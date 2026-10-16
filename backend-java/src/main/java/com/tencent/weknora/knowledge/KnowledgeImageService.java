package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.security.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Synchronous Go image update: child insertion, individual saves, indexes, best-effort hash. */
@Service
@ConditionalOnProperty(name = "weknora.knowledge.processing.enabled", havingValue = "true")
public final class KnowledgeImageService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeBaseCodec codec;
  private final KnowledgeTransferState transfer;
  private final ChunkEditService.Embeddings embeddings;
  private final DocumentProcessingHandler.Indexes indexes;
  private final TransactionTemplate tx;
  private final boolean pg;

  @org.springframework.beans.factory.annotation.Autowired
  public KnowledgeImageService(
      JdbcTemplate jdbc,
      ObjectMapper json,
      KnowledgeBaseWriteAuthorization writes,
      ModelClientFactory models,
      DocumentProcessingHandler.Indexes indexes,
      PlatformTransactionManager manager)
      throws java.io.IOException {
    this(
        jdbc,
        json,
        writes,
        (owner, id) ->
            models.embeddingForCleanup(
                new AuthContext(
                    "system-image", owner, "viewer", false, null, false, Set.of(), Set.of()),
                id),
        indexes,
        manager);
  }

  KnowledgeImageService(
      JdbcTemplate jdbc,
      ObjectMapper json,
      KnowledgeBaseWriteAuthorization writes,
      ChunkEditService.Embeddings embeddings,
      DocumentProcessingHandler.Indexes indexes,
      PlatformTransactionManager manager)
      throws java.io.IOException {
    this.jdbc = jdbc;
    this.json = json;
    this.writes = writes;
    this.embeddings = embeddings;
    this.indexes = indexes;
    codec = new KnowledgeBaseCodec(json);
    transfer = new KnowledgeTransferState(json);
    tx = new TransactionTemplate(manager);
    pg =
        jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL"));
  }

  public KnowledgeBaseWriteAuthorization.WriteAccess authorize(
      AuthContext caller, String id, String raw) {
    ChunkLifecycleService.capability(caller, "ingest");
    List<Map<String, Object>> rows;
    try {
      rows = jdbc.queryForList("SELECT * FROM knowledges WHERE id=? AND deleted_at IS NULL", id);
    } catch (org.springframework.dao.DataAccessException failure) {
      throw api(503, 1008, "cannot verify KB access right now");
    }
    if (rows.isEmpty()) throw api(404, 1003, "Knowledge not found");
    var grant =
        writes.authorizeImageUpdate(caller, text(rows.getFirst(), "knowledge_base_id"), raw);
    if (number(rows.getFirst(), "tenant_id") != grant.effectiveTenantId())
      throw api(403, 1002, "Permission denied to access this knowledge");
    return grant;
  }

  public void update(
      KnowledgeBaseWriteAuthorization.WriteAccess grant, String document, String id, String raw) {
    try {
      long owner = grant.effectiveTenantId();
      var doc = document(owner, document);
      if (transfer.moving(doc.get("metadata")))
        throw api(409, 1005, "knowledge has an unfinished move; retry the move first");
      var storedBase = base(text(doc, "knowledge_base_id"));
      if (number(storedBase, "tenant_id") != owner
          || !text(doc, "knowledge_base_id").equals(grant.knowledgeBaseId()))
        throw api(403, 1002, "knowledge does not belong to its knowledge base");
      var images = KnowledgeImageRequest.images(raw, json);
      if (images.size() != 1) return;
      JsonNode image = images.get(0);
      var parents =
          jdbc.queryForList(
              "SELECT * FROM chunks WHERE tenant_id=? AND id=? AND deleted_at IS NULL", owner, id);
      if (parents.isEmpty()) throw new IllegalStateException("chunk not found");
      var parent = new LinkedHashMap<>(parents.getFirst());
      if (!text(parent, "knowledge_id").equals(document)
          || !text(parent, "knowledge_base_id").equals(text(doc, "knowledge_base_id")))
        throw api(403, 1002, "chunk does not belong to its knowledge document");
      parent.put("image_info", raw);
      var children =
          jdbc.queryForList(
              "SELECT * FROM chunks WHERE tenant_id=? AND parent_chunk_id=? AND deleted_at IS NULL",
              owner,
              id);
      for (var child : children)
        if (number(child, "tenant_id") != owner
            || !text(child, "knowledge_id").equals(document)
            || !text(child, "knowledge_base_id").equals(text(parent, "knowledge_base_id")))
          throw api(403, 1002, "image child does not belong to its document");
      var updates = new ArrayList<Map<String, Object>>();
      updates.add(parent);
      var additions = new ArrayList<Map<String, Object>>();
      boolean caption = false, ocr = false;
      for (var child : children) {
        JsonNode old;
        try {
          old = KnowledgeImageRequest.images(text(child, "image_info"), json);
        } catch (Exception malformed) {
          continue;
        }
        if (old.size() == 0) continue;
        JsonNode first = old.get(0);
        requireImage(first);
        requireImage(image);
        if (!first.path("original_url").asText("").equals(image.path("original_url").asText("")))
          continue;
        String field =
            switch (text(child, "chunk_type")) {
              case "image_caption" -> {
                caption = true;
                yield "caption";
              }
              case "image_ocr" -> {
                ocr = true;
                yield "ocr_text";
              }
              default -> null;
            };
        if (field != null && !first.path(field).asText("").equals(image.path(field).asText(""))) {
          child.put("content", image.path(field).asText(""));
          child.put("image_info", raw);
          updates.add(child);
        }
      }
      requireImage(image);
      if (!caption && !image.path("caption").asText("").isEmpty())
        additions.add(child(parent, "image_caption", image.path("caption").asText(), raw));
      if (!ocr && !image.path("ocr_text").asText("").isEmpty())
        additions.add(child(parent, "image_ocr", image.path("ocr_text").asText(), raw));
      // Only CreateChunks is a transaction. Later failures retain these newly inserted children.
      if (!additions.isEmpty())
        tx.executeWithoutResult(
            status -> {
              long seq =
                  pg
                      ? 0
                      : jdbc.queryForObject(
                          "SELECT COALESCE(MAX(seq_id),0) FROM chunks", Long.class);
              for (var child : additions) {
                if (!pg) child.put("seq_id", ++seq);
                String columns =
                    "id,tenant_id,knowledge_id,knowledge_base_id,tag_id,content,source_content,content_revision,index_status,last_editor_id,chunk_index,is_enabled,flags,status,start_at,end_at,pre_chunk_id,next_chunk_id,chunk_type,parent_chunk_id,context_header,content_hash,image_info,created_at,updated_at";
                var values =
                    new ArrayList<Object>(
                        Arrays.asList(
                            child.get("id"),
                            owner,
                            document,
                            child.get("knowledge_base_id"),
                            "",
                            child.get("content"),
                            "",
                            0,
                            "ready",
                            "",
                            0,
                            true,
                            1,
                            0,
                            0,
                            0,
                            "",
                            "",
                            child.get("chunk_type"),
                            id,
                            "",
                            "",
                            raw,
                            child.get("created_at"),
                            child.get("updated_at")));
                if (!pg) {
                  columns += ",seq_id";
                  values.add(child.get("seq_id"));
                }
                jdbc.update(
                    "INSERT INTO chunks("
                        + columns
                        + ") VALUES("
                        + String.join(",", Collections.nCopies(values.size(), "?"))
                        + ")",
                    values.toArray());
              }
            });
      for (var row : updates) {
        row.put("updated_at", now());
        jdbc.update(
            "UPDATE chunks SET content=?,image_info=?,updated_at=? WHERE id=? AND deleted_at IS"
                + " NULL",
            text(row, "content"),
            raw,
            row.get("updated_at"),
            text(row, "id"));
      }
      var changed = new ArrayList<>(updates);
      changed.addAll(additions);
      reindex(owner, storedBase, changed);
      doc = document(owner, document);
      String hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("MD5")
                      .digest(
                          (document + text(doc, "file_hash") + raw)
                              .getBytes(StandardCharsets.UTF_8)));
      try {
        jdbc.update(
            "UPDATE knowledges SET file_hash=?,updated_at=? WHERE tenant_id=? AND id=? AND"
                + " deleted_at IS NULL",
            hash,
            now(),
            owner,
            document);
      } catch (RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Image saved but document hash update failed for {}", document, failure);
      }
    } catch (ApiException typed) {
      throw typed;
    } catch (Exception failure) {
      throw api(500, 1007, ChunkEditService.diagnostic(failure));
    }
  }

  private void reindex(long owner, Map<String, Object> storedBase, List<Map<String, Object>> chunks)
      throws Exception {
    var config = new LinkedHashMap<>(storedBase);
    config.remove("indexing_strategy");
    var kb = codec.fromRow(config);
    // Match the SQL Scanner: SQLite TEXT uses the legacy defaults; PG JSONB is decoded.
    if (pg && storedBase.get("indexing_strategy") != null) {
      var strategy = json.readTree(storedBase.get("indexing_strategy").toString());
      if (KnowledgeBaseCodec.hasAnyIndexing(strategy)) kb.set("indexing_strategy", strategy);
    }
    var strategy = kb.path("indexing_strategy");
    if (!strategy.path("vector_enabled").asBoolean()
        && !strategy.path("keyword_enabled").asBoolean()) return;
    EmbeddingModel model;
    try {
      model = embeddings.resolve(owner, kb.path("embedding_model_id").asText());
    } catch (Exception failure) {
      throw new IllegalStateException(ChunkEditService.modelDiagnostic(failure));
    }
    var entries = new ArrayList<IndexEntry>();
    var ids = new ArrayList<String>();
    var docs = new HashMap<String, Map<String, Object>>();
    for (var chunk : chunks) {
      if (!text(chunk, "knowledge_base_id").equals(kb.path("id").asText())) continue;
      ids.add(text(chunk, "id"));
      if (!enabled(chunk) || text(chunk, "chunk_type").equals("parent_text")) continue;
      var doc = docs.computeIfAbsent(text(chunk, "knowledge_id"), key -> document(owner, key));
      String content = KnowledgeUploadForms.goTrim(text(chunk, "content"));
      if (!text(chunk, "context_header").isEmpty())
        content = text(chunk, "context_header") + "\n\n" + content;
      entries.add(entry(chunk, text(chunk, "id"), doc, content));
      for (JsonNode q : questionMetadata(chunk).path("generated_questions"))
        if (!KnowledgeUploadForms.goTrim(q.path("question").asText()).isEmpty())
          entries.add(
              entry(
                  chunk,
                  QuestionGenerationText.sourceId(text(chunk, "id"), q.path("id").asText()),
                  doc,
                  q.path("question").asText()));
    }
    // CompositeRetrieveEngine deduplicates SourceID with the first occurrence winning.
    var unique = new LinkedHashMap<String, IndexEntry>();
    for (var entry : entries) unique.putIfAbsent(entry.sourceId(), entry);
    entries = new ArrayList<>(unique.values());
    var writer = indexes.resolve(owner, kb);
    writer.prepare(Set.of(model.dimensions()));
    indexTransaction(c -> writer.deleteByChunk(c, ids));
    if (entries.isEmpty()) return;
    float[][] vectors = null;
    try (var call = new CallContext()) {
      for (int attempt = 0; attempt < 5; attempt++)
        try {
          vectors =
              model.embed(
                  call,
                  entries.stream().map(e -> IndexText.embeddingInput(e.content())).toList(),
                  false);
          break;
        } catch (RuntimeException failure) {
          if (attempt == 4) throw failure;
          call.pause(Duration.ofMillis(200L << attempt));
        }
    }
    if (vectors == null || vectors.length != entries.size())
      throw new IllegalStateException("embedding count mismatch");
    var mapped = new LinkedHashMap<String, float[]>();
    for (int i = 0; i < entries.size(); i++) {
      FloatVectors.validate(vectors[i], model.dimensions());
      mapped.put(entries.get(i).sourceId(), vectors[i]);
    }
    var batch = entries;
    indexTransaction(c -> writer.batchSave(c, batch, mapped));
  }

  private JsonNode questionMetadata(Map<String, Object> chunk) throws Exception {
    var schema =
        json.createObjectNode()
            .put("kind", "struct")
            .put("name", "DocumentChunkMetadata")
            .put("type", "types.DocumentChunkMetadata");
    var question =
        json.createObjectNode()
            .put("kind", "struct")
            .put("name", "GeneratedQuestion")
            .put("type", "types.GeneratedQuestion");
    var fields = question.putArray("fields");
    for (String key : List.of("id", "question"))
      fields.add(
          json.createObjectNode()
              .put("name", key)
              .set("schema", json.createObjectNode().put("kind", "string").put("type", "string")));
    fields.add(
        json.createObjectNode()
            .put("name", "content_revision")
            .set(
                "schema",
                json.createObjectNode()
                    .put("kind", "pointer")
                    .put("type", "*int")
                    .set("elem", json.createObjectNode().put("kind", "int").put("type", "int"))));
    schema
        .putArray("fields")
        .add(
            json.createObjectNode()
                .put("name", "generated_questions")
                .set(
                    "schema",
                    json.createObjectNode()
                        .put("kind", "slice")
                        .put("type", "[]types.GeneratedQuestion")
                        .set("elem", question)));
    schema
        .withArray("fields")
        .add(
            json.createObjectNode()
                .put("name", "generated_questions_revision")
                .set("schema", json.createObjectNode().put("kind", "int").put("type", "int")));
    Object stored = chunk.get("metadata");
    String raw =
        stored instanceof byte[] bytes
            ? new String(bytes, StandardCharsets.UTF_8)
            : Objects.toString(stored, "");
    return raw.isEmpty() ? json.createObjectNode() : new GoUploadJson(json).decode(raw, schema);
  }

  private IndexEntry entry(
      Map<String, Object> chunk, String source, Map<String, Object> doc, String content) {
    String title = KnowledgeUploadForms.goTrim(text(doc, "title"));
    return new IndexEntry(
        source,
        0,
        text(chunk, "id"),
        text(chunk, "knowledge_id"),
        text(chunk, "knowledge_base_id"),
        "",
        title.isEmpty() ? content : title + "\n" + content,
        true);
  }

  private Map<String, Object> child(
      Map<String, Object> parent, String type, String content, String raw) {
    var row = new LinkedHashMap<String, Object>();
    for (String key : List.of("tenant_id", "knowledge_id", "knowledge_base_id"))
      row.put(key, parent.get(key));
    row.put("id", UUID.randomUUID().toString());
    row.put("parent_chunk_id", parent.get("id"));
    row.put("chunk_type", type);
    row.put("content", IndexText.clean(content));
    row.put("image_info", raw);
    row.put("is_enabled", true);
    row.put("created_at", now());
    row.put("updated_at", row.get("created_at"));
    return row;
  }

  private Map<String, Object> document(long owner, String id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM knowledges WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
            owner,
            id);
    if (rows.isEmpty()) throw new IllegalStateException("knowledge not found");
    return rows.getFirst();
  }

  private Map<String, Object> base(String id) {
    var rows =
        jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL", id);
    if (rows.isEmpty()) throw api(404, 1003, "knowledge base not found");
    return rows.getFirst();
  }

  @FunctionalInterface
  private interface IndexMutation {
    void run(java.sql.Connection connection) throws Exception;
  }

  private void indexTransaction(IndexMutation mutation) {
    tx.executeWithoutResult(
        status ->
            jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Void>)
                    c -> {
                      try {
                        mutation.run(c);
                        return null;
                      } catch (java.sql.SQLException failure) {
                        throw failure;
                      } catch (RuntimeException failure) {
                        throw failure;
                      } catch (Exception failure) {
                        throw new java.sql.SQLException(failure);
                      }
                    }));
  }

  private void requireImage(JsonNode image) {
    if (image == null || image.isNull())
      throw new ApiException(
              500,
              "INTERNAL_SERVER_ERROR",
              "runtime error: invalid memory address or nil pointer dereference")
          .withResponse(
              Map.of(
                  "error",
                  "Internal Server Error",
                  "message",
                  "runtime error: invalid memory address or nil pointer dereference"));
  }

  private Object now() {
    return pg ? Timestamp.from(Instant.now()) : GoSqliteTimestamp.write(Instant.now());
  }

  private static long number(Map<String, Object> row, String key) {
    return row.get(key) instanceof Number n ? n.longValue() : 0;
  }

  private static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  private static boolean enabled(Map<String, Object> row) {
    return row.get("is_enabled") instanceof Boolean b
        ? b
        : Objects.toString(row.get("is_enabled"), "").matches("(?i)true|1");
  }

  private static ApiException api(int status, int code, String message) {
    return new ApiException(
            status,
            status == 403
                ? "FORBIDDEN"
                : status == 404
                    ? "NOT_FOUND"
                    : status == 409 ? "CONFLICT" : "INTERNAL_SERVER_ERROR",
            message)
        .withAppCode(code);
  }
}
