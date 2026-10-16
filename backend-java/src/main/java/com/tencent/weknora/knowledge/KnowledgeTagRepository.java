package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tag data is always bounded by the authoritative KB owner and KB ID. */
public final class KnowledgeTagRepository {
  @FunctionalInterface public interface Cleanup {void enqueue(long owner,ObjectNode base,List<String> ids,JsonNode engines);}
  @FunctionalInterface public interface DocumentCleanup {void enqueue(AuthContext caller,long owner,ObjectNode base,List<String> ids);}
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final TransactionTemplate tx;private final Cleanup cleanup;private final boolean postgres;private final String driver;
  public KnowledgeTagRepository(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager,Cleanup cleanup,String driver){this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(manager);this.cleanup=cleanup;this.driver=driver;postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  private DocumentCleanup documents;
  public KnowledgeTagRepository withDocuments(DocumentCleanup documents){this.documents=Objects.requireNonNull(documents);return this;}
  public Map<String,Object> list(KnowledgeBaseReadAuthorization.ReadAccess grant,long page,long size,String keyword){
    long owner=grant.effectiveTenantId();String kb=grant.knowledgeBaseId();var args=new ArrayList<Object>(List.of(owner,kb));String where="tenant_id=? AND knowledge_base_id=?";
    keyword=AuthPreferences.trimGoSpace(keyword);if(!keyword.isEmpty()){where+=" AND name LIKE ? ESCAPE ?";args.add("%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");args.add("\\");}
    long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_tags WHERE "+where,Long.class,args.toArray()));
    var values=new ArrayList<>(args);values.add(size);values.add((page-1)*size);
    var data=jdbc.queryForList("SELECT * FROM knowledge_tags WHERE "+where+" ORDER BY sort_order ASC,created_at DESC,seq_id DESC LIMIT ? OFFSET ?",values.toArray());
    var results=new ArrayList<ObjectNode>();
    var statistics=new HashMap<String,long[]>();
    if(!data.isEmpty()){var countArgs=new ArrayList<Object>(List.of(owner,kb));for(var row:data){String id=row.get("id").toString();statistics.put(id,new long[2]);countArgs.add(id);}String marks=String.join(",",Collections.nCopies(data.size(),"?"));
      for(var count:jdbc.queryForList("SELECT r.tag_id,COUNT(*) AS count FROM knowledge_tag_relations r JOIN knowledges k ON k.id=r.knowledge_id AND k.deleted_at IS NULL AND k.tenant_id=? AND k.knowledge_base_id=? WHERE r.tag_id IN ("+marks+") GROUP BY r.tag_id",countArgs.toArray()))statistics.get(count.get("tag_id").toString())[0]=number(count,"count");
      for(var count:jdbc.queryForList("SELECT tag_id,COUNT(*) AS count FROM chunks WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL AND tag_id IN ("+marks+") GROUP BY tag_id",countArgs.toArray()))statistics.get(count.get("tag_id").toString())[1]=number(count,"count");
    }
    for(var row:data){var tag=render(row);var counts=statistics.get(tag.path("id").asText());tag.put("knowledge_count",counts[0]).put("chunk_count",counts[1]);results.add(tag);}
    return Map.of("total",total,"page",page,"page_size",size,"data",results);
  }
  public String resolve(long owner,String raw){
    String id=KnowledgeImportNames.sanitizeForLog(raw);if(id.matches("[+-]?[0-9]+"))try{long seq=Long.parseLong(id);var rows=jdbc.queryForList("SELECT id FROM knowledge_tags WHERE tenant_id=? AND seq_id=? LIMIT 1",owner,seq);if(rows.isEmpty())throw error(404,1003,"标签不存在");return rows.getFirst().get("id").toString();}catch(NumberFormatException ignored){}return id;
  }
  public ObjectNode create(AuthContext caller,KnowledgeBaseWriteAuthorization.WriteAccess grant,KnowledgeTagRequestDecoder.Change change){
    String name=AuthPreferences.trimGoSpace(KnowledgeImportNames.sanitizeForLog(change.name())),color=AuthPreferences.trimGoSpace(KnowledgeImportNames.sanitizeForLog(change.color()));if(name.isEmpty())throw error(400,1000,"知识库ID和标签名称不能为空");
    long owner=grant.effectiveTenantId();String kb=grant.knowledgeBaseId(),id=UUID.randomUUID().toString();long sort=name.equals("未分类")?-1:change.sortOrder();Instant now=Instant.now();
    var result=tx.execute(status->{assertOwner(grant);if(!jdbc.queryForList("SELECT id FROM knowledge_tags WHERE tenant_id=? AND knowledge_base_id=? AND name=? LIMIT 1",owner,kb,name).isEmpty())throw error(409,1005,"标签名称已存在");
      if(postgres)jdbc.update("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,color,sort_order,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",id,owner,kb,name,color,sort,time(now),time(now));
      else{long seq=Objects.requireNonNullElse(jdbc.queryForObject("SELECT MAX(seq_id) FROM knowledge_tags",Long.class),0L)+1;jdbc.update("INSERT INTO knowledge_tags(id,tenant_id,knowledge_base_id,name,color,sort_order,seq_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",id,owner,kb,name,color,sort,seq,time(now),time(now));}
      return render(find(owner,id));});audit(caller,grant,result,"tag.created",json.createObjectNode().put("name",name));return result;
  }
  public ObjectNode update(AuthContext caller,KnowledgeBaseWriteAuthorization.WriteAccess grant,String id,KnowledgeTagRequestDecoder.Change change){
    var result=tx.execute(status->{assertOwner(grant);var row=find(grant.effectiveTenantId(),id);tagScope(row,grant);String name=change.name()==null?Objects.toString(row.get("name"),""):AuthPreferences.trimGoSpace(change.name());if(name.isEmpty())throw error(400,1000,"标签名称不能为空");String color=change.color()==null?Objects.toString(row.get("color"),""):AuthPreferences.trimGoSpace(change.color());long sort=change.sortOrder()==null?number(row,"sort_order"):change.sortOrder();jdbc.update("UPDATE knowledge_tags SET name=?,color=?,sort_order=?,updated_at=? WHERE tenant_id=? AND knowledge_base_id=? AND id=?",name,color,sort,time(Instant.now()),grant.effectiveTenantId(),grant.knowledgeBaseId(),id);return render(find(grant.effectiveTenantId(),id));});audit(caller,grant,result,"tag.updated",json.createObjectNode().put("name",result.path("name").asText()));return result;
  }
  public List<String> exclusions(long owner,String kb,List<Long> ids){
    if(ids.isEmpty())return List.of();var wanted=new LinkedHashSet<>(ids);for(long id:wanted)if(id<=0)throw error(400,1000,"排除条目 ID 必须为正整数");
    String marks=String.join(",",Collections.nCopies(wanted.size(),"?"));var args=new ArrayList<Object>();args.add(owner);args.addAll(wanted);var rows=jdbc.queryForList("SELECT id,seq_id,tenant_id,knowledge_base_id,chunk_type FROM chunks WHERE tenant_id=? AND seq_id IN ("+marks+") AND deleted_at IS NULL",args.toArray());var result=new ArrayList<String>();for(var row:rows){if(!Objects.equals(row.get("knowledge_base_id"),kb)||!Objects.equals(row.get("chunk_type"),"faq"))throw error(403,1002,"排除条目不属于当前知识库");result.add(row.get("id").toString());wanted.remove(number(row,"seq_id"));}if(!wanted.isEmpty())throw error(404,1003,"排除条目不存在");return result;
  }
  public void delete(AuthContext caller,KnowledgeBaseWriteAuthorization.WriteAccess grant,String id,boolean force,boolean contentOnly,List<String> excluded){
    long owner=grant.effectiveTenantId();String kb=grant.knowledgeBaseId();var row=find(owner,id);tagScope(row,grant);if(!excluded.isEmpty()&&!grant.knowledgeBase().path("type").asText().equals("faq"))throw error(400,1000,"仅 FAQ 条目删除支持排除条目");
    JsonNode effectiveEngines=engines(owner);
    long[] counts=counts(owner,kb,id);if(!contentOnly&&!force&&(counts[0]>0||counts[1]>0))throw error(400,1000,"标签仍有知识或FAQ条目引用，无法删除");
    var deleted=new ArrayList<String>();
    if(contentOnly||force){boolean document=grant.knowledgeBase().path("type").asText().equals("document")&&counts[0]>0;
      if(document){if(documents==null)throw error(501,1008,"Native knowledge-list deletion is not migrated");var ids=tx.execute(status->{assertOwner(grant);tagScope(find(owner,id),grant);return jdbc.queryForList("SELECT k.id FROM knowledges k JOIN knowledge_tag_relations r ON r.knowledge_id=k.id WHERE k.tenant_id=? AND k.knowledge_base_id=? AND k.deleted_at IS NULL AND r.tag_id=?",String.class,owner,kb,id);});if(ids!=null&&!ids.isEmpty())try{documents.enqueue(caller,owner,grant.knowledgeBase(),ids);}catch(RuntimeException e){throw error(500,1007,"删除标签下的文档失败");}}
      else if(counts[1]>0)tx.executeWithoutResult(status->{assertOwner(grant);tagScope(find(owner,id),grant);for(var chunk:jdbc.queryForList("SELECT id FROM chunks WHERE tenant_id=? AND knowledge_base_id=? AND tag_id=? AND deleted_at IS NULL",String.class,owner,kb,id))if(!excluded.contains(chunk))deleted.add(chunk);for(int i=0;i<deleted.size();i+=1000){var batch=deleted.subList(i,Math.min(i+1000,deleted.size()));String marks=String.join(",",Collections.nCopies(batch.size(),"?"));var args=new ArrayList<Object>();args.add(time(Instant.now()));args.add(owner);args.add(kb);args.addAll(batch);jdbc.update("UPDATE chunks SET deleted_at=? WHERE tenant_id=? AND knowledge_base_id=? AND id IN ("+marks+") AND deleted_at IS NULL",args.toArray());}});
    }
    if(!deleted.isEmpty())try{cleanup.enqueue(owner,grant.knowledgeBase(),deleted,effectiveEngines);}catch(RuntimeException ignored){}
    boolean retained=contentOnly||!excluded.isEmpty();if(!retained)tx.executeWithoutResult(status->{assertOwner(grant);jdbc.update("DELETE FROM knowledge_tags WHERE tenant_id=? AND knowledge_base_id=? AND id=?",owner,kb,id);});
    var details=json.createObjectNode().put("name",Objects.toString(row.get("name"),""));if(retained)details.put("content_cleared",true).put("excluded_count",excluded.size());else details.put("force",force);audit(caller,grant,render(row),retained?"tag.updated":"tag.deleted",details);
  }
  private long[] counts(long owner,String kb,String id){return new long[]{Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_tag_relations r JOIN knowledges k ON k.id=r.knowledge_id AND k.deleted_at IS NULL AND k.tenant_id=? AND k.knowledge_base_id=? WHERE r.tag_id=?",Long.class,owner,kb,id)),Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE tenant_id=? AND knowledge_base_id=? AND tag_id=? AND deleted_at IS NULL",Long.class,owner,kb,id))};}
  private Map<String,Object> find(long owner,String id){var rows=jdbc.queryForList("SELECT * FROM knowledge_tags WHERE tenant_id=? AND id=?"+(postgres?" FOR UPDATE":""),owner,id);if(rows.isEmpty())throw internal();return rows.getFirst();}
  private void assertOwner(KnowledgeBaseWriteAuthorization.WriteAccess grant){var rows=jdbc.queryForList("SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL"+(postgres?" FOR UPDATE":""),grant.knowledgeBaseId());if(rows.isEmpty()||number(rows.getFirst(),"tenant_id")!=grant.effectiveTenantId())throw error(403,1002,"无权修改该知识库");}
  private static void tagScope(Map<String,Object> row,KnowledgeBaseWriteAuthorization.WriteAccess grant){if(number(row,"tenant_id")!=grant.effectiveTenantId()||!Objects.equals(row.get("knowledge_base_id"),grant.knowledgeBaseId()))throw error(403,1002,"无权修改该知识库");}
  private ObjectNode render(Map<String,Object> row){var tag=json.createObjectNode();for(String k:List.of("id","name","color","knowledge_base_id"))tag.put(k,Objects.toString(row.get(k),""));for(String k:List.of("tenant_id","seq_id","sort_order"))tag.put(k,number(row,k));for(String k:List.of("created_at","updated_at"))tag.put(k,KnowledgeBaseRepository.timestamp(row.get(k)));return tag;}
  private Object time(Instant value){return postgres?Timestamp.from(value):value.toString();}
  private JsonNode engines(long owner){var rows=jdbc.queryForList("SELECT retriever_engines FROM tenants WHERE id=?",owner);if(rows.isEmpty())throw internal();Object raw=rows.getFirst().get("retriever_engines");try{if(raw!=null&&(postgres||raw instanceof byte[])){var decoded=raw instanceof byte[] b?json.readTree(b):json.readTree(raw.toString());var engines=decoded.isArray()?decoded:decoded.path("engines");if(engines.isArray()&&!engines.isEmpty())return engines;}}catch(java.io.IOException e){throw internal();}var result=json.createArrayNode();var seen=new HashSet<String>();for(String d:driver.split(",")){d=d.trim();String engine=switch(d){case "elasticsearch_v7","elasticsearch_v8"->"elasticsearch";default->d;};List<String> kinds=switch(d){case "milvus"->List.of("vector","keywords");case "elasticsearch_v7"->List.of("keywords");case "sqlite","postgres","elasticsearch_v8","qdrant","weaviate","doris","tencent_vectordb","opensearch"->List.of("keywords","vector");default->List.of();};for(String kind:kinds)if(seen.add(engine+":"+kind))result.add(json.createObjectNode().put("retriever_engine_type",engine).put("retriever_type",kind));}return result;}
  private void audit(AuthContext caller,KnowledgeBaseWriteAuthorization.WriteAccess grant,ObjectNode tag,String action,ObjectNode details){try{String actor=Objects.toString(caller.userId(),""),role=Objects.toString(caller.role(),"");if(actor.isEmpty())role="";if(caller.apiKeyId()!=null&&caller.apiKeyId()>0){details.put("api_key_id",caller.apiKeyId());var keys=jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=? AND tenant_id=?",caller.apiKeyId(),caller.tenantId());if(!keys.isEmpty()&&keys.getFirst().get("name")!=null&&!keys.getFirst().get("name").toString().isEmpty())details.put("api_key_name",keys.getFirst().get("name").toString());}jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details) VALUES(?,?,?,?,'knowledge_tag',?,'knowledge_base',?,'success',"+(postgres?"CAST(? AS JSONB)":"?")+")",grant.effectiveTenantId(),actor,role,action,tag.path("id").asText(),grant.knowledgeBaseId(),details.toString());}catch(RuntimeException ignored){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Tag activity audit failed after business mutation");}}
  private static long number(Map<String,Object> row,String key){Object value=row.get(key);return value==null?0:value instanceof Number n?n.longValue():Long.parseLong(value.toString());}
  static ApiException error(int status,int code,String message){return new ApiException(status,"TAG_ERROR",message).withAppCode(code);}
  static ApiException internal(){return error(500,1007,"Internal server error").withResponse(Map.of("success",false,"error",Map.of("code",1007,"message","Internal server error")));}
}
