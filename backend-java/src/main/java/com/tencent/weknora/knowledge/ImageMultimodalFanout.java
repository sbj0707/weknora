package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.google.re2j.Pattern;
import com.tencent.weknora.task.*;
import java.util.*;

/** Manual fan-out: resolve capability before publication, then atomically own the image slots. */
public final class ImageMultimodalFanout {
  private static final Pattern MARKDOWN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)"),
      HTML = Pattern.compile("(?i)<img\\b([^>]*?)\\ssrc\\s*=\\s*['\"]([^'\"]+)['\"]([^>]*)>");
  private final ImageMultimodalRepository repository;
  private final ObjectMapper json;
  private final ImageMultimodalHandler.Vision models;
  private final KnowledgeFileUpload.Enqueuer tasks;

  public ImageMultimodalFanout(
      ImageMultimodalRepository repository,
      ObjectMapper json,
      ImageMultimodalHandler.Vision models,
      KnowledgeFileUpload.Enqueuer tasks) {
    this.repository = repository;
    this.json = json;
    this.models = models;
    this.tasks = tasks;
  }

  void supported(long owner, JsonNode effective) throws TaskFailure {
    JsonNode cfg = effective.path("vlm_config");
    if (!ImageMultimodalHandler.enabled(cfg))
      throw new TaskFailure("VLM is not enabled for this knowledge base", false);
    if (cfg.path("model_id").asText().isEmpty())
      throw new TaskFailure("Native legacy inline VLM configuration is not migrated", false);
    try {
      models.resolve(owner, cfg);
    } catch (RuntimeException unavailable) {
      throw new TaskFailure(unavailable.getMessage(), false);
    }
  }

  void enqueue(
      TaskRecord job,
      DocumentProcessingRepository.Snapshot source,
      JsonNode effective,
      List<ManualImageResolver.Image> images,
      List<DocumentChunks.Stored> chunks)
      throws Exception {
    var payloads = new ArrayList<ObjectNode>();
    for (int i = 0; i < images.size(); i++) {
      var image = images.get(i);
      var p =
          json.createObjectNode()
              .put("tenant_id", job.tenantId())
              .put("knowledge_id", source.knowledge().path("id").asText())
              .put("knowledge_base_id", source.knowledgeBase().path("id").asText())
              .put("chunk_id", owner(image.serving(), chunks))
              .put("image_url", image.serving())
              .put("image_local_path", "")
              .put("enable_ocr", true)
              .put("enable_caption", true);
      p.set("image_actions", effective.path("image_actions").deepCopy());
      if (effective.path("image_attrs_enabled").asBoolean()) p.put("image_attrs_enabled", true);
      String language = job.payload().path("language").asText("zh-CN");
      if (!language.isEmpty()) p.put("language", language);
      if (job.payload().path("attempt").asLong() > 0)
        p.put("attempt", job.payload().path("attempt").asLong());
      if (i > 0) p.put("image_index", i);
      for (String key :
          List.of(
              "lf_trace_id", "lf_parent_obs_id", "lf_traceparent", "lf_user_id", "lf_session_id"))
        if (job.payload().hasNonNull(key) && !job.payload().path(key).asText().isEmpty())
          p.set(key, job.payload().get(key));
      payloads.add(p);
    }
    repository.seed(job, source, payloads, tasks);
  }

  static String owner(String url, List<DocumentChunks.Stored> chunks) {
    if (url.isEmpty()) return "";
    String owner = "";
    for (var c : chunks) {
      boolean match = false;
      var md = MARKDOWN.matcher(c.content());
      while (md.find())
        if (md.group(2).equals(url)) {
          match = true;
          break;
        }
      if (!match) {
        var html = HTML.matcher(c.content());
        while (html.find())
          if (KnowledgeUploadForms.goTrim(html.group(2)).equals(url)) {
            match = true;
            break;
          }
      }
      if (match) {
        if (!owner.isEmpty() && !owner.equals(c.id())) return "";
        owner = c.id();
      }
    }
    return owner;
  }
}
