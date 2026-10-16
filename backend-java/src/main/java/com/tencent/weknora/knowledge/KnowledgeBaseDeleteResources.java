package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.retrieval.SQLiteVectorExtension;
import com.tencent.weknora.storage.*;
import com.tencent.weknora.vectorstore.*;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/** Physical KB cleanup uses the captured engine selection and Go's process-wide file service. */
public final class KnowledgeBaseDeleteResources implements KnowledgeBaseDeleteProcessor.Effects {
  @FunctionalInterface public interface Models { int dimensions(long owner, String model)throws Exception; }
  private final KnowledgeBaseDeleteSql sql, indexSql;
  private final Models models;
  private final VectorStoreRegistry stores;
  private final GraphExtractionStore graph;
  private final KnowledgeBaseTaskScrubber tasks;
  private final Clock clock;
  private final boolean postgres;
  private final String provider;
  private final LocalFileStore files;

  public KnowledgeBaseDeleteResources(JdbcTemplate jdbc, ObjectMapper json, Models models,
      VectorStoreRegistry stores, GraphExtractionStore graph, KnowledgeBaseTaskScrubber tasks,
      Environment env, Clock clock) {
    sql = new KnowledgeBaseDeleteSql(jdbc);
    this.models = models; this.stores = stores; this.graph = graph; this.tasks = tasks; this.clock = clock;
    postgres = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
    String path = env.getProperty("weknora.sqlite.vec.extension", env.getProperty("WEKNORA_SQLITE_VEC_EXTENSION", ""));
    // The library is needed only for a selected SQLite engine, not for bound ES/empty engines.
    indexSql = new KnowledgeBaseDeleteSql(jdbc, connection -> {
      if (!postgres && !path.isEmpty()) new SQLiteVectorExtension(Path.of(path)).load(connection);
    });
    String configured = StoragePaths.trim(env.getProperty("STORAGE_TYPE", ""));
    provider = configured.isEmpty() ? "local" : configured;
    String base = env.getProperty("LOCAL_STORAGE_BASE_DIR", "");
    files = new LocalFileStore(Path.of(base.isEmpty() ? "/data/files" : base), "", null, Long.MAX_VALUE);
  }

  @Override public KnowledgeBaseDeleteProcessor.Indexes resolve(KnowledgeBaseDeleteProcessor.Payload p, CallContext call)throws Exception {
    call.check();
    if (p.vectorStoreId() != null && !p.vectorStoreId().isEmpty()) {
      final VectorStoreEngine engine;
      try { engine = stores.resolve(p.tenantId(), p.vectorStoreId(), call); }
      catch (VectorStoreRegistry.Failure error) {
        if (error.reason() == VectorStoreRegistry.Reason.FORBIDDEN || error.reason() == VectorStoreRegistry.Reason.NOT_FOUND)
          throw new KnowledgeBaseDeleteProcessor.Failure(KnowledgeBaseDeleteProcessor.Verdict.SKIP_RETRY, "skip retry for the task");
        throw error;
      }
      return (ids, dimension, type, context) -> engine.delete("knowledge_id", ids, context);
    }
    boolean selected = false;
    String supported = postgres ? "postgres" : "sqlite";
    if (p.effectiveEngines() != null) for (JsonNode entry : p.effectiveEngines()) {
      String engine = entry.path("retriever_engine_type").asText(""), kind = entry.path("retriever_type").asText("");
      if (!engine.equals(supported)) throw new IOException("Native KB cleanup engine is not migrated: " + engine);
      if (!Set.of("vector", "keywords").contains(kind)) throw new IOException("retrieval engine " + engine + " does not support retriever type: " + kind);
      selected = true;
    }
    if (!selected) return (ids, dimension, type, context) -> {};
    return (ids, dimension, type, context) -> deleteIndexes(ids, context);
  }

