package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.core.env.Environment;

/**
 * Pure Go env-store projections; reads reflect live settings, duplicate-check uses startup
 * snapshot.
 */
public final class VectorStoreEnvironment {
  private final ObjectMapper json;
  private final Environment env;

  public VectorStoreEnvironment(ObjectMapper json, Environment env) {
    this.json = json;
    this.env = env;
  }

  String value(String key) {
    return env.getProperty(key, "");
  }

  public List<ObjectNode> stores() {
    var result = new ArrayList<ObjectNode>();
    for (String raw : value("RETRIEVE_DRIVER").split(",")) {
      String driver = com.tencent.weknora.security.AuthPreferences.trimGoSpace(raw);
      var row = store(driver);
      if (row != null) result.add(row);
    }
    return result;
  }

  ObjectNode store(String driver) {
    String name =
        switch (driver) {
          case "sqlite" -> "SQLite";
          case "postgres" -> "PostgreSQL";
          case "elasticsearch_v8" -> "Elasticsearch v8";
          case "elasticsearch_v7" -> "Elasticsearch v7";
          case "qdrant" -> "Qdrant";
          case "milvus" -> "Milvus";
          case "weaviate" -> "Weaviate";
          case "doris" -> "Apache Doris";
          case "tencent_vectordb" -> "Tencent VectorDB";
          case "opensearch" -> "OpenSearch";
          default -> null;
        };
    if (name == null) return null;
    String engine = driver.startsWith("elasticsearch_") ? "elasticsearch" : driver;
    var row =
        json.createObjectNode()
            .put("id", "__env_" + driver + "__")
            .put("tenant_id", 0)
            .put("name", name)
            .put("engine_type", engine)
            .put("created_at", "0001-01-01T00:00:00Z")
            .put("updated_at", "0001-01-01T00:00:00Z")
            .putNull("deleted_at")
            .put("source", "env")
            .put("readonly", true);
    var cc = row.putObject("connection_config");
    var ix = row.putObject("index_config");
    switch (driver) {
      case "postgres" -> cc.put("use_default_connection", true);
      case "elasticsearch_v7", "elasticsearch_v8" -> {
        copy(cc, "addr", "ELASTICSEARCH_ADDR");
        copy(cc, "username", "ELASTICSEARCH_USERNAME");
        copy(cc, "password", "ELASTICSEARCH_PASSWORD");
        copy(ix, "index_name", "ELASTICSEARCH_INDEX");
      }
      case "qdrant" -> {
        copy(cc, "host", "QDRANT_HOST");
        copy(cc, "api_key", "QDRANT_API_KEY");
      }
      case "milvus" -> {
        copy(cc, "addr", "MILVUS_ADDRESS");
        copy(cc, "username", "MILVUS_USERNAME");
        copy(cc, "password", "MILVUS_PASSWORD");
      }
      case "weaviate" -> {
        copy(cc, "host", "WEAVIATE_HOST");
        copy(cc, "grpc_address", "WEAVIATE_GRPC_ADDRESS");
        copy(cc, "scheme", "WEAVIATE_SCHEME");
        copy(cc, "api_key", "WEAVIATE_API_KEY");
      }
      case "doris" -> {
        copy(cc, "addr", "DORIS_ADDR");
        copy(cc, "database", "DORIS_DATABASE");
        copy(cc, "username", "DORIS_USERNAME");
        copy(cc, "password", "DORIS_PASSWORD");
        try {
          long port = Long.parseLong(value("DORIS_HTTP_PORT"));
          if (port != 0) cc.put("http_port", port);
        } catch (NumberFormatException ignored) {
        }
        copy(ix, "collection_prefix", "DORIS_TABLE_PREFIX");
      }
      case "tencent_vectordb" -> {
        copy(cc, "addr", "TENCENT_VECTORDB_ADDR");
        copy(cc, "database", "TENCENT_VECTORDB_DATABASE");
        copy(cc, "username", "TENCENT_VECTORDB_USERNAME");
        copy(cc, "api_key", "TENCENT_VECTORDB_API_KEY");
        copy(ix, "collection_name", "TENCENT_VECTORDB_COLLECTION");
      }
      case "opensearch" -> {
        copy(cc, "addr", "OPENSEARCH_ADDR");
        copy(cc, "username", "OPENSEARCH_USERNAME");
        copy(cc, "password", "OPENSEARCH_PASSWORD");
        if (value("OPENSEARCH_INSECURE_SKIP_VERIFY").equalsIgnoreCase("true"))
          cc.put("insecure_skip_verify", true);
        copy(ix, "index_name", "OPENSEARCH_INDEX");
      }
    }
    return row;
  }

  private void copy(ObjectNode to, String key, String property) {
    String v = value(property);
    if (!v.isEmpty()) to.put(key, v);
  }

  public ObjectNode find(String id) {
    return stores().stream().filter(s -> s.path("id").asText().equals(id)).findFirst().orElse(null);
  }

  public JsonNode types() {
    try (var in = getClass().getResourceAsStream("/vector-store-public-types.json")) {
      var result = json.readTree(in);
      long replicas = 1;
      try {
        long n =
            Long.parseLong(
                com.tencent.weknora.security.AuthPreferences.trimGoSpace(
                    value("TENCENT_VECTORDB_REPLICA_NUMBER")));
        if (n >= 0) replicas = n;
      } catch (NumberFormatException ignored) {
      }
      for (var type : result)
        if (type.path("type").asText().equals("tencent_vectordb"))
          for (var f : type.path("index_fields"))
            if (f.path("name").asText().equals("replica_number"))
              ((ObjectNode) f).put("default", replicas);
      return result;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static ObjectNode mask(ObjectNode row) {
    var result = row.deepCopy();
    var cc = (ObjectNode) result.path("connection_config");
    for (String key : List.of("password", "api_key"))
      if (!cc.path(key).asText().isEmpty()) cc.put(key, "***");
    return result;
  }
}
