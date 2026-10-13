package com.tencent.weknora.retrieval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.google.re2j.*;
import java.util.*;

/** Production reranking.CleanPassage / EnrichedPassage / ModelPassage. */
public final class RerankPassage {
  private static final Pattern CODE = Pattern.compile("(?s)```[^\\r\\n]*\\r?\\n(.*?)\\r?\\n?```"),
      LATEX = Pattern.compile("(?s)\\$\\$(.*?)\\$\\$"),
      HTML = Pattern.compile("</?[a-zA-Z][^>]*>"),
      LINKED =
          Pattern.compile(
              "\\[!\\[([^\\]]*)\\]\\(([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*)\\)\\]\\([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*\\)"),
      IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*\\)"),
      LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*\\)"),
      URL = Pattern.compile("https?://[^\\s)\\]>]+"),
      TABLESEP = Pattern.compile("(?m)^[ \\t]*\\|[ \\t:|-]+\\|[ \\t]*$"),
      TABLEROW = Pattern.compile("(?m)^[ \\t]*\\|(.+?)\\|[ \\t]*$"),
      HEAD = Pattern.compile("(?m)^#{1,6}\\s+"),
      QUOTE = Pattern.compile("(?m)^>\\s?"),
      BOLD3 = Pattern.compile("\\*{3}(.+?)\\*{3}"),
      BOLD2 = Pattern.compile("\\*{2}(.+?)\\*{2}"),
      BOLD1 = Pattern.compile("\\*(.+?)\\*"),
      LIST = Pattern.compile("(?m)^[\\t ]*(?:[-*+]|\\d+\\.)\\s+"),
      NEWLINES = Pattern.compile("\\n{3,}");

  public static String clean(String value) {
    String text = CODE.matcher(value).replaceAll("$1");
    text = LATEX.matcher(text).replaceAll("$1");
    text = HTML.matcher(text).replaceAll("");
    text = LINKED.matcher(text).replaceAll("![$1]($2)");
    text = IMAGE.matcher(text).replaceAll("");
    text = LINK.matcher(text).replaceAll("$1");
    text = URL.matcher(text).replaceAll("");
    text = TABLESEP.matcher(text).replaceAll("");
    Matcher matcher = TABLEROW.matcher(text);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      List<String> cells = new ArrayList<>();
      for (String cell : matcher.group(1).split("\\|", -1)) {
        cell = HybridText.trim(cell);
        if (!cell.isEmpty()) cells.add(cell);
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(String.join(", ", cells)));
    }
    matcher.appendTail(out);
    text = out.toString();
    text = HEAD.matcher(text).replaceAll("");
    text = QUOTE.matcher(text).replaceAll("");
    text = BOLD3.matcher(text).replaceAll("$1");
    text = BOLD2.matcher(text).replaceAll("$1");
    text = BOLD1.matcher(text).replaceAll("$1");
    text = LIST.matcher(text).replaceAll("");
    text = NEWLINES.matcher(text).replaceAll("\n\n");
    return HybridText.trim(text);
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  public static String enriched(ObjectNode result) {
    String content = result.path("content").asText(),
        body = HybridText.trim(content),
        text = clean(content);
    List<String> additions = new ArrayList<>();
    String images = result.path("image_info").asText();
    if (!images.isEmpty())
      try {
        JsonNode root = RerankMetadata.images(images);
        if (root != null && !root.isNull() && !root.isArray()) throw new IllegalArgumentException();
        if (root != null && root.isArray()) {
          for (JsonNode image : root) validateImage(image);
          for (JsonNode image : root)
            for (String key : List.of("caption", "ocr_text")) {
              String value = image.path(key).asText("");
              if (!value.isEmpty() && !HybridText.trim(value).equals(body)) additions.add(value);
            }
        }
      } catch (Exception ignored) {
      }
    JsonNode metadata = result.get("chunk_metadata");
    if (metadata != null && !metadata.isNull())
      try {
        metadata =
            RerankMetadata.document(
                result.has("chunk_metadata_raw")
                    ? result.path("chunk_metadata_raw").asText()
                    : metadata.isTextual() ? metadata.asText() : metadata.toString());
        if (metadata != null && !metadata.isNull() && !metadata.isObject())
          throw new IllegalArgumentException();
        JsonNode questions =
            metadata == null ? NullNode.instance : metadata.path("generated_questions");
        if (!questions.isMissingNode() && !questions.isNull() && !questions.isArray())
          throw new IllegalArgumentException();
        List<String> values = new ArrayList<>();
        for (JsonNode question : questions) {
          if (!question.isNull() && !question.isObject()) throw new IllegalArgumentException();
          for (String key : List.of("id", "question")) string(question.path(key));
          integer(question.path("content_revision"));
          values.add(question.path("question").asText(""));
        }
        if (metadata != null) integer(metadata.path("generated_questions_revision"));
        if (!values.isEmpty()) additions.add(String.join("; ", values));
      } catch (Exception ignored) {
      }
    if (additions.isEmpty()) return text;
    return (text.isEmpty() ? "" : text + "\n\n") + String.join("\n", additions);
  }

  public static String model(ObjectNode result) {
    String passage = enriched(result);
    if (HybridText.trim(passage).isEmpty() || result.path("chunk_type").asText().equals("faq"))
      return passage;
    String header = HybridText.trim(result.path("context_header").asText()),
        title = HybridText.trim(result.path("knowledge_title").asText());
    if (!header.isEmpty()) passage = header + "\n\n" + passage;
    if (!title.isEmpty()) passage = title + "\n\n" + passage;
    return passage;
  }

  private static void validateImage(JsonNode image) {
    if (!image.isNull() && !image.isObject()) throw new IllegalArgumentException();
    for (String key : List.of("sha256", "url", "original_url", "caption", "ocr_text"))
      string(image.path(key));
    for (String key : List.of("start_pos", "end_pos")) integer(image.path(key));
    JsonNode attrs = image.path("attrs");
    if (!attrs.isMissingNode() && !attrs.isNull()) {
      if (!attrs.isObject()) throw new IllegalArgumentException();
      string(attrs.path("schema"));
      JsonNode source = attrs.path("source");
      if (!source.isMissingNode() && !source.isNull()) {
        if (!source.isObject()) throw new IllegalArgumentException();
        string(source.path("schema"));
        string(source.path("prompt"));
        JsonNode custom = source.path("custom");
        if (!custom.isMissingNode() && !custom.isNull() && !custom.isBoolean())
          throw new IllegalArgumentException();
      }
      JsonNode values = attrs.path("attrs");
      if (!values.isMissingNode() && !values.isNull() && !values.isObject())
        throw new IllegalArgumentException();
    }
  }

  private static void string(JsonNode value) {
    if (!value.isMissingNode() && !value.isNull() && !value.isTextual())
      throw new IllegalArgumentException();
  }

  private static void integer(JsonNode value) {
    if (!value.isMissingNode()
        && !value.isNull()
        && (!value.isIntegralNumber() || !value.canConvertToLong()))
      throw new IllegalArgumentException();
  }
}
