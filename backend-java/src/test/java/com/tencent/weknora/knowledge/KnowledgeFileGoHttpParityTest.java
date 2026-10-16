package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Actual Go production file service and HTTP transport, complete bytes/headers and role-denial audit. */
class KnowledgeFileGoHttpParityTest extends KnowledgeFolderGoHttpParityTest {
  @Override @TestFactory Stream<DynamicTest> folders()throws Exception {
    JsonNode rows;try(var in=getClass().getResourceAsStream("/go-knowledge-file-http.json")){rows=json.readTree(in);}
    assertThat(rows.size()).isEqualTo(209);var sequence=new AtomicInteger();return StreamSupport.stream(rows.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }
  @Override void compare(JsonNode oracle,int no)throws Exception {
    var f=database(no);try {
      if(f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("SQLite")))
        try(var c=f.jdbc.getDataSource().getConnection()){org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.FileSystemResource("../migrations/sqlite/000004_memory.up.sql"));}
      for(String table:List.of("knowledges","knowledge_bases","tenants"))f.jdbc.update("DELETE FROM "+table);
      boolean postgres=Boolean.TRUE.equals(f.jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
      for(var seed:oracle.path("seed")){
        var names=new ArrayList<String>();var values=new ArrayList<Object>();seed.path("row").fields().forEachRemaining(e->{names.add(e.getKey());JsonNode value=e.getValue();try{values.add(value.isObject()&&value.has("base64")?Base64.getDecoder().decode(value.path("base64").asText()):json.treeToValue(value,Object.class));}catch(Exception failure){throw new IllegalStateException(failure);}});
        if(postgres)for(int i=0;i<values.size();i++)if(values.get(i) instanceof byte[] bytes)values.set(i,new String(bytes,StandardCharsets.UTF_8));
        f.jdbc.update("INSERT INTO "+seed.path("table").asText()+"("+String.join(",",names)+") VALUES("+String.join(",",Collections.nCopies(names.size(),"?"))+")",values.toArray());
      }
      Path base=temp.resolve("files-"+no),file=base.resolve("1/d1/source");Files.createDirectories(file.getParent());if(!oracle.path("variant").asText().equals("file-missing"))Files.write(file,Base64.getDecoder().decode(oracle.path("file_base64").asText()));
      var env=new MockEnvironment().withProperty("LOCAL_STORAGE_BASE_DIR",base.toString()).withProperty("STORAGE_TYPE","local");
      var beans=new DefaultListableBeanFactory();beans.registerSingleton("jdbc",f.jdbc);beans.registerSingleton("json",json);beans.registerSingleton("tx",f.manager);beans.registerSingleton("authRepository",new AuthRepository(f.jdbc,json));beans.registerSingleton("codec",new KnowledgeBaseCodec(json));
      var audit=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.DeniedRoleAuditService"));audit.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("audit",audit);
      var writes=beans.createBean(KnowledgeBaseWriteAuthorization.class);beans.registerSingleton("writes",writes);
      var guard=new RootBeanDefinition(Class.forName("com.tencent.weknora.security.NativeRoleGuard"));guard.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("guard",guard);
      var reads=new KnowledgeBaseReadAuthorization(f.jdbc,new KnowledgeBaseCodec(json),json,Set.of());var contentRepo=new KnowledgeContentReadRepository(f.jdbc,json);var contentReads=new KnowledgeContentReadAuthorization(contentRepo,reads);
      var controller=new KnowledgeFileController(contentReads,writes,new KnowledgeFileService(f.jdbc,new StorageResolver(f.jdbc,json,env),json));
      var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).addInterceptors((org.springframework.web.servlet.HandlerInterceptor)beans.getBean("guard")).setControllerAdvice(new com.tencent.weknora.api.ApiErrorAdvice()).build();
      var scope=oracle.path("scope");var caps=new HashSet<String>();var bases=new HashSet<String>();scope.path("Capabilities").forEach(v->caps.add(v.asText()));scope.path("KnowledgeBaseIDs").forEach(v->bases.add(v.asText()));
      var auth=new AuthContext(oracle.path("user").asText(),oracle.path("caller_tenant").asLong(),oracle.path("effective_role").asText(),false,oracle.path("machine").asBoolean()?0L:null,scope.path("FullAccess").asBoolean(),caps,bases);
      var before=json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM knowledges ORDER BY id"));var tenants=json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM tenants ORDER BY id"));
      var request=MockMvcRequestBuilders.get(java.net.URI.create(oracle.path("target").asText())).requestAttr(CurrentAuth.ATTRIBUTE,auth);oracle.path("request_headers").fields().forEachRemaining(e->request.header(e.getKey(),e.getValue().asText()));
      var response=mvc.perform(request).andReturn().getResponse();byte[] actual=response.getContentAsByteArray();byte[] expected=Base64.getDecoder().decode(oracle.path("body_base64").asText());
      assertThat(response.getStatus()).as("actual Go HTTP status, Java body=%s",response.getContentAsString()).isEqualTo(oracle.path("status").asInt());
      var headers=new TreeMap<String,String>();for(String key:response.getHeaderNames())headers.put(key,response.getHeader(key));String type=Objects.toString(headers.get("Content-Type"),"");
      if(type.startsWith("multipart/byteranges; boundary=")){String boundary=type.substring(type.indexOf('=')+1);assertThat(boundary).matches("[0-9a-f]{60}");headers.put("Content-Type","multipart/byteranges; boundary="+"b".repeat(60));actual=new String(actual,StandardCharsets.UTF_8).replace(boundary,"b".repeat(60)).getBytes(StandardCharsets.UTF_8);}
      if(response.getStatus()>=400&&type.startsWith("application/json")){
        assertThat(canonical(json.readTree(new String(actual,StandardCharsets.UTF_8).replace(base.toString(),"_BASE_")))).as("complete Go error DTO").isEqualTo(canonical(json.readTree(expected)));
        // JSON media types specify UTF-8 by RFC8259; Servlet omits the redundant charset.
        headers.put("Content-Type","application/json; charset=utf-8");
      }else assertThat(actual).as("all original bytes, exact byte ranges or manual Markdown").isEqualTo(expected);
      assertThat(json.<JsonNode>valueToTree(headers)).as("all Go content/disposition/cache/range headers").isEqualTo(oracle.path("headers"));
      var audits=f.jdbc.queryForList("SELECT tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details FROM audit_logs ORDER BY id");for(var a:audits)a.put("details",json.readTree(Objects.toString(a.get("details"))));assertThat(canonical(json.valueToTree(audits.isEmpty()?null:audits))).as("original caller role-denial audits").isEqualTo(canonical(oracle.path("audits")));
      assertThat(json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM knowledges ORDER BY id"))).as("no document mutation").isEqualTo(before);assertThat(json.<JsonNode>valueToTree(f.jdbc.queryForList("SELECT * FROM tenants ORDER BY id"))).as("no quota mutation").isEqualTo(tenants);assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Integer.class)).isZero();
    }finally{release(f);}
  }
}
