package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Query-entity extraction uses its own configured template, with the shared Go formatter. */
public final class GraphQueryText {
  private final ObjectMapper json;
  private final JsonNode template;
  private final GraphExtractionText formatter;

  public GraphQueryText(ObjectMapper json, JsonNode template) {
    this.json = json;
    this.template = template.deepCopy();
    formatter = new GraphExtractionText(json, "");
  }

  public List<ObjectNode> messages(String query) {
    var lines = new ArrayList<String>();
    // Go's query plugin copies description/examples but deliberately omits template tags.
    lines.add(template.path("description").asText());
    if (!template.path("examples").isEmpty()) {
      lines.add("# Examples");
      for (var example : template.path("examples")) {
        var config = json.createObjectNode().put("text", example.path("text").asText());
        config.set("nodes", example.path("node"));
        config.set("relations", example.path("relation"));
        String rendered = formatter.messages("", config).getFirst().path("content").asText();
        String prefix = "\n# Examples\n";
        if (!rendered.startsWith(prefix) || !rendered.endsWith("\n"))
          throw new IllegalStateException("Shared graph formatter example contract changed");
        lines.add(rendered.substring(prefix.length(), rendered.length() - 1));
        lines.add("");
      }
    }
    return List.of(
        json.createObjectNode().put("role", "system").put("content", String.join("\n", lines)),
        json.createObjectNode().put("role", "user").put("content", "# Question\nQ: " + query + "\nA: "));
  }

  public ObjectNode options() { return formatter.options(); }
  public List<String> entities(String output) {
    return formatter.parse(output).nodes().stream().map(GraphExtractionText.Node::name).toList();
  }
}
