package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Disabled by default while the full Go upload/processing capability set is still migrating. */
@RestController
@ConditionalOnProperty(name = "weknora.knowledge.upload.enabled", havingValue = "true")
@RequestMapping("/api/v1/knowledge-bases")
public final class KnowledgeUploadController {
  @FunctionalInterface
  public interface Creator {
    ObjectNode create(
        KnowledgeFileUpload.Command command,
        long quota,
        long used,
        KnowledgeFileUpload.Observer observer)
        throws IOException;
  }

  private final KnowledgeBaseWriteAuthorization writes;
  private final KnowledgeUploadForms forms;
  private final Creator creator;
  private final JdbcTemplate jdbc;
  private final Environment env;
  private final KnowledgeUploadAudit audit;

  @org.springframework.beans.factory.annotation.Autowired
  public KnowledgeUploadController(
      KnowledgeBaseWriteAuthorization writes,
      KnowledgeUploadForms forms,
      KnowledgeFileUpload upload,
      JdbcTemplate jdbc,
      Environment env,
      KnowledgeUploadAudit audit) {
    this(writes, forms, upload::create, jdbc, env, audit);
  }

  public KnowledgeUploadController(
      KnowledgeBaseWriteAuthorization writes,
      KnowledgeUploadForms forms,
      Creator creator,
      JdbcTemplate jdbc,
      Environment env,
      KnowledgeUploadAudit audit) {
    this.writes = writes;
    this.forms = forms;
    this.creator = creator;
    this.jdbc = jdbc;
    this.env = env;
    this.audit = audit;
  }

  @PostMapping("/{id}/knowledge/file")
  public Map<String, Object> file(HttpServletRequest request, @PathVariable("id") String id)
      throws IOException {
    AuthContext caller = CurrentAuth.get(request);
    var grant = writes.authorizeUpload(caller, id, request.getRequestURI(), request.getMethod());
    long max = maximum(env.getProperty("MAX_FILE_SIZE_MB", ""));
    try (var multipart = KnowledgeMultipartUpload.read(request, max)) {
      var bound = forms.bind(multipart.fields());
      String check = multipart.filename();
      if (!bound.customFilename().isEmpty()) {
        var split = KnowledgeImportNames.split(bound.customFilename());
        if (!split.filename().isEmpty()) check = split.filename();
      }
      if (KnowledgeProcessConfig.fileType(check).equalsIgnoreCase("json"))
        KnowledgeUploadForms.validateJson(check, multipart.bytes());
      var tenant =
          jdbc.queryForList(
              "SELECT storage_quota,storage_used FROM tenants WHERE id=? AND deleted_at IS NULL",
              grant.effectiveTenantId());
      if (tenant.isEmpty())
        throw new ApiException(500, "INTERNAL_SERVER_ERROR", "workspace context missing");
      var command =
          new KnowledgeFileUpload.Command(
              grant.effectiveTenantId(),
              grant.knowledgeBaseId(),
              multipart.filename(),
              multipart.size(),
              multipart::open,
              bound.metadata(),
              bound.multimodel(),
              bound.customFilename(),
              bound.tags(),
              bound.channel(),
              bound.overrides(),
              language(request),
              Objects.toString(request.getAttribute("request_id"), ""));
      ObjectNode row;
      try {
        row =
            creator.create(
                command,
                number(tenant.getFirst().get("storage_quota")),
                number(tenant.getFirst().get("storage_used")),
                (c, r, taskId, accepted) -> audit.record(caller, c, r, taskId, accepted));
      } catch (IOException error) {
        throw new ApiException(500, "INTERNAL_SERVER_ERROR", "Knowledge upload failed");
      }
      return Map.of("success", true, "data", row);
    }
  }

  private String language(HttpServletRequest request) {
    String configured = KnowledgeUploadForms.goTrim(env.getProperty("WEKNORA_LANGUAGE", ""));
    if (!configured.isEmpty()) return configured;
    String raw = Objects.toString(request.getHeader("Accept-Language"), "");
    String language = KnowledgeUploadForms.goTrim(raw.split(",", 2)[0].split(";", 2)[0]);
    return language.isEmpty() ? "zh-CN" : language;
  }

  static long maximum(String value) {
    try {
      long mb = Long.parseLong(value);
      return mb > 0 ? mb : 50;
    } catch (NumberFormatException error) {
      return 50;
    }
  }

  private static long number(Object value) {
    return value instanceof Number n ? n.longValue() : 0;
  }
}
