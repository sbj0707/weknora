package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.IOException;
import java.util.*;

/** Gin binds one outer value; the service unmarshals the entire inner JSON string. */
final class KnowledgeImageRequest {
  static String decode(byte[] bytes, ObjectMapper json) {
    var schema = structure(json, "", "struct { ImageInfo string \"json:\\\"image_info\\\"\" }");
    field(json, schema, "image_info", primitive(json, "string", "string"));
    try {
      var value =
          new GoUploadJson(json)
              .decode(GoUtf8.decode(Arrays.copyOf(bytes, GoJsonFirstValue.length(bytes))), schema);
      return KnowledgeImportNames.sanitizeForLog(value.path("image_info").asText(""));
    } catch (IOException failure) {
      throw new ApiException(400, "BAD_REQUEST", failure.getMessage()).withAppCode(1000);
    }
  }

  static JsonNode images(String raw, ObjectMapper json) throws IOException {
    var image = structure(json, "ImageInfo", "types.ImageInfo");
    for (String key : List.of("sha256", "url", "original_url", "caption", "ocr_text"))
      field(json, image, key, primitive(json, "string", "string"));
    for (String key : List.of("start_pos", "end_pos"))
      field(json, image, key, primitive(json, "int", "int"));
    var attrs = structure(json, "ImageAttrs", "types.ImageAttrs");
    field(json, attrs, "schema", primitive(json, "string", "string"));
    var source = structure(json, "ImageAttrSource", "types.ImageAttrSource");
    for (String key : List.of("schema", "prompt"))
      field(json, source, key, primitive(json, "string", "string"));
    field(json, source, "custom", primitive(json, "bool", "bool"));
    field(json, attrs, "source", source);
    field(
        json,
        attrs,
        "attrs",
        json.createObjectNode()
            .put("kind", "map")
            .put("type", "map[string]interface {}")
            .set("elem", primitive(json, "interface", "interface {}")));
    field(json, image, "attrs", attrs);
    return new GoUploadJson(json)
        .decode(
            raw,
            json.createObjectNode()
                .put("kind", "slice")
                .put("type", "[]*types.ImageInfo")
                .set(
                    "elem",
                    json.createObjectNode()
                        .put("kind", "pointer")
                        .put("type", "*types.ImageInfo")
                        .set("elem", image)));
  }

  private static ObjectNode structure(ObjectMapper json, String name, String type) {
    var node = json.createObjectNode().put("kind", "struct").put("name", name).put("type", type);
    node.putArray("fields");
    return node;
  }

  private static JsonNode primitive(ObjectMapper json, String kind, String type) {
    return json.createObjectNode().put("kind", kind).put("type", type);
  }

  private static void field(ObjectMapper json, ObjectNode schema, String name, JsonNode type) {
    schema.withArray("fields").add(json.createObjectNode().put("name", name).set("schema", type));
  }
}
