package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.*;
import com.tencent.weknora.storage.StoragePaths;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public final class KnowledgeContentReadController {
  private final KnowledgeContentReadRepository repository;
  private final KnowledgeBaseReadAuthorization reads;
  private final KnowledgeContentReadAuthorization contentReads;

  public KnowledgeContentReadController(
      KnowledgeContentReadRepository repository,
      KnowledgeBaseReadAuthorization reads,
      KnowledgeContentReadAuthorization contentReads) {
    this.repository = repository;
    this.reads = reads;
    this.contentReads = contentReads;
  }

  @GetMapping("/knowledge-bases/{id}/knowledge")
  public Map<String, Object> list(HttpServletRequest request, @PathVariable String id) {
    var grant = authorize(request, id);
    var page = pagination(request, false);
    return execute(
        () ->
            repository.knowledges(
                grant.effectiveTenantId(),
                KnowledgeImportNames.sanitizeForLog(id),
                page,
                filter(request)));
  }

  @GetMapping("/knowledge/{id:(?!batch$|search$).+}")
  public Map<String, Object> knowledge(HttpServletRequest request, @PathVariable String id) {
    var auth = caller(request);
    var access =
        contentReads.authorizeKnowledge(
            auth, id, param(request, "agent_id", ""), param(request, "agent_source_tenant_id", ""));
    var grant = access.parent();
    String clean = KnowledgeImportNames.sanitizeForLog(id);
    var current = globalKnowledge(clean);
    if (current.path("tenant_id").asLong() != grant.effectiveTenantId())
      throw new ApiException(403, "FORBIDDEN", "Permission denied to access this knowledge")
          .withAppCode(1002);
    try {
      return Map.of(
          "success", true, "data", repository.knowledge(grant.effectiveTenantId(), clean));
    } catch (RuntimeException ex) {
      throw new ApiException(404, "NOT_FOUND", "Knowledge not found").withAppCode(1003);
    }
  }

  @GetMapping("/chunks/{knowledge_id}")
  public Map<String, Object> chunks(
      HttpServletRequest request, @PathVariable(name = "knowledge_id") String id) {
    var auth = caller(request);
    var grant =
        contentReads
            .authorizeKnowledge(
                auth,
                id,
                param(request, "agent_id", ""),
                param(request, "agent_source_tenant_id", ""))
            .parent();
    var page = pagination(request, true);
    String[] query = request.getParameterValues("chunk_type");
    List<String> types = query == null || query.length == 0 ? List.of("text") : List.of(query);
    return execute(
        () ->
            repository.chunks(
                grant.effectiveTenantId(), KnowledgeImportNames.sanitizeForLog(id), page, types));
  }

  @GetMapping("/chunks/by-id/{id}")
  public Map<String, Object> chunk(HttpServletRequest request, @PathVariable String id) {
    var auth = caller(request);
    contentReads.authorizeChunk(
        auth, id, param(request, "agent_id", ""), param(request, "agent_source_tenant_id", ""));
    try {
      return Map.of(
          "success", true, "data", repository.globalChunk(KnowledgeImportNames.sanitizeForLog(id)));
    } catch (ApiException ex) {
      throw ex;
    }
  }

  private static Map<String, Object> execute(
      java.util.function.Supplier<Map<String, Object>> action) {
    try {
      return action.get();
    } catch (ApiException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      Throwable cause = ex;
      while (cause.getCause() != null) cause = cause.getCause();
      String message = Objects.toString(cause.getMessage(), "Internal server error");
      if (message.startsWith("[SQLITE_") && message.endsWith(")")) {
        int open = message.indexOf(" (");
        if (open >= 0) message = message.substring(open + 2, message.length() - 1);
      }
      throw new ApiException(500, "INTERNAL_SERVER_ERROR", message).withAppCode(1007);
    }
  }

  private AuthContext caller(HttpServletRequest request) {
    var auth = CurrentAuth.get(request);
    auth.requireRole("viewer");
    KnowledgeContentReadAuthorization.requireRetrieve(auth);
    return auth;
  }

  private KnowledgeBaseReadAuthorization.ReadAccess authorize(
      HttpServletRequest request, String kb) {
    return read(request, caller(request), kb);
  }

  private KnowledgeBaseReadAuthorization.ReadAccess read(
      HttpServletRequest request, AuthContext auth, String kb) {
    return reads.authorizeRead(
        auth, kb, param(request, "agent_id", ""), param(request, "agent_source_tenant_id", ""));
  }

  private com.fasterxml.jackson.databind.node.ObjectNode globalKnowledge(String id) {
    return contentReads.lookupKnowledge(id);
  }

  private static KnowledgeContentReadRepository.Page pagination(
      HttpServletRequest request, boolean chunk) {
    long page = number(request, "page"), size = number(request, "page_size");
    List<String> errors = new ArrayList<>();
    if (page < 0) errors.add(validation("Page", "min"));
    if (size < 0) errors.add(validation("PageSize", "min"));
    else if (size > 1000) errors.add(validation("PageSize", "max"));
    if (!errors.isEmpty()) throw bad(String.join("\n", errors));
    return new KnowledgeContentReadRepository.Page(
        page < 1 ? 1 : page, size < 1 ? (chunk ? 10 : 20) : chunk ? Math.min(size, 100) : size);
  }

  private static String validation(String field, String tag) {
    return "Key: 'Pagination."
        + field
        + "' Error:Field validation for '"
        + field
        + "' failed on the '"
        + tag
        + "' tag";
  }

  private static long number(HttpServletRequest request, String key) {
    String raw = param(request, key, "");
    if (raw.isEmpty()) return 0;
    if (!raw.matches("[+-]?[0-9]+"))
      throw bad("strconv.ParseInt: parsing " + quote(raw) + ": invalid syntax");
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException ex) {
      throw bad("strconv.ParseInt: parsing " + quote(raw) + ": value out of range");
    }
  }

  private static KnowledgeContentReadRepository.ListFilter filter(HttpServletRequest r) {
    String sort = param(r, "sort_by", "created_at"), order = param(r, "sort_order", "desc");
    if (!Set.of("updated_at", "created_at", "file_name").contains(sort))
      throw bad("invalid sort_by: must be updated_at, created_at, or file_name");
    if (!Set.of("asc", "desc").contains(order))
      throw bad("invalid sort_order: must be asc or desc");
    List<String> tags = new ArrayList<>();
    for (String tag : param(r, "tag_ids", "").split(",", -1)) {
      tag = StoragePaths.trim(tag);
      if (!tag.isEmpty() && !tag.equals("__untagged__")) tags.add(tag);
    }
    String folder = param(r, "folder_path", "");
    boolean folderPresent = r.getParameter("folder_path") != null;
    String recursive = param(r, "folder_recursive", "false");
    return new KnowledgeContentReadRepository.ListFilter(
        tags,
        param(r, "keyword", ""),
        param(r, "file_type", ""),
        param(r, "source", ""),
        param(r, "parse_status", ""),
        sort,
        order,
        filterTime(param(r, "start_time", ""), "start_time"),
        filterTime(param(r, "end_time", ""), "end_time"),
        folderPresent,
        KnowledgeImportNames.normalizeFolder(folder),
        Set.of("1", "t", "T", "TRUE", "true", "True").contains(recursive));
  }

  private static Instant filterTime(String value, String name) {
    return GoKnowledgeFilterTime.parse(value, name);
  }

  private static String quote(String value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex);
    }
  }

  private static String param(HttpServletRequest request, String key, String fallback) {
    String value = request.getParameter(key);
    return value == null ? fallback : value;
  }

  private static ApiException bad(String message) {
    return new ApiException(400, "BAD_REQUEST", message).withAppCode(1000);
  }
}
