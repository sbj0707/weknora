package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;

/** Same resource-pair checks at admission and again before move execution. */
final class KnowledgeMoveCompatibility {
  private KnowledgeMoveCompatibility() {}

  static void validate(JsonNode source, JsonNode target, String mode, JsonNode tenant) {
    validate(source,target,mode,tenant,false);
  }

  static void validateClone(JsonNode source,JsonNode target,JsonNode tenant) {
    validate(source,target,"",tenant,true);
  }

  private static void validate(JsonNode source,JsonNode target,String mode,JsonNode tenant,boolean clone) {
    if (source == null || target == null || source.isNull() || target.isNull())
      fail("kb_access: not found");
    if (text(source, "id").equals(text(target, "id")))
      fail("source and target knowledge bases must differ");
    if (!text(source, "type").equals(text(target, "type")))
      fail("source and target knowledge bases must have the same type");
    if (!text(source, "embedding_model_id").equals(text(target, "embedding_model_id")))
      fail("source and target knowledge bases use different embedding models");
    if (!clone && !mode.equals("reuse_vectors") && !mode.equals("reparse"))
      fail("unknown move mode: " + mode);
    if ((clone || mode.equals("reuse_vectors"))
        && !text(source, "vector_store_id").equals(text(target, "vector_store_id")))
      fail(
          "source and target knowledge bases use different vector stores; use reparse mode for"
              + " moves");
    String defaultId = tenant == null ? "" : text(tenant, "default_storage_backend_id");
    String left = storageId(source, defaultId), right = storageId(target, defaultId);
    boolean same;
    if (!left.isEmpty() || !right.isEmpty()) same = !left.isEmpty() && left.equals(right);
    else {
      String fallback =
          tenant == null ? "" : text(tenant.path("storage_engine_config"), "default_provider");
      same = provider(source, fallback).equals(provider(target, fallback));
    }
    if (!same) fail("source and target knowledge bases use different storage instances");
  }

  private static String storageId(JsonNode kb, String fallback) {
    String explicit = KnowledgeUploadForms.goTrim(text(kb, "storage_backend_id"));
    return explicit.isEmpty() ? KnowledgeUploadForms.goTrim(fallback) : explicit;
  }

  private static String provider(JsonNode kb, String fallback) {
    String selected = lowerTrim(text(kb.path("storage_provider_config"), "provider"));
    if (!selected.isEmpty() && !selected.equals("__pending_env__")) return selected;
    var legacy = kb.has("cos_config") ? kb.path("cos_config") : kb.path("storage_config");
    selected = lowerTrim(text(legacy, "provider"));
    return selected.isEmpty() ? lowerTrim(fallback) : selected;
  }

  // Go strings.ToLower does not expand characters into multiple code points.
  private static String lowerTrim(String value) {
    var result = new StringBuilder();
    KnowledgeUploadForms.goTrim(value)
        .codePoints()
        .forEach(cp -> result.appendCodePoint(Character.toLowerCase(cp)));
    return result.toString();
  }

  private static String text(JsonNode value, String name) {
    return value.path(name).asText("");
  }

  private static void fail(String message) {
    throw new IllegalArgumentException(message);
  }
}
