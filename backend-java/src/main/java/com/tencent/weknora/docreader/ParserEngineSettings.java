package com.tencent.weknora.docreader;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.GoStoredStructDecoder;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import java.io.*;import java.nio.file.*;import java.util.*;
/** Locked Go ParserEngineConfig binding and secret-preserving unsaved checks. */
public final class ParserEngineSettings {
    private final ObjectMapper json;private final GoStoredStructDecoder config,credentials;private final JsonNode configSchema;
    public ParserEngineSettings(ObjectMapper json,JsonNode schema){this.json=json;configSchema=schema.path("config");var decoder=goMapper();config=new GoStoredStructDecoder(decoder,configSchema);credentials=new GoStoredStructDecoder(decoder,schema.path("credentials"));}
    static ObjectMapper goMapper(){return new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(10000).maxNumberLength(Integer.MAX_VALUE).maxStringLength(Integer.MAX_VALUE).maxNameLength(Integer.MAX_VALUE).build()).build());}
    public JsonNode config(byte[] bytes){return config.decode(GoUtf8.decode(bytes));}
    public JsonNode credentials(byte[] bytes){return credentials.decode(GoUtf8.decode(bytes));}
    public JsonNode request(byte[] bytes)throws IOException{return GoParserConfigRequest.decode(bytes,configSchema);}
    public JsonNode response(JsonNode value)throws IOException{
        ObjectNode copy=value==null||value.isNull()?json.createObjectNode():(ObjectNode)value.deepCopy();
        for(String key:List.of("mineru_api_key","mineru_server_api_key","paddleocr_vl_cloud_token"))if(!copy.path(key).asText("").isEmpty())copy.put(key,"***");
        return config.project(json.writeValueAsString(copy));
    }
    public byte[] stored(JsonNode value)throws IOException{return json.writeValueAsBytes(config.project(json.writeValueAsString(value)));}
    public JsonNode merge(JsonNode incoming,JsonNode existing){
        ObjectNode out=(incoming==null||incoming.isNull()?json.createObjectNode():(ObjectNode)incoming.deepCopy());
        for(String key:List.of("mineru_api_key","mineru_server_api_key","paddleocr_vl_cloud_token")){
            String value=out.path(key).asText("");if(value.isEmpty()||value.equals("***"))out.put(key,existing==null?"":existing.path(key).asText(""));
        }
        if(existing!=null&&!existing.isNull()&&(!out.has("chat_parser_engine_rules")||out.get("chat_parser_engine_rules").isNull())&&existing.has("chat_parser_engine_rules"))out.set("chat_parser_engine_rules",existing.get("chat_parser_engine_rules"));
        return out;
    }
}
