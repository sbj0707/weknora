package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mirrors Go's complete active-tenant reference graph, returning identification only. */
final class ModelUsage {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    ModelUsage(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    ObjectNode details(long tenant,String id){ObjectNode out=json.createObjectNode();ArrayNode kbs=json.createArrayNode(),agents=json.createArrayNode(),memory=json.createArrayNode();long kbCount=0,agentCount=0;
        for(var row:jdbc.queryForList("SELECT id,name,embedding_model_id,summary_model_id,image_processing_config,vlm_config,asr_config,wiki_config,auto_tag_config FROM knowledge_bases WHERE tenant_id=? AND deleted_at IS NULL ORDER BY name ASC,id ASC",tenant)){
            ArrayNode bindings=json.createArrayNode();if(id.equals(row.get("embedding_model_id")))bindings.add("embedding_model");if(id.equals(row.get("summary_model_id")))bindings.add("summary_model");
            for(String[] binding:List.of(new String[]{"image_processing_config","model_id","image_processing_model"},new String[]{"vlm_config","model_id","vlm_model"},new String[]{"asr_config","model_id","asr_model"},new String[]{"wiki_config","synthesis_model_id","wiki_synthesis_model"},new String[]{"auto_tag_config","model_id","auto_tag_model"}))if(id.equals(parse(row.get(binding[0])).path(binding[1]).asText()))bindings.add(binding[2]);
            if(!bindings.isEmpty()){kbCount++;if(kbs.size()<50)kbs.add(resource(row,bindings));}
        }
        for(var row:jdbc.queryForList("SELECT id,name,config FROM custom_agents WHERE tenant_id=? AND deleted_at IS NULL ORDER BY name ASC,id ASC",tenant)){
            JsonNode config=parse(row.get("config"));ArrayNode bindings=json.createArrayNode();
            for(String[] binding:List.of(new String[]{"model_id","chat_model"},new String[]{"rerank_model_id","rerank_model"},new String[]{"vlm_model_id","vlm_model"},new String[]{"asr_model_id","asr_model"},new String[]{"query_understand_model_id","query_understand_model"}))if(id.equals(config.path(binding[0]).asText()))bindings.add(binding[1]);
            if(id.equals(config.path("question_suggestions").path("follow_ups").path("model_id").asText()))bindings.add("follow_up_model");
            if(!bindings.isEmpty()){agentCount++;if(agents.size()<50)agents.add(resource(row,bindings));}
        }
        var tenants=jdbc.queryForList("SELECT memory_config FROM tenants WHERE id=?",tenant);if(!tenants.isEmpty()){JsonNode config=parse(tenants.getFirst().get("memory_config"));if(id.equals(CatalogRuntime.trim(config.path("embedding_model_id").asText())))memory.add("embedding_model");if(id.equals(CatalogRuntime.trim(config.path("extract_model_id").asText())))memory.add("extract_model");}
        out.set("knowledge_bases",kbs);out.set("agents",agents);out.set("long_term_memory",json.createObjectNode().set("bindings",memory));out.put("knowledge_base_total",kbCount);out.put("agent_total",agentCount);return out;
    }
    private ObjectNode resource(Map<String,Object> row,ArrayNode bindings){ObjectNode out=json.createObjectNode();out.put("id",row.get("id").toString());out.put("name",row.get("name").toString());out.set("bindings",bindings);return out;}
    private JsonNode parse(Object value){if(value==null)return json.createObjectNode();try{JsonNode node=json.readTree(value.toString());return node==null||node.isNull()?json.createObjectNode():node;}catch(java.io.IOException ex){throw new IllegalStateException("Invalid stored model reference configuration",ex);}}
}
