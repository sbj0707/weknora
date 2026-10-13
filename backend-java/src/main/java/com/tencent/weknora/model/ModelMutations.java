package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Only DB/pure-catalog mutations; Ollama jobs and connectivity routes remain on Go. */
@Service
public class ModelMutations {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final ModelRepository reads;private final ModelCatalogRuntime catalog;private final ModelURLPolicy urls;private final ModelParametersCodec params;
    public ModelMutations(JdbcTemplate jdbc,ObjectMapper json,ModelRepository reads,ModelCatalogRuntime catalog,ModelURLPolicy urls,GoSecretCodec secrets){this.jdbc=jdbc;this.json=json;this.reads=reads;this.catalog=catalog;this.urls=urls;this.params=new ModelParametersCodec(json,secrets);}
    private Map<String,Object> row(AuthContext auth,String id){var rows=jdbc.queryForList("SELECT * FROM models WHERE id=? AND (tenant_id=? OR is_builtin=true) AND deleted_at IS NULL",id,auth.tenantId());if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","Model not found");return new LinkedHashMap<>(rows.getFirst());}
    private static boolean builtin(Map<String,Object> row){Object b=row.get("is_builtin");return Boolean.TRUE.equals(b)||b instanceof Number n&&n.intValue()!=0||"true".equalsIgnoreCase(String.valueOf(b));}
    private static String str(Map<String,Object> row,String key){return row.get(key)==null?"":row.get(key).toString();}
    static String sanitize(String value){return value.replace('\n',' ').replace('\r',' ').replace('\t',' ').replaceAll("[\\x00-\\x1F]","");}
    private void validate(String name,String type,ObjectNode value){urls.validate(value.path("base_url").asText());if(!Set.of("KnowledgeQA","VLLM","Embedding","Rerank","ASR").contains(type))return;
        ObjectNode spec=value.hasNonNull("spec")?(ObjectNode)value.get("spec"):null;
        if(spec!=null)for(var fields=spec.path("thinking_levels").fieldNames();fields.hasNext();){String key=fields.next();if(!CatalogRuntime.LEVELS.contains(key)&&!Set.of("none","false","disabled","true","enabled","default","on").contains(key))throw ModelParametersCodec.bad("spec.thinking_levels: unknown level \""+key+"\"");}
        try{catalog.snapshot().resolve(new CatalogRuntime.Ref(value.path("provider").asText(),name,value.path("base_url").asText(),type,value.hasNonNull("extra_config")?(ObjectNode)value.get("extra_config"):json.createObjectNode(),spec,0));}catch(IllegalArgumentException ex){throw ModelParametersCodec.bad("model parameters: "+ex.getMessage());}
    }
    ObjectNode create(AuthContext auth,JsonNode request){
        String name=ModelParametersCodec.string(request,"name"),type=ModelParametersCodec.string(request,"type"),source=ModelParametersCodec.string(request,"source");
        if(name.isEmpty()||type.isEmpty()||source.isEmpty())throw ModelParametersCodec.bad("name, type and source are required");
        ObjectNode value=params.request(request.get("parameters"));validate(name,type,value);
        if(!source.equals("remote"))throw new ApiException(503,"MODEL_DOWNLOAD_NOT_MIGRATED","Non-remote model creation requires the Go model download worker");
        String id=UUID.randomUUID().toString();Timestamp now=Timestamp.from(Instant.now());
        // PostgreSQL JSON columns need an explicit cast; SQLite accepts TEXT without a cast.
        jdbc.update("INSERT INTO models(id,tenant_id,name,display_name,type,source,description,parameters,is_default,is_builtin,managed_by,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,"+jsonParameter()+",false,false,'','active',?,?)",id,auth.tenantId(),sanitize(name),sanitize(ModelParametersCodec.string(request,"display_name")),sanitize(type),source,sanitize(ModelParametersCodec.string(request,"description")),params.encoded(value),now,now);
        return reads.get(auth,id);
    }
    ObjectNode update(AuthContext auth,String id,JsonNode request){
        reads.get(auth,id);Map<String,Object> row=row(auth,id);ObjectNode old=params.stored(row.get("parameters")),next=params.request(request.get("parameters"));
        String name=ModelParametersCodec.string(request,"name"),type=ModelParametersCodec.string(request,"type"),source=ModelParametersCodec.string(request,"source");
        validate(name.isEmpty()?str(row,"name"):name,type.isEmpty()?str(row,"type"):type,next);
        if(builtin(row)&&!auth.systemAdmin())throw new ApiException(403,"FORBIDDEN","only system administrators can update builtin models");
        next.put("api_key",old.path("api_key").asText());if(!old.path("app_secret").asText().isEmpty())next.put("app_secret",old.path("app_secret").asText());else next.remove("app_secret");
        next.put("parameter_size",old.path("parameter_size").asText());for(String field:List.of("interface_type","app_id"))if(next.path(field).asText().isEmpty()&&old.has(field))next.set(field,old.get(field));
        preserveExtras(old,next,catalog.snapshot());
        if(!name.isEmpty())row.put("name",name);if(request.hasNonNull("display_name"))row.put("display_name",sanitize(ModelParametersCodec.string(request,"display_name")));
        row.put("description",ModelParametersCodec.string(request,"description"));row.put("source",source);row.put("type",type);if(builtin(row))row.put("managed_by","");
        save(row,next);return reads.project(row,auth);
    }
    private static String vendor(ObjectNode value,CatalogRuntime runtime){String id=CatalogRuntime.trim(value.path("provider").asText()).toLowerCase(Locale.ROOT);return id.isEmpty()?runtime.detect(value.path("base_url").asText()):id;}
    static void preserveExtras(ObjectNode old,ObjectNode next,CatalogRuntime runtime){
        Set<String> keys=new HashSet<>();CatalogRuntime.Provider provider=runtime.providers.get(vendor(old,runtime));if(provider!=null)for(JsonNode field:provider.definition().path("ExtraFields"))if(field.path("secret").asBoolean()||field.path("type").asText().equals("password"))keys.add(field.path("key").asText());
        boolean named=!CatalogRuntime.trim(next.path("provider").asText()).isEmpty()||!CatalogRuntime.trim(next.path("base_url").asText()).isEmpty(),switched=named&&!vendor(old,runtime).equals(vendor(next,runtime));
        ObjectNode stored=old.hasNonNull("extra_config")?(ObjectNode)old.get("extra_config"):null,incoming=next.hasNonNull("extra_config")?(ObjectNode)next.get("extra_config"):null;
        if(incoming==null){if(stored==null)return;incoming=stored.deepCopy();if(switched)keys.forEach(incoming::remove);next.set("extra_config",incoming);return;}
        for(String key:keys){String value=CatalogRuntime.trim(incoming.path(key).asText());boolean mask=value.isEmpty()||value.matches("[*•·●]+");if(switched){if(mask)incoming.remove(key);}else if(mask&&stored!=null&&!stored.path(key).asText().isEmpty())incoming.set(key,stored.get(key));}
    }
    private String jsonParameter(){try(var c=Objects.requireNonNull(jdbc.getDataSource()).getConnection()){return c.getMetaData().getDatabaseProductName().equals("PostgreSQL")?"CAST(? AS jsonb)":"?";}catch(java.sql.SQLException ex){throw new IllegalStateException(ex);}}
    private void save(Map<String,Object> row,ObjectNode value){Timestamp now=Timestamp.from(Instant.now());String encoded=params.encoded(value);jdbc.update("UPDATE models SET name=?,display_name=?,description=?,source=?,type=?,parameters="+jsonParameter()+",managed_by=?,updated_at=? WHERE id=? AND tenant_id=? AND deleted_at IS NULL",row.get("name"),row.get("display_name"),row.get("description"),row.get("source"),row.get("type"),encoded,str(row,"managed_by"),now,row.get("id"),row.get("tenant_id"));row.put("parameters",encoded);row.put("updated_at",now);}
    ObjectNode credentials(AuthContext auth,String id,JsonNode request,boolean clear,String field){
        boolean provided=request!=null&&(request.hasNonNull("api_key")||request.hasNonNull("app_secret"));
        if(!clear&&!provided)try{reads.get(auth,id);}catch(ApiException ex){throw new ApiException(404,"NOT_FOUND","Model not found");}
        Map<String,Object> row=row(auth,id);ObjectNode value=params.stored(row.get("parameters"));
        if((clear||provided)&&builtin(row)&&!auth.systemAdmin())throw new ApiException(403,"FORBIDDEN","only system administrators can modify builtin model credentials");
        boolean changed=false;
        for(String key:List.of("api_key","app_secret")){if(clear&&!key.equals(field))continue;String edited=clear?"":ModelParametersCodec.string(request,key);if((clear||!edited.isEmpty())&&!edited.equals(value.path(key).asText())){value.put(key,edited);changed=true;}}
        if(changed){if(builtin(row))row.put("managed_by","");save(row,value);}
        ObjectNode result=json.createObjectNode(),fields=json.createObjectNode();for(String key:List.of("api_key","app_secret"))fields.set(key,json.createObjectNode().put("configured",!value.path(key).asText().isEmpty()));result.set("fields",fields);return result;
    }
    void delete(AuthContext auth,String id){Map<String,Object> row=row(auth,id);if(builtin(row))throw ModelParametersCodec.bad("builtin models cannot be deleted");ObjectNode usage=new ModelUsage(jdbc,json).details(auth.tenantId(),id);
        int kb=usage.path("knowledge_base_total").asInt(),agents=usage.path("agent_total").asInt();boolean memory=!usage.path("long_term_memory").path("bindings").isEmpty();
        if(kb>0||agents>0||memory){List<String> parts=new ArrayList<>();if(kb>0)parts.add(kb+" knowledge base(s)");if(agents>0)parts.add(agents+" agent(s)");if(memory)parts.add("long-term memory");throw ModelParametersCodec.bad("model is used by "+String.join(" and ",parts)+"; reconfigure or remove those references before deleting").withAppCode(2300).withDetails(usage);}
        jdbc.update("UPDATE models SET deleted_at=? WHERE id=? AND tenant_id=? AND deleted_at IS NULL",Timestamp.from(Instant.now()),id,auth.tenantId());
    }
}
