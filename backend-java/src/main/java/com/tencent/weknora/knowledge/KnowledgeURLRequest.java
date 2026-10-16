package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import java.io.*;
import java.util.*;

/** The anonymous Go URL request struct, including typed duplicate/null and first-value binding. */
public record KnowledgeURLRequest(String url, String filename, String fileType,
    Boolean multimodel, String title, List<String> tags, String channel, JsonNode overrides) {
  static KnowledgeURLRequest decode(byte[] bytes, ObjectMapper json) {
    try (var input=KnowledgeURLRequest.class.getResourceAsStream("/knowledge-url-request-schema.json")) {
      var schema=json.readTree(input);
      var value=new GoUploadJson(json).decode(
          GoUtf8.decode(Arrays.copyOf(bytes,GoJsonFirstValue.length(bytes))),schema);
      String url=value.path("url").asText("");
      if(url.isEmpty())throw ManualKnowledgeRequest.bad("Key: 'URL' Error:Field validation for 'URL' failed on the 'required' tag");
      var tags=new ArrayList<String>();value.path("tag_ids").forEach(v->tags.add(v.asText("")));
      var multi=value.path("enable_multimodel");
      return new KnowledgeURLRequest(url,value.path("file_name").asText(""),value.path("file_type").asText(""),
          multi.isMissingNode()||multi.isNull()?null:multi.asBoolean(),value.path("title").asText(""),
          List.copyOf(tags),value.path("channel").asText(""),value.get("process_config"));
    } catch(IOException error) {throw ManualKnowledgeRequest.bad(error.getMessage());}
  }
}
