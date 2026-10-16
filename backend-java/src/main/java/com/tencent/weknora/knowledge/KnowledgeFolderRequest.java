package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.IOException;
import java.util.*;

/** Gin binding: first JSON value, typed duplicate/null semantics, then required validation. */
record KnowledgeFolderRequest(String from,String to,String kb,List<String> ids) {
  static KnowledgeFolderRequest decode(byte[] bytes,ObjectMapper json,boolean move) {
    String name=move?"MoveKnowledgeToFolderRequest":"RenameKnowledgeFolderRequest";
    var schema=json.createObjectNode().put("kind","struct").put("name",name).put("type","handler."+name);
    var fields=schema.putArray("fields");
    for(String field:move?List.of("kb_id","folder_path"):List.of("from","to"))
      fields.add(json.createObjectNode().put("name",field).set("schema",json.createObjectNode().put("kind","string").put("type","string")));
    if(move)fields.add(json.createObjectNode().put("name","knowledge_ids").set("schema",json.createObjectNode().put("kind","slice").put("type","[]string").set("elem",json.createObjectNode().put("kind","string").put("type","string"))));
    JsonNode value;
    try {int length=GoJsonFirstValue.length(bytes);value=new GoUploadJson(json).decode(GoUtf8.decode(Arrays.copyOf(bytes,length)),schema);}
    catch(IOException error){throw bad("Invalid request parameters: "+error.getMessage());}
    var invalid=new ArrayList<String>();
    if(move){if(value.path("kb_id").asText("").isEmpty())invalid.add(required(name,"KBID"));if(!value.path("knowledge_ids").isArray())invalid.add(required(name,"IDs"));}
    else {if(value.path("from").asText("").isEmpty())invalid.add(required(name,"From"));if(value.path("to").asText("").isEmpty())invalid.add(required(name,"To"));}
    if(!invalid.isEmpty())throw bad("Invalid request parameters: "+String.join("\n",invalid));
    var ids=new LinkedHashSet<String>();
    if(move){value.path("knowledge_ids").forEach(v->{String id=KnowledgeUploadForms.goTrim(v.asText(""));if(!id.isEmpty())ids.add(id);});if(ids.isEmpty())throw bad("knowledge_ids cannot be empty");if(ids.size()>200)throw bad("too many ids (max 200 per batch)");}
    return new KnowledgeFolderRequest(value.path("from").asText(""),value.path(move?"folder_path":"to").asText(""),value.path("kb_id").asText(""),List.copyOf(ids));
  }
  private static String required(String name,String field){return "Key: '"+name+"."+field+"' Error:Field validation for '"+field+"' failed on the 'required' tag";}
  static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000);}
}
