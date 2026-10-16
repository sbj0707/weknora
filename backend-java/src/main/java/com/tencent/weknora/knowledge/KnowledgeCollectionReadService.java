package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public final class KnowledgeCollectionReadService {
  private record Scope(long owner, String kb) {}

  private final JdbcTemplate jdbc;
  private final KnowledgeContentReadRepository projection;
  private final KnowledgeBaseReadAuthorization reads;
  private final SharedAgentCollectionScope agents;
  private final KnowledgeBaseCodec codec;

  public KnowledgeCollectionReadService(
      JdbcTemplate jdbc,
      KnowledgeContentReadRepository projection,
      KnowledgeBaseReadAuthorization reads,
      SharedAgentCollectionScope agents,
      KnowledgeBaseCodec codec) {
    this.jdbc = jdbc;
    this.projection = projection;
    this.reads = reads;
    this.agents = agents;
    this.codec = codec;
  }

  public Map<String, Object> batch(
      AuthContext caller, List<String> ids, String kb, String agent, String selector) {
    if (caller.tenantId() == 0)
      throw new ApiException(401, "UNAUTHORIZED", "Unauthorized").withAppCode(1001);
    var scope = agent.isEmpty() ? null : agents.resolve(caller, agent, selector);
    if (scope != null && scope.empty()) return Map.of("success", true, "data", List.of());
    long owner = scope == null ? caller.tenantId() : scope.tenant();
    if (!kb.isEmpty()) {
      var grant = reads.authorizeRead(caller, kb, agent, selector);
      owner = grant.effectiveTenantId();
      if (scope != null && !scope.allows(owner, kb))
        throw new ApiException(403, "FORBIDDEN", "Knowledge base not accessible through this agent")
            .withAppCode(1002);
    }
    List<ObjectNode> data = new ArrayList<>();
    try {
      List<Map<String, Object>> rows = batchRows(owner, ids);
      Set<String> found = new HashSet<>();
      Map<String, Boolean> permissions = new HashMap<>();
      for (var row : rows) {
        String id = text(row, "id");
        found.add(id);
        if (scope != null || !kb.isEmpty() || allowed(caller, row, permissions))
          data.add(projection.projectKnowledge(row));
      }
      if (scope == null && kb.isEmpty())
        for (String id : ids)
          if (found.add(id)) {
            var extra =
                jdbc.queryForList(
                    "SELECT * FROM knowledges WHERE id=? AND deleted_at IS NULL ORDER BY id LIMIT"
                        + " 1",
                    id);
            if (!extra.isEmpty() && allowed(caller, extra.getFirst(), permissions))
              data.add(projection.projectKnowledge(extra.getFirst()));
          }
    } catch (RuntimeException ex) {
      throw failure("Failed to retrieve knowledge list", ex);
    }
    final String selectedKB = kb;
    final var selectedAgent = scope;
    data.removeIf(
        row ->
            !selectedKB.isEmpty() && !row.path("knowledge_base_id").asText().equals(selectedKB)
                || selectedAgent != null
                    && !selectedAgent.allows(
                        row.path("tenant_id").asLong(), row.path("knowledge_base_id").asText())
                || !caller.allowsKnowledgeBase(row.path("knowledge_base_id").asText()));
    attachActivity(data);
    return Map.of("success", true, "data", data);
  }

  public Map<String, Object> search(
      AuthContext caller,
      String keyword,
      long offset,
      int limit,
      List<String> fileTypes,
      String agent,
      String selector) {
    List<Scope> scopes = new ArrayList<>();
    if (!agent.isEmpty()) {
      var scope = agents.resolve(caller, agent, selector);
      if (scope.empty()) return emptySearch(false);
      if (scope.all()) {
        List<Map<String, Object>> bases;
        try {
          bases =
              jdbc.queryForList(
                  "SELECT * FROM knowledge_bases WHERE tenant_id=? AND deleted_at IS NULL",
                  scope.tenant());
        } catch (RuntimeException ex) {
          throw failure("Failed to list knowledge bases", ex);
        }
        for (var row : bases) {
          ObjectNode base = agentBase(row);
          if (base.path("type").asText().equals("document")
              && capable(codec.render(base).path("capabilities"), scope.config()))
            scopes.add(new Scope(scope.tenant(), text(row, "id")));
        }
      } else for (String kb : scope.ids()) scopes.add(new Scope(scope.tenant(), kb));
      scopes.removeIf(scopeRow -> !caller.allowsKnowledgeBase(scopeRow.kb()));
      if (scopes.isEmpty()) return emptySearch(false);
    } else if (caller.isApiKey() && !caller.knowledgeBaseIds().isEmpty()) {
      for (String kb : caller.knowledgeBaseIds()) scopes.add(new Scope(caller.tenantId(), kb));
    } else {
      try {
        for (var row :
            jdbc.queryForList(
                "SELECT id,type FROM knowledge_bases WHERE tenant_id=? AND deleted_at IS NULL",
                caller.tenantId()))
          if (text(row, "type").equals("document"))
            scopes.add(new Scope(caller.tenantId(), text(row, "id")));
      } catch (RuntimeException ignored) {
      }
      if (caller.userId() != null && !caller.userId().isEmpty())
        try {
          for (var row :
              jdbc.queryForList(
                  "SELECT DISTINCT b.id,b.tenant_id,b.type FROM kb_shares s JOIN organizations o ON"
                      + " o.id=s.organization_id AND o.deleted_at IS NULL JOIN"
                      + " organization_tenant_members src ON src.organization_id=s.organization_id"
                      + " AND src.tenant_id=s.source_tenant_id JOIN organization_tenant_members dst"
                      + " ON dst.organization_id=s.organization_id AND dst.tenant_id=? JOIN"
                      + " knowledge_bases b ON b.id=s.knowledge_base_id AND b.deleted_at IS NULL"
                      + " WHERE s.deleted_at IS NULL",
                  caller.tenantId()))
            if (text(row, "type").equals("document"))
              scopes.add(new Scope(number(row, "tenant_id"), text(row, "id")));
        } catch (RuntimeException ignored) {
        }
      if (scopes.isEmpty()) return emptySearch(true);
    }
    try {
      var args = new ArrayList<Object>();
      var pairs = new ArrayList<String>();
      for (var scope : scopes) {
        pairs.add("(?,?)");
        args.add(scope.owner());
        args.add(scope.kb());
      }
      StringBuilder where =
          new StringBuilder(
              "(k.tenant_id,k.knowledge_base_id) IN ("
                  + String.join(",", pairs)
                  + ") AND b.type='document' AND k.deleted_at IS NULL");
      if (!keyword.isEmpty()) {
        where.append(
            " AND (LOWER(k.file_name) LIKE ? ESCAPE '\\' OR LOWER(k.title) LIKE ? ESCAPE '\\')");
        String pattern = "%" + lower(escape(keyword)) + "%";
        args.add(pattern);
        args.add(pattern);
      }
      Set<String> patterns = new LinkedHashSet<>();
      boolean url = false;
      for (String type : fileTypes) {
        if (type.startsWith(".")) type = type.substring(1);
        type = lower(type);
        if (Set.of("html", "url").contains(type)) {
          url = true;
          continue;
        }
        patterns.add("%." + type);
        switch (type) {
          case "xls" -> patterns.add("%.xlsx");
          case "xlsx" -> patterns.add("%.xls");
          case "doc" -> patterns.add("%.docx");
          case "docx" -> patterns.add("%.doc");
          case "jpg", "jpeg", "png" -> {
            patterns.add("%.jpg");
            patterns.add("%.jpeg");
            patterns.add("%.png");
          }
        }
      }
      if (!patterns.isEmpty() || url) {
        var filters = new ArrayList<String>();
        for (String pattern : patterns) {
          filters.add("LOWER(k.file_name) LIKE ?");
          args.add(pattern);
        }
        if (url) filters.add("k.type='url'");
        where.append(" AND (").append(String.join(" OR ", filters)).append(")");
      }
      String from =
          " FROM knowledges k JOIN knowledge_bases b ON b.id=k.knowledge_base_id AND"
              + " b.tenant_id=k.tenant_id WHERE "
              + where;
      long total = jdbc.queryForObject("SELECT COUNT(*)" + from, Long.class, args.toArray());
      args.add(limit + 1);
      args.add(offset);
      var rows =
          jdbc.queryForList(
              "SELECT k.*,b.name AS knowledge_base_name"
                  + from
                  + " ORDER BY k.created_at DESC LIMIT ? OFFSET ?",
              args.toArray());
      boolean more = rows.size() > limit;
      var data = new ArrayList<ObjectNode>();
      for (int i = 0; i < Math.min(limit, rows.size()); i++)
        data.add(projection.projectKnowledge(rows.get(i)));
      return Map.of("success", true, "data", data, "has_more", more, "total", total);
    } catch (RuntimeException ex) {
      throw failure("Failed to search knowledge", ex);
    }
  }

  private List<Map<String, Object>> batchRows(long owner, List<String> ids) {
    var args = new ArrayList<Object>();
    args.add(owner);
    args.addAll(ids);
    return jdbc.queryForList(
        "SELECT * FROM knowledges WHERE tenant_id=? AND id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ") AND deleted_at IS NULL",
        args.toArray());
  }

  private boolean allowed(AuthContext caller, Map<String, Object> row, Map<String, Boolean> cache) {
    long owner = number(row, "tenant_id");
    if (owner == caller.tenantId()) return true;
    if (caller.userId() == null || caller.userId().isEmpty()) return false;
    String kb = text(row, "knowledge_base_id");
    return cache.computeIfAbsent(
        kb,
        key -> {
          try {
            return jdbc
                .queryForList(
                    "SELECT s.permission,dst.role FROM kb_shares s JOIN organizations o ON"
                        + " o.id=s.organization_id AND o.deleted_at IS NULL JOIN"
                        + " organization_tenant_members src ON"
                        + " src.organization_id=s.organization_id AND"
                        + " src.tenant_id=s.source_tenant_id JOIN organization_tenant_members dst"
                        + " ON dst.organization_id=s.organization_id AND dst.tenant_id=? WHERE"
                        + " s.knowledge_base_id=? AND s.deleted_at IS NULL",
                    caller.tenantId(),
                    key)
                .stream()
                .anyMatch(share -> validShare(text(share, "permission"), text(share, "role")));
          } catch (RuntimeException ex) {
            return false;
          }
        });
  }

  private static boolean validShare(String permission, String role) {
    Set<String> valid = Set.of("admin", "editor", "viewer");
    return permission.isEmpty()
        ? valid.contains(role)
        : role.isEmpty()
            ? valid.contains(permission)
            : valid.contains(permission) && valid.contains(role);
  }

  private void attachActivity(List<ObjectNode> data) {
    List<ObjectNode> active =
        data.stream()
            .filter(
                row ->
                    Set.of("pending", "processing", "finalizing")
                        .contains(row.path("parse_status").asText()))
            .toList();
    if (active.isEmpty()) return;
    Map<String, Instant> spans = new HashMap<>();
    try {
      List<String> ids = active.stream().map(row -> row.path("id").asText()).toList();
      for (var row :
          jdbc.queryForList(
              "SELECT knowledge_id,MAX(updated_at) AS last_seen FROM knowledge_processing_spans"
                  + " WHERE knowledge_id IN ("
                  + String.join(",", Collections.nCopies(ids.size(), "?"))
                  + ") GROUP BY knowledge_id",
              ids.toArray())) {
        Object value = row.get("last_seen");
        String raw = Objects.toString(value, "");
        if (raw.length() > 10)
          spans.put(
              text(row, "knowledge_id"),
              Instant.parse(KnowledgeProcessingSpanRepository.timestamp(value)));
      }
    } catch (RuntimeException ignored) {
    }
    for (var row : active) {
      Instant updated = Instant.parse(row.path("updated_at").asText()),
          span = spans.get(row.path("id").asText());
      row.put(
          "last_activity_at", (span != null && span.isAfter(updated) ? span : updated).toString());
    }
    // The existing native tracing implementation has no Go Redis/Wiki stall probe.
  }

  private ObjectNode agentBase(Map<String, Object> row) {
    // Go SQL scanners accept []byte, while SQLite TEXT values are strings.
    var input = new HashMap<>(row);
    for (String field : List.of("indexing_strategy", "extract_config"))
      if (input.get(field) instanceof String) input.put(field, null);
    return codec.fromRow(input);
  }

  private static String lower(String text) {
    var out = new StringBuilder();
    text.codePoints().forEach(cp -> out.appendCodePoint(Character.toLowerCase(cp)));
    return out.toString();
  }

  private static boolean capable(JsonNode caps, JsonNode config) {
    Set<String> required = new HashSet<>(), aux = new HashSet<>();
    if (config.path("agent_mode").asText().equals("quick-answer")) {
      required.add("vector");
      required.add("keyword");
    }
    Set<String> primary = new HashSet<>();
    for (var node : config.path("allowed_tools")) {
      String tool = node.asText();
      if (Set.of(
              "search_knowledge",
              "knowledge_search",
              "database_query",
              "grep_chunks",
              "list_knowledge_chunks",
              "get_document_info",
              "data_analysis",
              "data_schema")
          .contains(tool)) {
        primary.add("vector");
        primary.add("keyword");
      } else if (tool.equals("query_knowledge_graph")) primary.add("graph");
      else if (Set.of("read_document", "list_documents", "wiki_read_source_doc").contains(tool)) {
        aux.add("vector");
        aux.add("keyword");
        aux.add("wiki");
      } else if (Set.of(
              "wiki_search",
              "wiki_read_page",
              "wiki_flag_issue",
              "wiki_write_page",
              "wiki_replace_text",
              "wiki_rename_page",
              "wiki_delete_page",
              "wiki_read_issue",
              "wiki_update_issue")
          .contains(tool)) primary.add("wiki");
    }
    required.addAll(primary.isEmpty() ? aux : primary);
    return required.isEmpty() || required.stream().anyMatch(cap -> caps.path(cap).asBoolean());
  }

  private static Map<String, Object> emptySearch(boolean nil) {
    var out = new LinkedHashMap<String, Object>();
    out.put("success", true);
    out.put("data", nil ? null : List.of());
    out.put("has_more", false);
    out.put("total", 0);
    return out;
  }

  private static long number(Map<String, Object> row, String key) {
    return row.get(key) instanceof Number n ? n.longValue() : 0;
  }

  private static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static ApiException failure(String message, RuntimeException ex) {
    String details = ChunkEditService.diagnostic(ex);
    return new ApiException(500, "INTERNAL_SERVER_ERROR", message)
        .withAppCode(1007)
        .withDetails(details);
  }
}
