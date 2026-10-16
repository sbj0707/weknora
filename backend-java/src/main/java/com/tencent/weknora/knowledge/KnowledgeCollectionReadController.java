package com.tencent.weknora.knowledge;

import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Public document picker reads. Identity and scope always refer to the original caller. */
@RestController
public final class KnowledgeCollectionReadController {
  private final KnowledgeCollectionReadService service;

  public KnowledgeCollectionReadController(KnowledgeCollectionReadService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/knowledge/batch")
  public Map<String, Object> batch(HttpServletRequest request) {
    var auth = caller(request);
    String[] ids = request.getParameterValues("ids");
    String source = param(request, "agent_source_tenant_id");
    SharedAgentCollectionScope.bindUnsigned(source);
    if (ids == null)
      throw new ApiException(400, "BAD_REQUEST", "Invalid request parameters")
          .withAppCode(1000)
          .withDetails(
              "Key: 'GetKnowledgeBatchRequest.IDs' Error:Field validation for 'IDs' failed on the"
                  + " 'required' tag");
    // Gin binds every query field before the semantic source-selector check.
    SharedAgentCollectionScope.parseSource(source);
    return service.batch(
        auth,
        List.of(ids),
        KnowledgeImportNames.sanitizeForLog(param(request, "kb_id")),
        KnowledgeImportNames.sanitizeForLog(param(request, "agent_id")),
        source);
  }

  @GetMapping("/api/v1/knowledge/search")
  public Map<String, Object> search(HttpServletRequest request) {
    var auth = caller(request);
    String keyword = param(request, "keyword");
    if (keyword.isEmpty()) keyword = param(request, "query");
    keyword = com.tencent.weknora.storage.StoragePaths.trim(keyword);
    if (keyword.isEmpty()
        && !Set.of("1", "t", "T", "TRUE", "true", "True").contains(param(request, "recent")))
      throw new ApiException(
              400, "BAD_REQUEST", "missing search keyword: pass ?keyword=... or ?query=...")
          .withAppCode(1000);
    long offset = page(request, "offset", 0), limit = page(request, "limit", 20);
    List<String> types = new ArrayList<>();
    for (String value : param(request, "file_types").split(",", -1)) {
      value = com.tencent.weknora.storage.StoragePaths.trim(value);
      if (!value.isEmpty()) types.add(value);
    }
    return service.search(
        auth,
        keyword,
        offset,
        (int) limit,
        types,
        param(request, "agent_id"),
        param(request, "agent_source_tenant_id"));
  }

  private static AuthContext caller(HttpServletRequest r) {
    var auth = CurrentAuth.get(r);
    auth.requireRole("viewer");
    KnowledgeContentReadAuthorization.requireRetrieve(auth);
    return auth;
  }

  private static long page(HttpServletRequest r, String name, long fallback) {
    String raw = com.tencent.weknora.storage.StoragePaths.trim(param(r, name));
    if (raw.isEmpty()) return fallback;
    try {
      if (!raw.matches("[+-]?[0-9]+")) throw new NumberFormatException();
      long n = Long.parseLong(raw);
      if (name.equals("offset") ? n < 0 : n < 1 || n > 100) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException ex) {
      throw new ApiException(
              400,
              "VALIDATION_ERROR",
              name.equals("offset")
                  ? "offset must be a non-negative integer"
                  : "limit must be between 1 and 100")
          .withAppCode(1010);
    }
  }

  private static String param(HttpServletRequest r, String name) {
    return Objects.toString(r.getParameter(name), "");
  }
}
