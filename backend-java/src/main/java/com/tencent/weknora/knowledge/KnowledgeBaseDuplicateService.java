package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.security.*;
import com.tencent.weknora.vectorstore.VectorStoreRegistry;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;

/** Go's settings-only duplicate, including JSON clone semantics and independent best-effort audit. */
@Service
public final class KnowledgeBaseDuplicateService {
  private static final List<String> JSON_COLUMNS=List.of("chunking_config","image_processing_config","vlm_config","asr_config","cos_config","storage_provider_config","extract_config","faq_config","question_generation_config","auto_tag_config","profile_config","generated_profile","wiki_config","indexing_strategy");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseCodec codec;
  private final KnowledgeBaseReadAuthorization reads;
  private final KnowledgeBaseRepository repository;
  private final VectorStoreRegistry stores;
  public KnowledgeBaseDuplicateService(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseCodec codec,KnowledgeBaseReadAuthorization reads,KnowledgeBaseRepository repository,VectorStoreRegistry stores) {
    this.jdbc=jdbc;this.json=json;this.codec=codec;this.reads=reads;this.repository=repository;this.stores=stores;
  }
  public Map<String,Object> duplicate(AuthContext caller,String sourceId,String agentId,String sourceTenant,String language) {
    caller.requireRole("contributor");
    reads.authorizeManagementRead(caller,sourceId,agentId,sourceTenant);
    boolean sqlite=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("SQLite")));
    var source=load(sourceId,null,sqlite);
    if(source.path("tenant_id").asLong()!=caller.tenantId())throw new ApiException(403,"FORBIDDEN","No permission to duplicate this knowledge base");
    // The production service trims and independently re-reads after the handler's check.
    String responseSourceId=sourceId;
    sourceId=KnowledgeUploadForms.goTrim(sourceId);
    if(sourceId.isEmpty())throw new ApiException(400,"BAD_REQUEST","source knowledge base ID cannot be empty");
    source=load(sourceId,caller.tenantId(),sqlite);
    codec.ensureDefaults(source);
    var target=codec.fromPayload(codec.render(source));
    target.remove("generated_profile");
    String id=UUID.randomUUID().toString();
    target.put("id",id).put("tenant_id",caller.tenantId()).put("name",copyName(caller.tenantId(),source.path("name").asText(),language,sqlite));
    String user=Objects.toString(caller.userId(),"");
    target.put("creator_id",user.matches("system-[0-9]+")?"":user);
    Instant now=Instant.now();target.put("created_at",now.toString()).put("updated_at",now.toString());target.putNull("deleted_at");
    target.put("is_temporary",false).put("is_pinned",false).putNull("pinned_at");
    target.put("knowledge_count",0).put("chunk_count",0).put("is_processing",false).put("processing_count",0).put("share_count",0);target.remove("creator_name");
    codec.ensureDefaults(target);
    if(target.path("vector_store_id").asText().isEmpty())target.remove("vector_store_id");
    verifyBinding(caller,target);
    insert(target,sqlite,now);
    audit(caller,sourceId,target,sqlite);
    return Map.of("source_id",responseSourceId,"target_id",id,"message","Knowledge base duplicate created","knowledge_base",repository.decorateStore(codec.render(target)));
  }
  private ObjectNode load(String id,Long tenant,boolean sqlite) {
    var rows=tenant==null?jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",id)
      :jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND tenant_id=? AND deleted_at IS NULL",id,tenant);
    if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","Source knowledge base not found");
    return codec.fromDatabaseRow(rows.getFirst(),sqlite);
  }
  private String copyName(long tenant,String name,String language,boolean sqlite) {
    String locale=Objects.toString(language,"").toLowerCase(Locale.ROOT),suffix=locale.startsWith("zh")?" 副本":locale.startsWith("ko")?" 사본":locale.startsWith("ru")?" копия":" Copy";
    String base=KnowledgeUploadForms.goTrim(name);
    if(base.isEmpty())base=locale.startsWith("zh")?"知识库":locale.startsWith("ko")?"지식베이스":locale.startsWith("ru")?"База знаний":"Knowledge Base";
    String first=base+suffix;
    Set<String> names=new HashSet<>();
    try {
      for(var row:jdbc.queryForList("SELECT * FROM knowledge_bases WHERE tenant_id=? AND is_temporary=? AND deleted_at IS NULL ORDER BY created_at DESC",tenant,false))names.add(codec.fromDatabaseRow(row,sqlite).path("name").asText());
    }catch(org.springframework.dao.DataAccessException|ApiException unavailable){return first;}
    if(!names.contains(first))return first;
    for(long n=2;;n++){String next=first+" "+n;if(!names.contains(next))return next;}
  }
  private void verifyBinding(AuthContext caller,ObjectNode target) {
    String id=target.path("vector_store_id").asText();if(id.isEmpty())return;
    boolean uuid=id.matches("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|(?i:urn:uuid:)[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|\\{[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\}");
    if(!uuid)throw invalidBinding();
    try {
      if(jdbc.queryForList("SELECT id FROM vector_stores WHERE id=? AND tenant_id=? AND deleted_at IS NULL",id,caller.tenantId()).isEmpty())throw invalidBinding();
    }catch(org.springframework.dao.DataAccessException error){throw new ApiException(500,"INTERNAL_SERVER_ERROR","failed to verify vector store binding");}
    try(var call=new CallContext()) {stores.resolve(caller.tenantId(),id,call);}
    catch(VectorStoreRegistry.Failure error) {
      if(error.reason()==VectorStoreRegistry.Reason.FORBIDDEN)throw invalidBinding();
      throw new ApiException(400,"VECTOR_STORE_UNAVAILABLE","vector store is currently unavailable; check its connection configuration").withAppCode(2201);
    }
  }
  private static ApiException invalidBinding(){return new ApiException(400,"VECTOR_STORE_INVALID","vector store not found").withAppCode(2200);}
  private void insert(ObjectNode kb,boolean sqlite,Instant now) {
    var columns=new ArrayList<>(List.of("id","tenant_id","creator_id","name","description","type","is_temporary","embedding_model_id","summary_model_id","storage_backend_id","vector_store_id","created_at","updated_at","deleted_at"));columns.addAll(JSON_COLUMNS);
    var values=new ArrayList<Object>();var markers=new ArrayList<String>();
    for(String column:columns) {
      var value=kb.get(column.equals("cos_config")?"storage_config":column);
      Object bound=null;
      if(value!=null&&!value.isNull()) {
        if(JSON_COLUMNS.contains(column))bound=sqlite?value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8):value.toString();
        else if(column.endsWith("_at"))bound=sqlite?GoSqliteTimestamp.write(now):Timestamp.from(now);
        else if(value.isTextual())bound=value.asText();else if(value.isBoolean())bound=value.asBoolean();else bound=value.asLong();
      }
      values.add(bound);markers.add(!sqlite&&JSON_COLUMNS.contains(column)?"CAST(? AS JSONB)":"?");
    }
    try {jdbc.update("INSERT INTO knowledge_bases("+String.join(",",columns)+") VALUES("+String.join(",",markers)+")",values.toArray());}
    catch(org.springframework.dao.DataAccessException failure){throw databaseFailure(failure);}
  }
  private static ApiException databaseFailure(org.springframework.dao.DataAccessException failure) {
    Throwable cause=failure.getMostSpecificCause();String message=Objects.toString(cause.getMessage(),"");
    if(cause instanceof java.sql.SQLException sql&&sql.getSQLState()!=null&&cause.getClass().getName().equals("org.postgresql.util.PSQLException"))
      message=message.split("\n",2)[0]+" (SQLSTATE "+sql.getSQLState()+")";
    else if(cause.getClass().getName().equals("org.sqlite.SQLiteException")) {
      var match=java.util.regex.Pattern.compile("^\\[SQLITE_[A-Z_]+\\] [^(]+ \\((.*)\\)$",java.util.regex.Pattern.DOTALL).matcher(message);
      if(match.matches())message=match.group(1);
    }
    return new ApiException(500,"INTERNAL_SERVER_ERROR",message);
  }
  private void audit(AuthContext caller,String sourceId,ObjectNode target,boolean sqlite) {
    try {
      var details=json.createObjectNode().put("source_kb_id",sourceId).put("name",target.path("name").asText());
      if(caller.isApiKey()) {
        if(caller.apiKeyId()!=null&&caller.apiKeyId()>0)details.put("api_key_id",caller.apiKeyId());
        var names=jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?",caller.apiKeyId());
        if(!names.isEmpty()&&!Objects.toString(names.getFirst().get("name"),"").isEmpty())details.put("api_key_name",names.getFirst().get("name").toString());
      }
      String actor=Objects.toString(caller.userId(),"");
      jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,scope_type,scope_id,target_type,target_id,outcome,details) VALUES(?,?,?,'kb.duplicated','knowledge_base',?,'knowledge_base',?,'success',"+(sqlite?"?":"CAST(? AS JSONB)")+")",caller.tenantId(),actor,actor.isEmpty()?"":caller.role(),target.path("id").asText(),target.path("id").asText(),details.toString());
    }catch(Exception error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Knowledge base duplicate audit failed after create");}
  }
}
