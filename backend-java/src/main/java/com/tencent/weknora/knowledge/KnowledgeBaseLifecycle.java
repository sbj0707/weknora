package com.tencent.weknora.knowledge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeBaseLifecycle {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeBaseCodec codec;private final KnowledgeBaseBindings bindings;private final KnowledgeBaseRepository repository;private final TransactionTemplate transaction;
    private Boolean postgres;private final KnowledgeBaseWriteAuthorization writes;
    private static final List<String> JSON_FIELDS=List.of("chunking_config","image_processing_config","vlm_config","asr_config","storage_provider_config","extract_config","faq_config","question_generation_config","auto_tag_config","profile_config","wiki_config","indexing_strategy","generated_profile");
    private static final List<String> CREATE_FIELDS=List.of("id","name","description","tenant_id","type","is_temporary","creator_id","embedding_model_id","summary_model_id","storage_backend_id","vector_store_id","created_at","updated_at","deleted_at");
    public KnowledgeBaseLifecycle(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseCodec codec,KnowledgeBaseBindings bindings,KnowledgeBaseRepository repository,PlatformTransactionManager manager,KnowledgeBaseWriteAuthorization writes){this.writes=writes;this.jdbc=jdbc;this.json=json;this.codec=codec;this.bindings=bindings;this.repository=repository;transaction=new TransactionTemplate(manager);}
    public ObjectNode create(AuthContext auth,ObjectNode request) {
        auth.requireRole("contributor");auth.requireCapability("manage_kbs");
        ObjectNode kb=codec.fromPayload(request);validateExtract(kb.get("extract_config"));normalizePrompts(kb);validatePrompts(kb);
        if(kb.path("id").asText().isEmpty())kb.put("id",UUID.randomUUID().toString());
        kb.put("tenant_id",auth.tenantId());
        if(!auth.userId().startsWith("system-"))kb.put("creator_id",auth.userId());
        codec.ensureDefaults(kb);Instant now=Instant.now();kb.put("created_at",now.toString());kb.put("updated_at",now.toString());
        transaction.executeWithoutResult(status -> {bindings.resolveStorage(auth,kb);bindings.verifyVector(auth,kb);insert(kb);});
        audit(auth,kb,"kb.created",Map.of("name",kb.path("name").asText(),"type",kb.path("type").asText()));
        return repository.decorateStore(codec.render(kb));
    }
    public ObjectNode update(AuthContext auth,String id,ObjectNode request) {
        var grant=writes.authorizeUpdate(auth,id);
        ObjectNode[] changed={null};var fields=new ArrayList<String>();
        transaction.executeWithoutResult(status -> {
            var rows=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE tenant_id=? AND id=? AND deleted_at IS NULL"+(isPostgres()?" FOR UPDATE":""),grant.effectiveTenantId(),id);
            if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","knowledge base not found");
            var kb=codec.fromRow(rows.getFirst());
            JsonNode name=request.get("name"),description=request.get("description");
            if(name==null||!name.isTextual()||name.asText().isEmpty()||description!=null&&!description.isTextual()&&!description.isNull())throw new ApiException(400,"BAD_REQUEST","Invalid request parameters");
            if(!name.equals(kb.get("name")))fields.add("name");
            String text=description==null||description.isNull()?"":description.asText();if(!text.equals(kb.path("description").asText()))fields.add("description");
            boolean profileWasEnabled=kb.path("profile_config").path("enabled").asBoolean();kb.put("name",name.asText());kb.put("description",text);
            JsonNode configuration=request.get("config");
            if(configuration!=null&&!configuration.isNull()) {
                if(!configuration.isObject())throw new ApiException(400,"BAD_REQUEST","Invalid request parameters");fields.add("config");
                var probe=codec.fromPayload(json.createObjectNode());
                probe.set("chunking_config",codec.config("chunking_config",configuration.hasNonNull("chunking_config")?configuration.get("chunking_config"):json.createObjectNode()));
                for(String key:List.of("wiki_config","profile_config"))if(configuration.hasNonNull(key))probe.set(key,codec.config(key,configuration.get(key)));
                validatePrompts(probe);
                kb.set("chunking_config",probe.get("chunking_config"));
                for(String key:List.of("image_processing_config","faq_config","wiki_config","auto_tag_config","profile_config"))if(configuration.hasNonNull(key))kb.set(key,codec.config(key,configuration.get(key)));
                if(configuration.hasNonNull("indexing_strategy")) {
                    var strategy=codec.config("indexing_strategy",configuration.get("indexing_strategy"));
                    if(!KnowledgeBaseCodec.hasAnyIndexing(strategy))throw new ApiException(500,"INTERNAL_SERVER_ERROR","at least one indexing strategy must be enabled");
                    kb.set("indexing_strategy",strategy);
                    if(strategy.path("wiki_enabled").asBoolean()&&kb.path("wiki_config").isNull())kb.set("wiki_config",codec.config("wiki_config",json.createObjectNode()));
                    if(kb.path("extract_config").isObject())((ObjectNode)kb.get("extract_config")).put("enabled",strategy.path("graph_enabled").asBoolean());
                    else if(strategy.path("graph_enabled").asBoolean())kb.set("extract_config",codec.config("extract_config",json.createObjectNode().put("enabled",true)));
                }
            }
            codec.ensureDefaults(kb);
            if(!profileWasEnabled&&kb.path("profile_config").path("enabled").asBoolean()&&kb.path("type").asText().equals("document")&&!kb.path("is_temporary").asBoolean())throw new ApiException(501,"PROFILE_TASK_NOT_MIGRATED","Automatic profile refresh task is not migrated");
            kb.put("updated_at",Instant.now().toString());saveUpdate(kb);changed[0]=kb;
        });
        ObjectNode kb=Objects.requireNonNull(changed[0]);audit(auth,kb,"kb.updated",Map.of("name",kb.path("name").asText(),"changed_fields",fields));
        return repository.decorateStore(codec.render(kb));
    }
    private void insert(ObjectNode kb) {
        var columns=new ArrayList<>(CREATE_FIELDS);columns.addAll(JSON_FIELDS);columns.add("cos_config");
        var values=new ArrayList<Object>();var markers=new ArrayList<String>();
        for(String key:columns){values.add(parameter(kb,key));markers.add(jsonColumn(key)&&isPostgres()?"CAST(? AS JSONB)":"?");}
        jdbc.update("INSERT INTO knowledge_bases ("+String.join(",",columns)+") VALUES ("+String.join(",",markers)+")",values.toArray());
    }
    private void saveUpdate(ObjectNode kb) {
        // Limited columns preserve immutable bindings and concurrently generated profiles.
        var columns=new ArrayList<>(List.of("name","description","updated_at","chunking_config","image_processing_config","faq_config","wiki_config","auto_tag_config","profile_config","indexing_strategy","extract_config"));
        var values=new ArrayList<Object>();var assignments=new ArrayList<String>();
        for(String key:columns){values.add(parameter(kb,key));assignments.add(key+"="+(jsonColumn(key)&&isPostgres()?"CAST(? AS JSONB)":"?"));}
        values.add(kb.path("id").asText());values.add(kb.path("tenant_id").asLong());jdbc.update("UPDATE knowledge_bases SET "+String.join(",",assignments)+" WHERE id=? AND tenant_id=? AND deleted_at IS NULL",values.toArray());
    }
    private Object parameter(ObjectNode kb,String column) {
        JsonNode value=kb.get(column.equals("cos_config")?"storage_config":column);if(value==null||value.isNull())return null;
        if(jsonColumn(column))return value.toString();
        if(column.endsWith("_at"))return isPostgres()?Timestamp.from(Instant.parse(value.asText())):DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSSxxx").withZone(ZoneOffset.UTC).format(Instant.parse(value.asText()));
        if(value.isTextual())return value.asText();if(value.isBoolean())return value.asBoolean();if(value.isNumber())return value.asLong();throw new IllegalArgumentException("Invalid field "+column);
    }
    private static boolean jsonColumn(String column){return JSON_FIELDS.contains(column)||column.equals("cos_config");}
    private boolean isPostgres(){if(postgres==null)postgres=jdbc.execute((ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL"));return Boolean.TRUE.equals(postgres);}
    private void audit(AuthContext auth,ObjectNode kb,String action,Map<String,Object> details) {
        try {var data=new LinkedHashMap<String,Object>(details);if(auth.isApiKey()) {data.put("api_key_id",auth.apiKeyId());var names=jdbc.queryForList("SELECT name FROM tenant_api_keys WHERE id=?",auth.apiKeyId());if(!names.isEmpty())data.put("api_key_name",names.getFirst().get("name"));}
            jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,scope_type,scope_id,outcome,details) VALUES(?,?,?,?,'knowledge_base',?,'knowledge_base',?,'success',"+(isPostgres()?"CAST(? AS JSONB)":"?")+")",kb.path("tenant_id").asLong(),auth.userId(),auth.role(),action,kb.path("id").asText(),kb.path("id").asText(),json.writeValueAsString(data));}
        catch(Exception ex){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Knowledge base activity audit write failed after business commit");}
    }
    private static void normalizePrompts(ObjectNode kb) {for(var entry:PROMPTS.entrySet())if(kb.path(entry.getKey()).isObject())for(String field:entry.getValue())if(((ObjectNode)kb.get(entry.getKey())).has(field))((ObjectNode)kb.get(entry.getKey())).put(field,kb.path(entry.getKey()).path(field).asText().strip());}
    private static final Map<String,List<String>> PROMPTS=Map.of("chunking_config",List.of("table_metadata_instructions"),"vlm_config",List.of("custom_instructions"),"wiki_config",List.of("content_instructions","extraction_instructions"),"question_generation_config",List.of("custom_instructions"),"extract_config",List.of("custom_instructions"));
    private static void validatePrompts(ObjectNode kb) {
        var checks=new LinkedHashMap<String,String>();checks.put("table metadata instructions",kb.path("chunking_config").path("table_metadata_instructions").asText());checks.put("image instructions",kb.path("vlm_config").path("custom_instructions").asText());checks.put("wiki content instructions",kb.path("wiki_config").path("content_instructions").asText());checks.put("wiki extraction instructions",kb.path("wiki_config").path("extraction_instructions").asText());checks.put("question generation instructions",kb.path("question_generation_config").path("custom_instructions").asText());checks.put("knowledge base description instructions",kb.path("profile_config").path("custom_instructions").asText());checks.put("graph extraction instructions",kb.path("extract_config").path("custom_instructions").asText());
        checks.forEach((label,text) -> {if(text.codePointCount(0,text.length())>4000)throw new ApiException(400,"BAD_REQUEST",label+" exceeds 4000 characters");});
    }
    private static void validateExtract(JsonNode extract) {
        if(extract==null||!extract.path("enabled").asBoolean())return;
        if(extract.path("text").asText().isEmpty())throw bad("text cannot be empty");if(extract.path("tags").isEmpty())throw bad("tags cannot be empty");
        for(int i=0;i<extract.path("tags").size();i++)if(extract.path("tags").get(i).asText().isEmpty())throw bad("tag cannot be empty at index "+i);
        if(extract.path("nodes").isEmpty())throw bad("nodes cannot be empty");var names=new HashSet<String>();
        for(int i=0;i<extract.path("nodes").size();i++){String name=extract.path("nodes").get(i).path("name").asText();if(name.isEmpty())throw bad("node name cannot be empty at index "+i);if(!names.add(name))throw bad("duplicate node name: "+name);}
        if(extract.path("relations").isEmpty())throw bad("relations cannot be empty");
        for(int i=0;i<extract.path("relations").size();i++){var relation=extract.path("relations").get(i);for(String field:List.of("node1","node2","type"))if(relation.path(field).asText().isEmpty())throw bad("relation "+field+" cannot be empty at index "+i);for(String field:List.of("node1","node2"))if(!names.contains(relation.path(field).asText()))throw bad("relation references non-existent "+field+": "+relation.path(field).asText());}
    }
    private static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message);}
}
