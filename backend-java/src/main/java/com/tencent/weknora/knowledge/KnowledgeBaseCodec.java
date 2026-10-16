package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.ApiException;
import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Component;

/** Go struct JSON semantics, shared between persisted config and safe HTTP rendering. */
@Component
public class KnowledgeBaseCodec {
    private final ObjectMapper json;
    private final JsonNode schema;
    public KnowledgeBaseCodec(ObjectMapper json) throws IOException {
        this.json=json;
        try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/kb-schema.json"))) {schema=json.readTree(in);}
    }
    public ObjectNode fromPayload(ObjectNode input) {
        ObjectNode raw=input.deepCopy();
        if(!raw.hasNonNull("storage_config")&&raw.hasNonNull("cos_config"))raw.set("storage_config",raw.get("cos_config"));
        ObjectNode kb=(ObjectNode)decode(raw,schema);
        if(raw.hasNonNull("cos_config")&&kb.get("storage_config").equals(schema.path("fields").path("storage_config").get("zero")))kb.set("storage_config",decode(raw.get("cos_config"),schema.path("fields").path("storage_config")));
        if(kb.path("storage_provider_config").isNull()&&!kb.path("storage_config").path("provider").asText().isEmpty()) kb.set("storage_provider_config",json.createObjectNode().put("provider",kb.path("storage_config").path("provider").asText()));
        return kb;
    }
    public ObjectNode fromRow(Map<String,Object> row) {
        var input=json.createObjectNode();
        for(var entry:row.entrySet()) {
            String key=entry.getKey().toLowerCase(Locale.ROOT);if(key.equals("cos_config"))key="storage_config";
            if(!schema.path("fields").has(key)||Set.of("is_pinned","pinned_at").contains(key))continue;
            Object value=entry.getValue();JsonNode shape=schema.path("fields").path(key);
            if(value==null) {input.putNull(key);continue;}
            if(shape.path("kind").asText().equals("struct")) {
                try {input.set(key,json.readTree(value instanceof byte[] b?new String(b,java.nio.charset.StandardCharsets.UTF_8):value.toString()));}
                catch(IOException ex) {if(!key.equals("indexing_strategy"))throw new ApiException(500,"INVALID_KB_CONFIGURATION","Invalid persisted knowledge base JSON");}
            } else if(key.endsWith("_at"))input.put(key,KnowledgeBaseRepository.timestamp(value));
            else if(shape.path("kind").asText().equals("bool")&&(value instanceof Number||value instanceof String))input.put(key,value.toString().equals("1")||value.toString().equalsIgnoreCase("true"));
            else input.set(key,json.valueToTree(value));
        }
        if(!input.hasNonNull("indexing_strategy"))input.set("indexing_strategy",defaultIndexing());
        var kb=fromPayload(input);ensureDefaults(kb);return kb;
    }
    /** Match the production Go Scanner input types, rather than treating SQLite TEXT as BLOB. */
    public ObjectNode fromDatabaseRow(Map<String,Object> row, boolean sqlite) {
        var scanned=new LinkedHashMap<>(row);
        // These Go Scanners accept []byte only. GORM still allocates a non-null
        // pointer for a non-null TEXT value, leaving its configuration at zero.
        for(String field:List.of("chunking_config","image_processing_config","vlm_config","asr_config",
            "storage_provider_config","cos_config","question_generation_config","extract_config","faq_config","wiki_config","indexing_strategy"))
            if(sqlite&&scanned.get(field) instanceof String)scanned.put(field,field.equals("indexing_strategy")?defaultIndexing().toString():"{}");
        // GORM skips the Scanner for SQL NULL and leaves the struct at zero.
        // This matters for FAQ's early return in EnsureDefaults.
        if(scanned.get("indexing_strategy")==null)scanned.put("indexing_strategy","{}");
        var kb=fromRow(scanned);
        // The legacy-provider fallback belongs to Go's JSON request unmarshal,
        // not its database Scan or EnsureDefaults.
        if(row.get("storage_provider_config")==null)kb.putNull("storage_provider_config");
        return kb;
    }
    public ObjectNode render(ObjectNode kb) {
        var out=kb.deepCopy();
        ((ObjectNode)out.get("storage_config")).put("secret_id","").put("secret_key","");
        ((ObjectNode)out.get("vlm_config")).put("api_key","");
        var indexing=out.path("indexing_strategy");
        out.set("capabilities",json.createObjectNode().put("vector",indexing.path("vector_enabled").asBoolean()).put("keyword",indexing.path("keyword_enabled").asBoolean()).put("wiki",indexing.path("wiki_enabled").asBoolean()).put("graph",indexing.path("graph_enabled").asBoolean()&&out.path("extract_config").path("enabled").asBoolean()).put("faq",out.path("type").asText().equals("faq")));
        return out;
    }
    public void ensureDefaults(ObjectNode kb) {
        if(kb.path("type").asText().isEmpty())kb.put("type","document");
        if(!kb.path("type").asText().equals("faq"))kb.putNull("faq_config");
        if(!kb.path("type").asText().equals("document")){kb.putNull("auto_tag_config");kb.putNull("profile_config");}
        else if(kb.path("auto_tag_config").isObject()) {
            var config=(ObjectNode)kb.get("auto_tag_config");int maximum=config.path("max_tags").asInt();
            config.put("max_tags",maximum<=0?3:Math.min(maximum,10));
            if(config.path("model_id").asText().isEmpty())config.remove("model_id");
            if(!config.hasNonNull("skip_if_tagged"))config.put("skip_if_tagged",true);
        }
        if(kb.path("type").asText().equals("faq")) {
            if(kb.path("faq_config").isNull()) {kb.set("faq_config",json.createObjectNode().put("index_mode","question_answer").put("question_index_mode","combined"));return;}
            var config=(ObjectNode)kb.get("faq_config");if(config.path("index_mode").asText().isEmpty())config.put("index_mode","question_answer");if(config.path("question_index_mode").asText().isEmpty())config.put("question_index_mode","combined");
        }
        if(!hasAnyIndexing(kb.path("indexing_strategy")))kb.set("indexing_strategy",defaultIndexing());
        if(kb.path("extract_config").path("enabled").asBoolean())((ObjectNode)kb.get("indexing_strategy")).put("graph_enabled",true);
    }
    public ObjectNode config(String field,JsonNode input) {return (ObjectNode)decode(input,schema.path("fields").path(field));}
    public static boolean hasAnyIndexing(JsonNode strategy) {for(String field:List.of("vector_enabled","keyword_enabled","wiki_enabled","graph_enabled"))if(strategy.path(field).asBoolean())return true;return false;}
    private ObjectNode defaultIndexing() {return json.createObjectNode().put("vector_enabled",true).put("keyword_enabled",true).put("wiki_enabled",false).put("graph_enabled",false);}
    private JsonNode decode(JsonNode value,JsonNode type) {
        if(value==null||value.isNull())return type.get("zero").deepCopy();
        String kind=type.path("kind").asText();
        switch(kind) {
            case "struct": {
                if(!value.isObject())throw invalid();var out=json.createObjectNode();
                var fields=type.path("fields");fields.fields().forEachRemaining(field -> {
                    JsonNode node=decode(value.get(field.getKey()),field.getValue());
                    boolean pointer=field.getValue().path("pointer").asBoolean();
                    if(!field.getValue().path("omit_empty").asBoolean()||!empty(node,pointer,field.getValue().path("kind").asText()))out.set(field.getKey(),node);
                });return out;
            }
            case "slice", "array": {if(!value.isArray())throw invalid();var out=json.createArrayNode();value.forEach(item -> out.add(decode(item,type.get("items"))));return out;}
            case "string": if(!value.isTextual())throw invalid();break;
            case "bool": if(!value.isBoolean())throw invalid();break;
            case "int", "int64", "uint64", "uint", "int32": if(!value.isIntegralNumber()||(!value.canConvertToLong())||(kind.startsWith("uint")&&value.asLong()<0))throw invalid();break;
            case "timestamp": if(!value.isTextual()&&!value.isNull())throw invalid();break;
            case "map": if(!value.isObject())throw invalid();break;
            default: break;
        }
        return value.deepCopy();
    }
    private static boolean empty(JsonNode value,boolean pointer,String kind) {if(value.isNull())return true;if(pointer||kind.equals("struct")||kind.equals("timestamp"))return false;return value.isTextual()&&value.asText().isEmpty()||value.isBoolean()&&!value.asBoolean()||value.isNumber()&&value.asDouble()==0||value.isContainerNode()&&value.isEmpty();}
    private static ApiException invalid() {return new ApiException(400,"BAD_REQUEST","Invalid request parameters");}
}
