package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.util.*;

/** The public Go binders accept the first JSON value and preserve typed duplicate/null semantics. */
record KnowledgeReparseRequest(String knowledgeBase, List<String> ids, JsonNode processConfig) {
  static KnowledgeReparseRequest decode(byte[] bytes, ObjectMapper json, boolean batch) {
    try {
      ObjectNode schema=json.createObjectNode().put("kind","struct")
          .put("name",batch?"batchReparseKnowledgeRequest":"")
          .put("type",batch?"handler.batchReparseKnowledgeRequest":"struct { ProcessConfig *types.KnowledgeProcessOverrides \"json:\\\"process_config\\\"\" }");
      var fields=schema.putArray("fields");
      if(batch){
        fields.add(json.createObjectNode().put("name","kb_id").set("schema",json.createObjectNode().put("kind","string").put("type","string")));
        fields.add(json.createObjectNode().put("name","ids").set("schema",json.createObjectNode().put("kind","slice").put("type","[]string").set("elem",json.createObjectNode().put("kind","string").put("type","string"))));
      }
      try(var in=KnowledgeReparseRequest.class.getResourceAsStream("/knowledge-upload-schema.json")){
        fields.add(json.createObjectNode().put("name","process_config").set("schema",json.createObjectNode().put("kind","pointer").put("type","*types.KnowledgeProcessOverrides").set("elem",json.readTree(in))));
      }
      int length=GoJsonFirstValue.length(bytes);
      var value=new GoUploadJson(json).decode(GoUtf8.decode(Arrays.copyOf(bytes,length)),schema);
      String kb=value.path("kb_id").asText("");var ids=new LinkedHashSet<String>();
      if(batch){
        if(kb.isEmpty()||!value.path("ids").isArray())throw invalid();
        for(var id:value.path("ids")){String clean=KnowledgeUploadForms.goTrim(id.asText(""));if(!clean.isEmpty())ids.add(clean);}
        if(ids.isEmpty())throw bad("no knowledge IDs provided for batch reparse");
        if(ids.size()>200)throw bad("too many ids (max 200 per batch)");
      }
      return new KnowledgeReparseRequest(kb,List.copyOf(ids),value.get("process_config"));
    }catch(IOException failure){
      if(batch)throw invalid();
      throw bad("Invalid reparse request body").withDetails(failure.getMessage());
    }
  }
  private static ApiException invalid(){return bad("invalid batch reparse knowledge request parameters");}
  static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000);}
}
