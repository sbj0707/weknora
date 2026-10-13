package com.tencent.weknora.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RerankMetadataGoParityTest {
  @Test
  void actualGoTypedImageAttrsAndDocumentQuestionsPreserveSavedTypeErrors() throws Exception {
    ObjectMapper json = new ObjectMapper();
    JsonNode fixture = json.readTree(Files.readString(Path.of(System.getProperty(
        "weknora.rerank.metadata.fixture",
        "src/test/java/com/tencent/weknora/retrieval/go-rerank-metadata.json"))));
    int count = 0;
    for (JsonNode c : fixture) {
      ObjectNode row = json.createObjectNode()
          .put("id", "row").put("content", "body").put("chunk_type", "text")
          .put("knowledge_title", "Title").put("context_header", "Header")
          .put("image_info", c.path("image").asText())
          .put("chunk_metadata_raw", c.path("metadata_raw").asText());
      row.set("chunk_metadata", json.readTree(c.path("metadata_raw").asText()));
      assertThat(RerankPassage.enriched(row)).as("enriched: %s", c)
          .isEqualTo(c.path("enriched").asText());
      assertThat(RerankPassage.model(row)).as("model: %s", c)
          .isEqualTo(c.path("model").asText());
      count++;
    }
    assertThat(count).isEqualTo(80);
  }
}
