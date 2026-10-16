package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Go validates the whole input, then commits each document's relation replacement separately. */
@Service
@ConditionalOnProperty(name = "weknora.knowledge.processing.enabled", havingValue = "true")
public final class KnowledgeBatchTagsService {
  private final JdbcTemplate jdbc;
  private final KnowledgeBaseWriteAuthorization writes;
  private final TransactionTemplate tx;
  private final KnowledgeTransferState transfer;
  private final boolean postgres;

  public KnowledgeBatchTagsService(
      JdbcTemplate jdbc,
      KnowledgeBaseWriteAuthorization writes,
      PlatformTransactionManager manager,
      ObjectMapper json) {
    this.jdbc = jdbc;
    this.writes = writes;
    tx = new TransactionTemplate(manager);
    transfer = new KnowledgeTransferState(json);
    postgres =
        Boolean.TRUE.equals(
            jdbc.execute(
                (ConnectionCallback<Boolean>)
                    c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  }

  public void update(AuthContext caller, KnowledgeBatchTagsRequest request, String raw) {
    String kb = KnowledgeImportNames.sanitizeForLog(request.kb());
    long owner = caller.tenantId();
    try {
      if (kb.isEmpty()) {
        // Go ranges a map here: there is no first JSON key or sorted-key contract.
        var ids = new ArrayList<>(request.updates().keySet());
        String first = ids.get(ThreadLocalRandom.current().nextInt(ids.size()));
        if (!first.isEmpty()) {
          List<Map<String, Object>> rows;
          try {
            rows =
                jdbc.queryForList(
                    "SELECT * FROM knowledges WHERE id=? AND deleted_at IS NULL", first);
          } catch (org.springframework.dao.DataAccessException failure) {
            throw error(404, "Knowledge not found");
          }
          if (rows.isEmpty()) throw error(404, "Knowledge not found");
          var row = rows.getFirst();
          kb = text(row, "knowledge_base_id");
          KnowledgeBaseWriteAuthorization.WriteAccess grant;
          try {
            grant = writes.resolveBatchTags(caller, kb, raw);
          } catch (ApiException failure) {
            if (failure.status() == 403
                && failure.getMessage().equals("Permission denied to access this knowledge base"))
              throw error(403, "Permission denied to access this knowledge");
            throw failure;
          }
          owner = grant.effectiveTenantId();
          if (number(row, "tenant_id") != owner)
            throw error(403, "Permission denied to access this knowledge");
        }
      } else owner = writes.resolveBatchTags(caller, kb, raw).effectiveTenantId();
      writes.requireBatchTagsOwnership(caller, kb);
      var ids = new ArrayList<>(request.updates().keySet());
      ids.sort(KnowledgeBatchTagsService::compareGoStrings);
      for (String id : ids)
        if (com.tencent.weknora.storage.StoragePaths.trim(id).isEmpty())
          throw error(400, "resource ID cannot be empty");
      var args = new ArrayList<Object>();
      args.add(owner);
      args.addAll(ids);
      var rows =
          jdbc.queryForList(
              "SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("
                  + marks(ids.size())
                  + ") AND deleted_at IS NULL",
              args.toArray());
      var byId = new HashMap<String, Map<String, Object>>();
      for (var row : rows) byId.put(text(row, "id"), row);
      var checked = new HashSet<String>();
      for (String id : ids) {
        var row = byId.get(id);
        if (row == null) throw error(404, "knowledge not found");
        if (transfer.moving(row.get("metadata")))
          throw error(409, "knowledge has an unfinished move; retry the move first");
        String parent = text(row, "knowledge_base_id");
        if (checked.add(parent)) {
          var bases =
              jdbc.queryForList(
                  "SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",
                  parent);
          if (bases.isEmpty()) throw error(404, "knowledge base not found");
          if (number(bases.getFirst(), "tenant_id") != number(row, "tenant_id"))
            throw error(403, "knowledge does not belong to its knowledge base");
          if (!parent.equals(kb)) throw error(403, "无权修改该知识库");
        }
      }
      if (!kb.isEmpty())
        for (String id : ids)
          if (!text(byId.get(id), "knowledge_base_id").equals(kb))
            throw error(403, "knowledge " + id + " does not belong to authorized knowledge base");
      var wanted = new LinkedHashSet<String>();
      request
          .updates()
          .values()
          .forEach(
              tags -> {
                for (String id : tags) if (!id.isEmpty()) wanted.add(id);
              });
      var tags = new HashMap<String, Map<String, Object>>();
      if (!wanted.isEmpty()) {
        args = new ArrayList<>();
        args.add(owner);
        args.addAll(wanted);
        for (var tag :
            jdbc.queryForList(
                "SELECT * FROM knowledge_tags WHERE tenant_id=? AND id IN ("
                    + marks(wanted.size())
                    + ")",
                args.toArray())) tags.put(text(tag, "id"), tag);
      }
      for (String id : ids)
        for (String tagId : request.updates().get(id)) {
          if (tagId.isEmpty()) continue;
          var tag = tags.get(tagId);
          if (tag == null) throw error(400, "标签 " + tagId + " 不存在");
          if (!text(tag, "knowledge_base_id").equals(text(byId.get(id), "knowledge_base_id")))
            throw error(400, "标签 " + tagId + " 不属于知识库 " + text(byId.get(id), "knowledge_base_id"));
        }
      for (String id : ids)
        tx.executeWithoutResult(
            status -> {
              jdbc.update("DELETE FROM knowledge_tag_relations WHERE knowledge_id=?", id);
              var unique = new LinkedHashSet<>(request.updates().get(id));
              unique.remove("");
              if (unique.isEmpty()) return;
              Object now = postgres ? Timestamp.from(Instant.now()) : Instant.now().toString();
              var values = new ArrayList<Object>();
              for (String tag : unique) {
                values.add(id);
                values.add(tag);
                values.add(now);
              }
              jdbc.update(
                  "INSERT INTO knowledge_tag_relations(knowledge_id,tag_id,created_at) VALUES "
                      + String.join(",", Collections.nCopies(unique.size(), "(?,?,?)")),
                  values.toArray());
            });
    } catch (ApiException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw error(500, "Internal server error");
    }
  }

  private static String marks(int size) {
    return String.join(",", Collections.nCopies(size, "?"));
  }

  private static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static int compareGoStrings(String a, String b) {
    return Arrays.compareUnsigned(
        a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static ApiException error(int status, String message) {
    if (status == 500 && message.equals("Internal server error"))
      return new ApiException(500, "INTERNAL_SERVER_ERROR", message)
          .withAppCode(1007)
          .withResponse(
              Map.of("success", false, "error", Map.of("code", 1007, "message", message)));
    return new ApiException(status, "KNOWLEDGE_TAGS", message)
        .withAppCode(
            switch (status) {
              case 400 -> 1000;
              case 401 -> 1001;
              case 403 -> 1002;
              case 404 -> 1003;
              case 409 -> 1005;
              default -> 1007;
            });
  }
}
