package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import java.io.*;
import java.util.*;

/** encoding/json map reuse, string-slice binding and Gin required/min validation. */
record KnowledgeBatchTagsRequest(String kb, Map<String, List<String>> updates) {
  private static final ObjectMapper BINDING_JSON =
      new ObjectMapper(
          JsonFactory.builder()
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(10000)
                      .maxNumberLength(Integer.MAX_VALUE)
                      .maxStringLength(Integer.MAX_VALUE)
                      .maxNameLength(Integer.MAX_VALUE)
                      .build())
              .build());

  static KnowledgeBatchTagsRequest decode(byte[] bytes, ObjectMapper json) {
    Map<String, List<String>> updates = null;
    String kb = "", error = null;
    try {
      int end = GoJsonFirstValue.length(bytes);
      try (var p = BINDING_JSON.createParser(GoUtf8.decode(Arrays.copyOf(bytes, end)))) {
        var token = p.nextToken();
        if (token != JsonToken.VALUE_NULL) {
          if (token != JsonToken.START_OBJECT)
            throw new IOException(wrong(p, "Go value of type handler.knowledgeTagBatchRequest"));
          String field;
          while ((field = p.nextFieldName()) != null) {
            token = p.nextToken();
            StringBuilder fold = new StringBuilder();
            field
                .codePoints()
                .forEach(cp -> fold.appendCodePoint(cp == 0x17f ? 's' : Character.toLowerCase(cp)));
            String folded = fold.toString();
            if (folded.equals("kb_id")) {
              if (token == JsonToken.VALUE_STRING) kb = GoUploadJson.unicode(p.getText());
              else if (token != JsonToken.VALUE_NULL) {
                if (error == null)
                  error = wrong(p, "Go struct field knowledgeTagBatchRequest.kb_id of type string");
                p.skipChildren();
              }
            } else if (folded.equals("updates")) {
              if (token == JsonToken.VALUE_NULL) {
                updates = null;
                continue;
              }
              if (token != JsonToken.START_OBJECT) {
                if (error == null)
                  error =
                      wrong(
                          p,
                          "Go struct field knowledgeTagBatchRequest.updates of type"
                              + " map[string][]string");
                p.skipChildren();
                continue;
              }
              if (updates == null) updates = new LinkedHashMap<>();
              String id;
              while ((id = p.nextFieldName()) != null) {
                token = p.nextToken();
                var tags = new ArrayList<String>();
                if (token == JsonToken.START_ARRAY) {
                  while ((token = p.nextToken()) != JsonToken.END_ARRAY) {
                    if (token == JsonToken.VALUE_STRING)
                      tags.add(GoUploadJson.unicode(p.getText()));
                    else if (token == JsonToken.VALUE_NULL) tags.add("");
                    else {
                      if (error == null)
                        error =
                            wrong(
                                p,
                                "Go struct field knowledgeTagBatchRequest.updates of type string");
                      p.skipChildren();
                      tags.add("");
                    }
                  }
                } else if (token != JsonToken.VALUE_NULL) {
                  if (error == null)
                    error =
                        wrong(
                            p, "Go struct field knowledgeTagBatchRequest.updates of type []string");
                  p.skipChildren();
                }
                updates.put(GoUploadJson.unicode(id), tags);
              }
            } else p.skipChildren();
          }
        }
      }
      if (error != null) throw new IOException(error);
    } catch (IOException failure) {
      throw KnowledgeTagRequestDecoder.bad("请求参数不合法", failure.getMessage());
    }
    if (updates == null || updates.isEmpty())
      throw KnowledgeTagRequestDecoder.bad(
          "请求参数不合法",
          "Key: 'knowledgeTagBatchRequest.Updates' Error:Field validation for 'Updates' failed on"
              + " the '"
              + (updates == null ? "required" : "min")
              + "' tag");
    return new KnowledgeBatchTagsRequest(kb, updates);
  }

  private static String wrong(JsonParser p, String destination) {
    String kind =
        switch (p.currentToken()) {
          case START_OBJECT -> "object";
          case START_ARRAY -> "array";
          case VALUE_STRING -> "string";
          case VALUE_TRUE, VALUE_FALSE -> "bool";
          default -> "number";
        };
    return "json: cannot unmarshal " + kind + " into " + destination;
  }
}
