package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.docreader.HtmlTableNormalizer;
import com.tencent.weknora.model.client.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

/**
 * Go observation/caption/OCR action loop; child output preserves unknown vs negative attributes.
 */
public final class ImageMultimodalText {
  public record Child(String type, String content) {}

  public record Result(List<Child> children, ObjectNode imageInfo, ObjectNode output) {}

  private final ObjectMapper json;
  private final JsonNode prompts;

  public ImageMultimodalText(ObjectMapper json) {
    this.json = json;
    try (var in = getClass().getResourceAsStream("/image-multimodal-prompts.json")) {
      prompts = json.readTree(Objects.requireNonNull(in));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public Result process(
      ObjectNode payload,
      JsonNode config,
      byte[] image,
      VisionModel model,
      CallContext call,
      Runnable guard,
      ObjectNode out) {
    out.put("image_bytes", image.length);
    var info =
        json.createObjectNode()
            .put("url", payload.path("image_url").asText())
            .put("original_url", payload.path("image_url").asText())
            .put("start_pos", 0)
            .put("end_pos", 0)
            .put("caption", "")
            .put("ocr_text", "");
    try {
      info.put(
          "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    info.set(
        "attrs",
        json.createObjectNode()
            .put("schema", "")
            .set("source", json.createObjectNode().put("schema", "").put("prompt", "")));
    boolean attrs = payload.path("image_attrs_enabled").asBoolean(),
        caption = payload.path("enable_caption").asBoolean(),
        ocr = payload.path("enable_ocr").asBoolean();
    if (!attrs && !ocr) out.put("ocr_skipped", "disabled");
    if (attrs || caption) {
      guard.run();
      String raw = null;
      try {
        raw = model.predict(call, List.of(image), descriptionPrompt(payload, config, attrs));
      } catch (CancellationException e) {
        throw e;
      } catch (RuntimeException e) {
        out.put("caption_error", e.getMessage());
      }
      guard.run();
      if (raw != null) {
        if (attrs) {
          var observation = observation(raw);
          info.set("attrs", observation.attrs());
          String desc = observation.description();
          if (!desc.isEmpty()) {
            if (caption) {
              info.put("caption", desc);
              captionOutput(out, desc);
            } else out.put("caption_skipped", "disabled");
          }
          if (!observation.observed()) out.put("observation_failed", true);
          if (desc.isEmpty()) out.put("caption_missing", true);
        } else {
          String text = trim(raw);
          if (!text.isEmpty()) {
            info.put("caption", text);
            captionOutput(out, text);
          }
        }
      }
    }
    if (attrs) {
      JsonNode values = info.path("attrs").path("attrs");
      boolean wanted = ocr && decideOCR(values, payload.path("image_actions"));
      out.set("attr_policy", json.createObjectNode().put("ocr", wanted));
      out.set(
          "image_attrs",
          values.isMissingNode()
              ? (info.path("attrs").path("schema").asText().equals("attrs/2")
                  ? json.createObjectNode()
                  : NullNode.instance)
              : values);
      ocr = wanted;
      if (!wanted) out.put("ocr_skipped", "attr_policy");
    }
    if (ocr) {
      guard.run();
      boolean scanned = payload.path("image_source_type").asText().equals("scanned_pdf");
      out.put("ocr_prompt", scanned ? "scanned_pdf" : "default");
      String raw = null;
      try {
        raw =
            model.predict(call, List.of(image), prompts.path(scanned ? "scanned" : "ocr").asText());
      } catch (CancellationException e) {
        throw e;
      } catch (RuntimeException e) {
        out.put("ocr_error", e.getMessage());
      }
      guard.run();
      if (raw != null) {
        String text = sanitizeOCR(raw);
        if (!text.isEmpty()) {
          info.put("ocr_text", text);
          out.put("ocr_chars", runes(text)).put("ocr_preview", preview(text, 200));
        } else out.put("ocr_chars", 0).put("ocr_skipped", "empty_or_invalid");
      }
    }
    var children = new ArrayList<Child>();
    if (!info.path("ocr_text").asText().isEmpty())
      children.add(new Child("image_ocr", info.path("ocr_text").asText()));
    if (caption && !info.path("caption").asText().isEmpty())
      children.add(new Child("image_caption", info.path("caption").asText()));
    out.put("chunks_created", children.size());
    if (children.isEmpty()) out.put("skipped", "no_extracted_content");
    return new Result(List.copyOf(children), info, out);
  }

  private String descriptionPrompt(JsonNode payload, JsonNode cfg, boolean attrs) {
    String language = trim(cfg.path("description_language").asText());
    if (language.isEmpty()) language = language(payload.path("language").asText());
    String base =
        attrs
            ? prompts.path("attrs").asText().replace("in English.", "in " + language + ".")
            : "Provide a brief and concise description of the main content of the image in "
                + language
                + ".";
    String custom = trim(cfg.path("custom_instructions").asText());
    return custom.isEmpty()
        ? base
        : trim(base)
            + "\n\n<image_description_business_instructions>\n"
            + custom
            + "\n"
            + "</image_description_business_instructions>\n"
            + "Apply these business instructions only when they do not conflict with the"
            + " system-owned output format, citation, safety, or factuality rules.";
  }

  private record Observation(ObjectNode attrs, String description, boolean observed) {}

  private Observation observation(String raw) {
    var attrs = json.createObjectNode();
    var descriptions = new ArrayList<String>();
    for (String line : raw.split("\n", -1)) {
      String t = line.replaceAll("^[*_#\\- \\t]+", "");
      int i = t.indexOf(':');
      if (i >= 0) {
        String key = trim(t.substring(0, i)), value = trim(t.substring(i + 1));
        String norm =
            key.toLowerCase(Locale.ROOT)
                .replace(" ", "_")
                .replace("-", "_")
                .replace("*", "")
                .replace("#", "");
        String v = trim(value.toLowerCase(Locale.ROOT)).replace("*", "").replace("#", "");
        if (norm.equals("contain.text") || norm.equals("contain_text")) {
          if (Set.of("none", "sparse", "block").contains(v)) attrs.put("contain.text", v);
          continue;
        }
        if (norm.equals("contain.data_visual") || norm.equals("contain_data_visual")) {
          if (Set.of("true", "false").contains(v))
            attrs.put("contain.data_visual", v.equals("true"));
          continue;
        }
        if (key.equalsIgnoreCase("DESCRIPTION")) {
          descriptions.add(value);
          continue;
        }
      }
      if (t.equalsIgnoreCase("DESCRIPTION")) continue;
      if (!t.isEmpty()) descriptions.add(t);
    }
    String description = trim(trim(String.join(" ", descriptions)).replaceAll("^[-*# ]+", ""));
    var row = json.createObjectNode().put("schema", "attrs/2");
    row.set("source", json.createObjectNode().put("schema", "attrs/2").put("prompt", "observe/1"));
    if (!attrs.isEmpty()) row.set("attrs", attrs);
    return new Observation(row, description, !attrs.isEmpty());
  }

  static boolean decideOCR(JsonNode attrs, JsonNode actions) {
    JsonNode on = actions.path("ocr").path("on");
    boolean fallback = on.isEmpty(), incomplete = false;
    if (fallback) {
      if (attrs.path("contain.text").asText().equals("block")
          || attrs.path("contain.data_visual").asBoolean()) return true;
      return !attrs.has("contain.text") || !attrs.has("contain.data_visual");
    }
    for (JsonNode c : on) {
      String key = c.path("prop").asText();
      if (!attrs.has(key)) {
        incomplete = true;
        continue;
      }
      if (attrs.path(key).asText().equals(c.path("is").asText())) return true;
    }
    return incomplete && actions.path("ocr").path("on_unobserved").asBoolean();
  }

  static String sanitizeOCR(String raw) {
    String text = trim(raw);
    var fence = Pattern.compile("(?s)^\\s*```[a-zA-Z]*\\s*\\n(.*?)\\n\\s*```\\s*$").matcher(text);
    if (fence.matches()) text = trim(fence.group(1));
    var tags = Pattern.compile("<[^>]+>").matcher(text);
    boolean html = tags.find();
    String plain = trim(text.replaceAll("<[^>]+>", ""));
    if (html && plain.getBytes(StandardCharsets.UTF_8).length < 10) return "";
    int tagBytes = 0;
    tags.reset();
    while (tags.find()) tagBytes += tags.group().getBytes(StandardCharsets.UTF_8).length;
    if (Pattern.compile("(?i)^\\s*(<!DOCTYPE|<html|<body|<div|<p[\\s>]|<table|<h[1-6][\\s>])")
            .matcher(text)
            .find()
        || tagBytes > text.getBytes(StandardCharsets.UTF_8).length * .3)
      text = trim(htmlMarkdown(Jsoup.parseBodyFragment(text).body()));
    text = HtmlTableNormalizer.normalize(text);
    String check = trim(text).toLowerCase(Locale.ROOT).replaceAll("[.!?。！？]+$", "");
    if (Set.of(
            "无文字内容",
            "无法识别",
            "no text",
            "no text content",
            "no content",
            "empty",
            "图片中没有文字",
            "图片中没有可识别的文字")
        .contains(check)) return "";
    return trim(text.replaceAll("\\n{3,}", "\n\n"));
  }

  private static String htmlMarkdown(Node n) {
    if (n instanceof org.jsoup.nodes.TextNode t)
      return t.getWholeText().replaceAll("[ \\t\\r\\n]+", " ");
    if (!(n instanceof Element e)) return "";
    String tag = e.normalName();
    if (Set.of("script", "style", "head").contains(tag)) return "";
    if (tag.equals("table")) return "\n\n" + HtmlTableNormalizer.normalize(e.outerHtml()) + "\n\n";
    String content =
        String.join("", e.childNodes().stream().map(ImageMultimodalText::htmlMarkdown).toList());
    if (tag.matches("h[1-6]"))
      return "\n\n" + "#".repeat(Integer.parseInt(tag.substring(1))) + " " + trim(content) + "\n\n";
    return switch (tag) {
      case "p", "div", "blockquote" -> "\n\n" + trim(content) + "\n\n";
      case "br" -> "\n";
      case "strong", "b" -> "**" + trim(content) + "**";
      case "em", "i" -> "*" + trim(content) + "*";
      case "a" -> "[" + trim(content) + "](" + e.attr("href") + ")";
      case "li" -> "\n- " + trim(content);
      default -> content;
    };
  }

  private static void captionOutput(ObjectNode out, String text) {
    out.put("caption_chars", runes(text)).put("caption_preview", preview(text, 200));
  }

  static String preview(String value, int maximum) {
    value = trim(value).replace("\n", " ").replace("\t", " ");
    while (value.contains("  ")) value = value.replace("  ", " ");
    return runes(value) > maximum
        ? value.substring(0, value.offsetByCodePoints(0, maximum)) + "...(truncated)"
        : value;
  }

  static String trim(String value) {
    return KnowledgeUploadForms.goTrim(value);
  }

  private static int runes(String value) {
    return value.codePointCount(0, value.length());
  }

  private static String language(String raw) {
    String code = trim(raw);
    if (code.isEmpty()) code = trim(System.getenv().getOrDefault("WEKNORA_LANGUAGE", "zh-CN"));
    return switch (code) {
      case "zh-CN", "zh", "zh-Hans" -> "Chinese (Simplified)";
      case "zh-TW", "zh-HK", "zh-Hant" -> "Chinese (Traditional)";
      case "en-US", "en", "en-GB" -> "English";
      case "ru-RU", "ru" -> "Russian";
      case "ko-KR", "ko" -> "Korean";
      case "ja-JP", "ja" -> "Japanese";
      case "fr-FR", "fr" -> "French";
      case "es-ES", "es" -> "Spanish";
      case "pt-BR", "pt" -> "Portuguese";
      case "de-DE", "de" -> "German";
      default -> code;
    };
  }
}
