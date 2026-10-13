package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import java.util.*;

/** Typed Go ModelParameters JSON. Credentials only cross the DB boundary through GoSecretCodec. */
final class ModelParametersCodec {
    private final ObjectMapper json; private final GoSecretCodec secrets;
    ModelParametersCodec(ObjectMapper json,GoSecretCodec secrets){this.json=json;this.secrets=secrets;}
    ObjectNode request(JsonNode input){
        if(input==null||input.isNull())input=json.createObjectNode();if(!input.isObject())throw bad("parameters must be an object");
        ObjectNode out=json.createObjectNode();
        for(String key:List.of("base_url","api_key","interface_type","parameter_size","provider"))out.put(key,string(input,key));
        for(String key:List.of("app_id","app_secret")){String value=string(input,key);if(!value.isEmpty())out.put(key,value);}
        JsonNode embedding=input.get("embedding_parameters");if(embedding!=null&&!embedding.isNull()&&!embedding.isObject())throw bad("embedding_parameters must be an object");
        ObjectNode ep=json.createObjectNode();for(String key:List.of("dimension","truncate_prompt_tokens"))ep.put(key,number(embedding,key));ep.put("supports_dimension_override",bool(embedding,"supports_dimension_override"));out.set("embedding_parameters",ep);
        out.put("supports_vision",bool(input,"supports_vision"));
        for(String key:List.of("context_window","max_output_tokens","max_concurrency")){long value=number(input,key);if(value!=0)out.put(key,value);}
        ObjectNode extra=map(input.get("extra_config"),false);out.set("extra_config",extra==null?NullNode.instance:extra);
        ObjectNode headers=map(input.get("custom_headers"),false);if(headers!=null&&!headers.isEmpty())out.set("custom_headers",headers);
        JsonNode spec=input.get("spec");if(spec!=null&&!spec.isNull()){
            if(!spec.isObject())throw bad("spec must be an object");ObjectNode s=json.createObjectNode();String api=string(spec,"api");if(!api.isEmpty())s.put("api",api);
            if(spec.hasNonNull("reasoning"))s.put("reasoning",bool(spec,"reasoning"));
            for(String key:List.of("context_window","max_output_tokens")){long value=number(spec,key);if(value!=0)s.put(key,value);}
            if(spec.hasNonNull("input")){if(!spec.get("input").isArray())throw bad("spec.input must be an array");ArrayNode arr=json.createArrayNode();for(JsonNode value:spec.get("input")){if(!value.isTextual()&&!value.isNull())throw bad("spec.input values must be strings");arr.add(value.isNull()?"":value.asText());}if(!arr.isEmpty())s.set("input",arr);}
            ObjectNode levels=map(spec.get("thinking_levels"),true);if(levels!=null&&!levels.isEmpty())s.set("thinking_levels",levels);
            if(spec.hasNonNull("compat")){if(!spec.get("compat").isObject())throw bad("spec.compat must be an object");if(!spec.get("compat").isEmpty())s.set("compat",spec.get("compat").deepCopy());}
            out.set("spec",s);
        }return out;
    }
    ObjectNode stored(Object value){try{ObjectNode out=request(json.readTree(value==null?"{}":value.toString()));for(String key:List.of("api_key","app_secret"))if(out.has(key))out.put(key,secrets.decryptLenient(out.path(key).asText()));return out;}catch(java.io.IOException ex){throw new ApiException(500,"INTERNAL_SERVER_ERROR","Invalid persisted model parameters");}}
    String encoded(ObjectNode params){ObjectNode encrypted=params.deepCopy();for(String key:List.of("api_key","app_secret"))if(encrypted.has(key))encrypted.put(key,secrets.encryptLegacyWhenConfigured(encrypted.path(key).asText()));try{return json.writeValueAsString(encrypted);}catch(java.io.IOException ex){throw new IllegalStateException(ex);}}
    private ObjectNode map(JsonNode value,boolean pointer){if(value==null||value.isNull())return null;if(!value.isObject())throw bad("parameter map must be an object");ObjectNode out=json.createObjectNode();value.fields().forEachRemaining(e->{if(!e.getValue().isTextual()&&!e.getValue().isNull())throw bad("parameter map values must be strings");if(pointer&&e.getValue().isNull())out.putNull(e.getKey());else out.put(e.getKey(),e.getValue().isNull()?"":e.getValue().asText());});return out;}
    static String string(JsonNode node,String key){JsonNode value=node==null?null:node.get(key);if(value==null||value.isNull())return "";if(!value.isTextual())throw bad(key+" must be a string");return value.asText();}
    private static long number(JsonNode node,String key){JsonNode value=node==null?null:node.get(key);if(value==null||value.isNull())return 0;if(!value.isIntegralNumber()||!value.canConvertToLong())throw bad(key+" must be an integer");return value.asLong();}
    private static boolean bool(JsonNode node,String key){JsonNode value=node==null?null:node.get(key);if(value==null||value.isNull())return false;if(!value.isBoolean())throw bad(key+" must be a boolean");return value.asBoolean();}
    static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message);}
}
