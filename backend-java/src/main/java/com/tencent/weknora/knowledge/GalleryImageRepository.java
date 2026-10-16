package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL gallery projection: judge the winning copy, then page in the database. */
@Repository
public class GalleryImageRepository {
  private static final String WINNER =
      "NOT EXISTS (SELECT 1 FROM chunk_images o WHERE o.knowledge_base_id=i.knowledge_base_id AND"
          + " o.tenant_id=i.tenant_id AND o.image_key=i.image_key AND (o.updated_at>i.updated_at OR"
          + " (o.updated_at=i.updated_at AND (o.chunk_id<i.chunk_id OR (o.chunk_id=i.chunk_id AND"
          + " o.image_index<i.image_index)))))";
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public GalleryImageRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Map<String, Object> list(
      long owner,
      String kb,
      KnowledgeContentReadRepository.Page page,
      HttpServletRequest request,
      JsonNode config,
      GalleryConfigService policies) {
    try {
      return query(owner, kb, page, request, config, policies);
    } catch (RuntimeException ex) {
      throw new ApiException(500, "INTERNAL_SERVER_ERROR", ChunkEditService.diagnostic(ex))
          .withAppCode(1007);
    }
  }

  private Map<String, Object> query(
      long owner,
      String kb,
      KnowledgeContentReadRepository.Page page,
      HttpServletRequest request,
      JsonNode config,
      GalleryConfigService policies) {
    boolean pg =
        Boolean.TRUE.equals(
            jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                    c -> c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
    Map<String, Set<String>> eligible = new HashMap<>();
    for (String usage : List.of("in_filter", "in_searchfield", "in_sortfield"))
      eligible.put(usage, new HashSet<>());
    for (var attr : config.path("attributes"))
      for (var e : eligible.entrySet())
        if (attr.path("usage").path(e.getKey()).asBoolean())
          e.getValue().add(attr.path("id").asText());
    var clauses = new ArrayList<String>(List.of("i.knowledge_base_id=?", "i.tenant_id=?"));
    var args = new ArrayList<Object>(List.of(kb, owner));
    String enabled = param(request, "is_enabled", "");
    if (!enabled.isEmpty()) {
      clauses.add("i.is_enabled=?");
      args.add(enabled.equals("true"));
    }
    String keyword = trim(param(request, "keyword", ""));
    if (!keyword.isEmpty()) {
      var fields = new ArrayList<String>();
      for (String id : param(request, "search_in", "").split(",", -1))
        if (eligible.get("in_searchfield").contains(trim(id))) fields.add(trim(id));
      if (fields.isEmpty()) fields.addAll(List.of("builtin:caption", "builtin:ocr_text"));
      var matches = new ArrayList<String>();
      for (String id : fields) {
        String expr = value(id, pg);
        if (expr == null) continue;
        matches.add(expr + (pg ? " ILIKE ?" : " LIKE ? ESCAPE '\\'"));
        args.add("%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
      }
      clauses.add("(" + (matches.isEmpty() ? "1=0" : String.join(" OR ", matches)) + ")");
    }
    var filters = policies.filters(param(request, "attr_filters", ""));
    for (String id : keys(filters)) {
      var values = filters.path(id);
      if (!eligible.get("in_filter").contains(id) || !values.isArray() || values.isEmpty())
        continue;
      String expr = value(id, pg);
      if (expr == null) continue;
      clauses.add("(" + expr + " IS NULL OR " + expr + " IN (" + marks(values.size()) + "))");
      for (var v : values) args.add(trim(v.asText()));
    }
    var rules = policies.rules(param(request, "attr_rules", ""));
    var on = new ArrayList<String>();
    var off = new ArrayList<String>();
    var onArgs = new ArrayList<Object>();
    var offArgs = new ArrayList<Object>();
    for (String id : keys(rules)) {
      if (!eligible.get("in_filter").contains(id)) continue;
      String expr = value(id, pg);
      if (expr == null) continue;
      for (String verdict : List.of("on", "off")) {
        var values = new ArrayList<String>();
        for (String key : keys(rules.path(id)))
          if (rules.path(id).path(key).asText().equals(verdict)) values.add(key);
        if (values.isEmpty()) continue;
        (verdict.equals("on") ? on : off)
            .add("(" + expr + " IS NOT NULL AND " + expr + " IN (" + marks(values.size()) + "))");
        (verdict.equals("on") ? onArgs : offArgs).addAll(values);
      }
    }
    if (!off.isEmpty()) {
      clauses.add(
          "(("
              + (on.isEmpty() ? "1=0" : String.join(" OR ", on))
              + ") OR NOT ("
              + String.join(" OR ", off)
              + "))");
      args.addAll(onArgs);
      args.addAll(offArgs);
    }
    boolean filtered = clauses.size() > 2;
    clauses.add(WINNER);
    String where = String.join(" AND ", clauses);
    String count =
        filtered
            ? "SELECT COUNT(*) FROM chunk_images i WHERE " + where
            : "SELECT COUNT(*) FROM (SELECT DISTINCT i.image_key FROM chunk_images i WHERE"
                + " i.knowledge_base_id=? AND i.tenant_id=?) d";
    List<Object> countArgs = filtered ? args : new ArrayList<>(List.of(kb, owner));
    String sort = trim(param(request, "sort_by", ""));
    if (!eligible.get("in_sortfield").contains(sort)) sort = "builtin:created_at";
    String sortKey;
    if (Set.of("builtin:created_at", "builtin:updated_at", "builtin:is_enabled").contains(sort))
      sortKey = "i." + sort.substring(8);
    else {
      String expr = value(sort, pg);
      sortKey = expr == null ? "i.created_at" : "LOWER(COALESCE(" + expr + ",''))";
    }
    String dir = param(request, "sort_order", "desc").equals("asc") ? " ASC" : " DESC";
    // Go int64 arithmetic wraps on overflow; the repository then clamps a negative offset to zero.
    long offset = Math.max(0, (page.page() - 1) * page.size());
    var pageArgs = new ArrayList<Object>(countArgs);
    pageArgs.addAll(args);
    pageArgs.add(page.size());
    pageArgs.add(offset);
    var rows =
        jdbc.queryForList(
            "SELECT"
                + " i.chunk_id,i.knowledge_id,i.chunk_type,i.is_enabled,i.status,i.created_at,i.updated_at,i.image_index,i.url,i.original_url,i.caption,i.ocr_text,"
                + (pg ? "i.attrs::text" : "i.attrs")
                + " AS attrs_json,("
                + count
                + ") AS total_count FROM chunk_images i WHERE "
                + where
                + " ORDER BY "
                + sortKey
                + dir
                + ",i.chunk_id"
                + dir
                + ",i.image_index"
                + dir
                + " LIMIT ? OFFSET ?",
            pageArgs.toArray());
    long total =
        rows.isEmpty()
            ? (offset == 0
                ? 0
                : Objects.requireNonNull(
                    jdbc.queryForObject(count, Long.class, countArgs.toArray())))
            : ((Number) rows.getFirst().get("total_count")).longValue();
    var names = new HashMap<String, String>();
    var ids = new LinkedHashSet<String>();
    for (var row : rows) {
      String id = text(row, "knowledge_id");
      if (!id.isEmpty()) ids.add(id);
    }
    if (!ids.isEmpty())
      try {
        var nameArgs = new ArrayList<Object>();
        nameArgs.add(owner);
        nameArgs.addAll(ids);
        for (var row :
            jdbc.queryForList(
                "SELECT id,title FROM knowledges WHERE tenant_id=? AND id IN ("
                    + marks(ids.size())
                    + ") AND deleted_at IS NULL",
                nameArgs.toArray())) names.put(text(row, "id"), text(row, "title"));
      } catch (RuntimeException ignored) {
      }
    var data = new ArrayList<ObjectNode>();
    for (var row : rows) {
      var asset = json.createObjectNode();
      String chunk = text(row, "chunk_id");
      asset.put("id", chunk + "#" + ((Number) row.get("image_index")).intValue());
      for (String key :
          List.of(
              "chunk_id",
              "knowledge_id",
              "chunk_type",
              "url",
              "original_url",
              "caption",
              "ocr_text")) asset.put(key, text(row, key));
      asset.put("source_name", names.getOrDefault(text(row, "knowledge_id"), ""));
      JsonNode attrs = json.createObjectNode();
      try {
        var parsed = json.readTree(text(row, "attrs_json"));
        if (parsed != null && parsed.isObject()) attrs = parsed;
      } catch (Exception ignored) {
      }
      asset.set("attrs", attrs);
      Object e = row.get("is_enabled");
      asset.put(
          "is_enabled",
          e instanceof Boolean b ? b.booleanValue() : e instanceof Number n && n.intValue() != 0);
      asset.put("status", row.get("status") instanceof Number n ? n.intValue() : 0);
      for (String key : List.of("created_at", "updated_at"))
        asset.put(
            key,
            row.get(key) == null
                ? "0001-01-01T00:00:00Z"
                : KnowledgeBaseRepository.timestamp(row.get(key)));
      data.add(asset);
    }
    return Map.of(
        "success",
        true,
        "data",
        data,
        "total",
        total,
        "page",
        page.page(),
        "page_size",
        page.size());
  }

  private static String value(String id, boolean pg) {
    int colon = id.indexOf(':');
    if (colon < 1 || colon == id.length() - 1) return null;
    String source = id.substring(0, colon), name = id.substring(colon + 1);
    if (source.equals("builtin"))
      return switch (name) {
        case "caption", "ocr_text" -> "i." + name;
        case "is_enabled" -> "(CASE WHEN i.is_enabled THEN 'true' ELSE 'false' END)";
        default -> null;
      };
    if (name.indexOf('\'') >= 0 || name.indexOf('"') >= 0 || name.indexOf('\\') >= 0) return null;
    if (pg) return "(i.attrs->>'" + name + "')";
    String path = "'$.\"" + name + "\"'";
    return "(CASE json_type(i.attrs,"
        + path
        + ") WHEN 'true' THEN 'true' WHEN 'false' THEN 'false' ELSE CAST(json_extract(i.attrs,"
        + path
        + ") AS TEXT) END)";
  }

  private static List<String> keys(JsonNode node) {
    var out = new ArrayList<String>();
    node.fieldNames().forEachRemaining(out::add);
    out.sort(
        (a, b) ->
            Arrays.compareUnsigned(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return out;
  }

  private static String marks(int n) {
    return String.join(",", Collections.nCopies(n, "?"));
  }

  private static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  private static String trim(String s) {
    return AuthPreferences.trimGoSpace(s);
  }

  private static String param(HttpServletRequest r, String key, String fallback) {
    return Objects.toString(r.getParameter(key), fallback);
  }
}
