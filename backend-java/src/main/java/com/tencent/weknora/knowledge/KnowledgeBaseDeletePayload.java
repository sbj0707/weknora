package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;

/** encoding/json KBDeletePayload: preserve typed duplicate/null/case-folding semantics. */
final class KnowledgeBaseDeletePayload {
  private KnowledgeBaseDeletePayload() {}
  static KnowledgeBaseDeleteProcessor.Payload decode(String raw, ObjectMapper json)throws IOException {
    var schema = KnowledgeMoveJson.schema(json, "KBDeletePayload");
    KnowledgeMoveJson.field(json, schema, "tenant_id", "uint", "uint64");
    KnowledgeMoveJson.field(json, schema, "knowledge_base_id", "string", "string");
    KnowledgeMoveJson.add(json, schema, "data_source_ids", slice(json, "[]string", scalar(json, "string", "string")));
    var engine = engineSchema(json);
    KnowledgeMoveJson.add(json, schema, "effective_engines", slice(json, "[]types.RetrieverEngineParams", engine));
    KnowledgeMoveJson.add(json, schema, "vector_store_id", json.createObjectNode().put("kind", "pointer").put("type", "*string").set("elem", scalar(json, "string", "string")));
    for (String name : List.of("lf_trace_id", "lf_parent_obs_id", "lf_traceparent", "lf_user_id", "lf_session_id")) {
      KnowledgeMoveJson.field(json, schema, name, "string", "string");
      ((ObjectNode) schema.withArray("fields").get(schema.withArray("fields").size() - 1)).put("error_path", "TracingContext." + name);
    }
    JsonNode decoded = new GoUploadJson(json).decode(raw, schema);
    var sources = new ArrayList<String>();
    for (JsonNode value : decoded.path("data_source_ids")) sources.add(value.asText(""));
    JsonNode engines=decoded.path("effective_engines");
    if(engines.isArray()) {
      var values=json.createArrayNode();
      for(JsonNode value:engines)values.add(json.createObjectNode()
          .put("retriever_engine_type",value.path("retriever_engine_type").asText(""))
          .put("retriever_type",value.path("retriever_type").asText("")));
      engines=values;
    }
    return new KnowledgeBaseDeleteProcessor.Payload(decoded.path("tenant_id").asLong(), decoded.path("knowledge_base_id").asText(""), sources,
        engines, decoded.hasNonNull("vector_store_id") ? decoded.path("vector_store_id").asText() : null);
  }
  static JsonNode tenantEngines(String raw,ObjectMapper json)throws IOException {
    var s=KnowledgeMoveJson.schema(json,"RetrieverEngines");
    var values=slice(json,"[]types.RetrieverEngineParams",engineSchema(json));
    KnowledgeMoveJson.add(json,s,"engines",values);
    JsonNode engines;
    try {engines=new GoUploadJson(json).decode(raw,s).path("engines");}
    catch(IOException objectError){engines=new GoUploadJson(json).decode(raw,values);}
    var out=json.createArrayNode();
    if(engines.isArray())for(JsonNode value:engines)out.add(json.createObjectNode()
        .put("retriever_engine_type",value.path("retriever_engine_type").asText(""))
        .put("retriever_type",value.path("retriever_type").asText("")));
    return out;
  }
  private static ObjectNode engineSchema(ObjectMapper json) {
    var engine=KnowledgeMoveJson.schema(json,"RetrieverEngineParams");
    KnowledgeMoveJson.field(json,engine,"retriever_engine_type","string","types.RetrieverEngineType");
    KnowledgeMoveJson.field(json,engine,"retriever_type","string","types.RetrieverType");
    return engine;
  }
  private static ObjectNode scalar(ObjectMapper json, String kind, String type) { return json.createObjectNode().put("kind", kind).put("type", type); }
  private static ObjectNode slice(ObjectMapper json, String type, JsonNode element) { return scalar(json, "slice", type).set("elem", element); }
}