  private void deleteIndexes(List<String> ids, CallContext call) {
    if (ids.isEmpty()) return;
    String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
    if (postgres) { indexSql.update(call, "DELETE FROM embeddings WHERE knowledge_id IN (" + marks + ")", ids.toArray()); return; }
    List<Map<String,Object>> rows = List.of();
    // Go ignores errors from the pre-read and individual vec/FTS deletes, then returns the
    // metadata delete's error. These independent commits must not become one SQL transaction.
    try { rows = indexSql.query(call, "SELECT id,dimension FROM lite_embeddings WHERE knowledge_id IN (" + marks + ")", ids.toArray()); }
    catch (RuntimeException ignored) { warn("index pre-read", ignored); }
    var groups = new LinkedHashMap<Long,List<Object>>();
    for (var row : rows) {
      long dimension = ((Number) row.get("dimension")).longValue();
      if (dimension > 0) groups.computeIfAbsent(dimension, unused -> new ArrayList<>()).add(row.get("id"));
    }
    for (var group : groups.entrySet()) for (var id : group.getValue()) {
      try { indexSql.update(call, "DELETE FROM vec_embeddings_" + group.getKey() + " WHERE rowid=?", id); }
      catch (RuntimeException error) { warn("vector index", error); }
    }
    for (var row : rows) try { indexSql.update(call, "DELETE FROM lite_embeddings_fts WHERE rowid=?", row.get("id")); }
    catch (RuntimeException error) { warn("keyword index", error); }
    indexSql.update(call, "DELETE FROM lite_embeddings WHERE knowledge_id IN (" + marks + ")", ids.toArray());
  }

  @Override public int dimensions(KnowledgeBaseDeleteProcessor.Payload p, String model, CallContext call)throws Exception {
    call.check(); int dimension = models.dimensions(p.tenantId(), model); call.check(); return dimension;
  }

  @Override public void file(KnowledgeBaseDeleteProcessor.Payload p, String reference, CallContext call)throws Exception {
    call.check();
    boolean resource = StoragePaths.resourceHandle(reference) != null;
    String physical = resource ? Objects.toString(resource(p.tenantId(), reference, call).get("physical_path"), "") : reference;
    if (provider.equals("local")) files.delete(physical);
    else if (!provider.equals("dummy")) throw new IOException("Native KB cleanup storage provider is not migrated: " + provider);
    call.check();
    if (resource) {
      var stored = resource(p.tenantId(), reference, call);
      sql.update(call, "UPDATE resources SET state='deleted',deleted_at=? WHERE id=? AND tenant_id=?", time(), stored.get("id"), p.tenantId());
    }
  }

  @Override public boolean releaseImage(KnowledgeBaseDeleteProcessor.Payload p, String reference, List<String> ids, CallContext call) {
    long remaining = -1;
    for (String id : ids) {
      call.check();
      if (StoragePaths.resourceHandle(reference) == null) continue;
      var stored = resource(p.tenantId(), reference, call);
      sql.update(call, "DELETE FROM resource_bindings WHERE resource_id=? AND owner_type=? AND owner_id=?", stored.get("id"), "knowledge", id);
      remaining = ((Number) sql.query(call, "SELECT COUNT(*) AS remaining FROM resource_bindings WHERE resource_id=?", stored.get("id")).getFirst().get("remaining")).longValue();
    }
    return remaining <= 0;
  }

  private Map<String,Object> resource(long tenant, String reference, CallContext call) {
    var rows = sql.query(call, "SELECT id,physical_path FROM resources WHERE tenant_id=? AND handle=? AND state='active' AND deleted_at IS NULL", tenant, StoragePaths.resourceHandle(reference));
    if (rows.isEmpty()) throw new NoSuchElementException("resource not found");
    return rows.getFirst();
  }

  @Override public void graph(KnowledgeBaseDeleteProcessor.Payload p, List<String> ids, CallContext call) {
    for (String id : ids) { call.check(); graph.delete(p.knowledgeBaseId(), id, call, call::check); }
  }

  @Override public void scrub(KnowledgeBaseDeleteProcessor.Payload p, List<String> ids, CallContext call) {
    tasks.scrub(p.knowledgeBaseId(), ids, p.dataSourceIds(), call);
  }

  private Object time() { return postgres ? Timestamp.from(clock.instant()) : GoSqliteTimestamp.write(clock.instant()); }
  private static void warn(String stage, Exception error) { org.slf4j.LoggerFactory.getLogger(KnowledgeBaseDeleteResources.class).warn("Knowledge base {} cleanup failed ({})", stage, error.getClass().getSimpleName()); }
}
