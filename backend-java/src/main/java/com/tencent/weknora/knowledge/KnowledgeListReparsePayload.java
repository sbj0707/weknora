package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.List;

/** Decode original persisted bytes with Go's typed, duplicate/null and case-folding behavior. */
final class KnowledgeListReparsePayload {
  private KnowledgeListReparsePayload() {}
  static ObjectNode decode(String raw, ObjectMapper json) throws IOException {
    var schema=structure(json,"KnowledgeListReparsePayload");
    field(json,schema,"knowledge_base_id",scalar(json,"string","string"));
    for(String name:List.of("lf_trace_id","lf_parent_obs_id","lf_traceparent","lf_user_id","lf_session_id"))
      schema.withArray("fields").add(json.createObjectNode().put("name",name).put("error_path","TracingContext."+name).set("schema",scalar(json,"string","string")));
    field(json,schema,"tenant_id",scalar(json,"uint","uint64").put("bits",64));
    field(json,schema,"knowledge_ids",json.createObjectNode().put("kind","slice").put("type","[]string").set("elem",scalar(json,"string","string")));
    var initiator=structure(json,"TaskInitiator");
    for(String name:List.of("user_id","api_key_name"))field(json,initiator,name,scalar(json,"string","string"));
    field(json,initiator,"role",scalar(json,"string","types.TenantRole"));
    field(json,initiator,"api_key_id",scalar(json,"uint","uint64").put("bits",64));
    field(json,schema,"initiator",initiator);
    try(var in=KnowledgeListReparsePayload.class.getResourceAsStream("/knowledge-upload-schema.json")){
      field(json,schema,"process_config",json.createObjectNode().put("kind","pointer").put("type","*types.KnowledgeProcessOverrides").set("elem",json.readTree(in)));
    }
    var decoded=new GoUploadJson(json).decode(raw,schema);
    return decoded.isObject()?(ObjectNode)decoded:json.createObjectNode();
  }
  private static ObjectNode structure(ObjectMapper json,String name){var node=json.createObjectNode().put("kind","struct").put("name",name).put("type","types."+name);node.putArray("fields");return node;}
  private static ObjectNode scalar(ObjectMapper json,String kind,String type){return json.createObjectNode().put("kind",kind).put("type",type);}
  private static void field(ObjectMapper json,ObjectNode parent,String name,JsonNode schema){parent.withArray("fields").add(json.createObjectNode().put("name",name).set("schema",schema));}
}
