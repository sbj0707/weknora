package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.chunker.*;
import java.util.*;

/** Go parent/text chunk materialization; parents are stored but excluded from embeddings. */
public final class DocumentChunks {
  @FunctionalInterface
  public interface Locations {
    List<Map<String, Object>> locate(long start, long end);
  }

  public record Stored(
      String id,
      String content,
      String contextHeader,
      int index,
      int start,
      int end,
      String type,
      String parentId,
      String previousId,
      String nextId,
      List<Map<String, Object>> locations) {
    public String embeddingContent() {
      String body = KnowledgeUploadForms.goTrim(content);
      return contextHeader.isEmpty() ? body : contextHeader + "\n\n" + body;
    }

    Stored links(String previous, String next) {
      return new Stored(
          id, content, contextHeader, index, start, end, type, parentId, previous, next, locations);
    }
  }

  private DocumentChunks() {}

  public static List<Stored> create(String markdown, JsonNode chunking, Locations locations) {
    var base =
        new SplitterConfig(
                chunking.path("chunk_size").asInt(),
                chunking.path("chunk_overlap").asInt(),
                strings(chunking.path("separators")),
                chunking.path("strategy").asText(),
                chunking.path("token_limit").asInt(),
                strings(chunking.path("languages")))
            .normalized();
    List<Stored> parents = new ArrayList<>(), text = new ArrayList<>();
    if (chunking.path("enable_parent_child").asBoolean()) {
      var configs =
          AdaptiveChunker.deriveParentChildConfigs(
              base,
              chunking.path("parent_chunk_size").asInt(),
              chunking.path("child_chunk_size").asInt());
      var parsed = AdaptiveChunker.splitParentChild(markdown, configs.parent(), configs.child());
      for (Chunk parent : parsed.parents())
        parents.add(stored(parent, "parent_text", "", locations));
      for (var child : parsed.children()) {
        Chunk chunk = child.chunk();
        if (KnowledgeUploadForms.goTrim(chunk.content()).isEmpty()) continue;
        String parent =
            child.parentIndex() >= 0 && child.parentIndex() < parents.size()
                ? parents.get(child.parentIndex()).id()
                : "";
        text.add(stored(chunk, "text", parent, locations));
      }
    } else
      for (Chunk chunk : AdaptiveChunker.split(markdown, base))
        if (!KnowledgeUploadForms.goTrim(chunk.content()).isEmpty())
          text.add(stored(chunk, "text", "", locations));
    links(parents);
    if (parents.isEmpty()) links(text);
    List<Stored> result = new ArrayList<>(parents);
    result.addAll(text);
    result.sort(Comparator.comparingInt(Stored::index));
    return List.copyOf(result);
  }

  private static Stored stored(Chunk chunk, String type, String parent, Locations locations) {
    return new Stored(
        UUID.randomUUID().toString(),
        chunk.content(),
        chunk.contextHeader(),
        chunk.seq(),
        chunk.start(),
        chunk.end(),
        type,
        parent,
        "",
        "",
        located(locations, chunk.start(), chunk.end()));
  }

  private static List<Map<String, Object>> located(Locations locations, long start, long end) {
    var value = locations.locate(start, end);
    return value == null ? List.of() : List.copyOf(value);
  }

  private static void links(List<Stored> chunks) {
    for (int i = 0; i < chunks.size(); i++) {
      Stored chunk = chunks.get(i);
      chunks.set(
          i,
          chunk.links(
              i > 0 ? chunks.get(i - 1).id() : "",
              i + 1 < chunks.size() ? chunks.get(i + 1).id() : ""));
    }
  }

  private static List<String> strings(JsonNode input) {
    List<String> values = new ArrayList<>();
    for (JsonNode node : input) values.add(node.asText());
    return values;
  }

  public static String indexContent(String title, Stored chunk) {
    String trimmed = KnowledgeUploadForms.goTrim(title);
    return trimmed.isEmpty() ? chunk.embeddingContent() : trimmed + "\n" + chunk.embeddingContent();
  }
}
