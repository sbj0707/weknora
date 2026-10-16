package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.ModelURLPolicy;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.security.*;
import java.net.*;
import java.time.*;
import java.util.*;

/** Real ES8 connection probe, create lifecycle and name-only mutations. */
public final class VectorStorePublicService {
  private static final Set<String> VALID =
      Set.of(
          "elasticsearch",
          "qdrant",
          "milvus",
          "weaviate",
          "doris",
          "tencent_vectordb",
          "opensearch");
  private final VectorStorePublicRepository repository;
  private final VectorStoreEnvironment environment;
  private final List<ObjectNode> startupEnv;
  private final VectorStoreRegistry registry;
  private final VectorStoreRegistry.Factory factory;
  private final SafeModelHttp http;
  private final ObjectMapper json;
  private final ModelURLPolicy policy;

  public VectorStorePublicService(
      VectorStorePublicRepository repository,
      VectorStoreEnvironment environment,
      VectorStoreRegistry registry,
      VectorStoreRegistry.Factory factory,
      SafeModelHttp http,
      ObjectMapper json,
      ModelURLPolicy policy) {
    this.repository = repository;
    this.environment = environment;
    this.registry = registry;
    this.factory = factory;
    this.http = http;
    this.json = json;
    this.policy = policy;
    startupEnv = environment.stores();
  }

  public ObjectNode get(long owner, String id) {
    ObjectNode row;
    try {
      row = id.startsWith("__env_") ? environment.find(id) : repository.get(owner, id);
    } catch (RuntimeException e) {
      throw VectorStorePublicForms.direct(500, "failed to query vector store");
    }
    if (row == null) throw VectorStorePublicForms.direct(404, "vector store not found");
    return row;
  }

  public List<ObjectNode> list(long owner) {
    try {
      var list = new ArrayList<>(environment.stores());
      list.addAll(repository.list(owner));
      return list.stream().map(VectorStoreEnvironment::mask).toList();
    } catch (RuntimeException e) {
      throw VectorStorePublicForms.error(500, 1007, "Internal server error");
    }
  }

  public ObjectNode create(long owner, JsonNode body) {
    String name = body.path("name").asText(), engine = body.path("engine_type").asText();
    if (name.isEmpty()) throw validation("name is required");
    if (!VALID.contains(engine)) throw validation("unsupported engine type: " + engine);
    var cc =
        body.path("connection_config").isObject()
            ? (ObjectNode) body.path("connection_config").deepCopy()
            : json.createObjectNode();
    var index =
        body.path("index_config").isObject() ? body.path("index_config") : json.createObjectNode();
    validateConnection(engine, cc);
    inputPolicy(engine, cc);
    validateIndex(index);
    List<ObjectNode> existing;
    try {
      existing = repository.matchingEngine(owner, engine);
    } catch (RuntimeException failed) {
      throw VectorStorePublicForms.error(500, 1007, "failed to check for duplicates");
    }
    for (var row : existing)
      if (duplicate(engine, cc, index, row))
        throw VectorStorePublicForms.error(
            409, 1005, "a vector store with the same endpoint and index already exists");
    for (var row : startupEnv)
      if (duplicate(engine, cc, index, row))
        throw VectorStorePublicForms.error(
            409,
            1005,
            "a vector store with the same endpoint and index is already configured via environment"
                + " variables");
    supported(engine);
    String version;
    try {
      version = probe(engine, cc);
    } catch (ApiException error) {
      throw VectorStorePublicForms.error(
          400,
          1000,
          "connection test failed: "
              + goError(error)
              + ". Ensure the server is reachable before saving.");
    }
    if (!version.isEmpty()) cc.put("version", version);
    ObjectNode saved = repository.create(owner, name, engine, cc, index);
    try (var call = CallContext.timeout(Duration.ofSeconds(10))) {
      var service =
          factory.build(
              new VectorStoreMetadata.Store(
                  saved.path("id").asText(), owner, engine, cc, (ObjectNode) index),
              call);
      registry.register(saved.path("id").asText(), service);
    } catch (RuntimeException failed) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "Store persisted; runtime registration failed: {}",
              failed.getClass().getSimpleName());
    }
    return VectorStoreEnvironment.mask(saved);
  }

  public ObjectNode update(long owner, String id, JsonNode body) {
    String name = body.path("name").asText();
    if (name.isEmpty()) throw validation("name is required");
    repository.rename(owner, id, name);
    try {
      var result = repository.get(owner, id);
      return result == null ? null : VectorStoreEnvironment.mask(result);
    } catch (RuntimeException failed) {
      // Go acknowledges the committed name update even if re-fetch fails.
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Store renamed; response re-fetch failed");
      return null;
    }
  }

  public void delete(long owner, String id) {
    repository.delete(owner, id);
    try {
      registry.unregister(id);
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Deleted store runtime unregister failed");
    }
  }

  public String testRaw(JsonNode body) {
    String engine = body.path("engine_type").asText();
    if (!VALID.contains(engine))
      throw validation("connection test is not supported for engine type: " + engine);
    var cc = body.path("connection_config");
    validateConnection(engine, cc);
    inputPolicy(engine, cc);
    supported(engine);
    return probe(engine, cc);
  }

  public String testStored(long owner, ObjectNode row) {
    String engine = row.path("engine_type").asText();
    supported(engine);
    String version = probe(engine, row.path("connection_config"));
    if (!row.path("readonly").asBoolean()
        && !version.isEmpty()
        && !version.equals(row.path("connection_config").path("version").asText())) {
      var cc = (ObjectNode) row.path("connection_config").deepCopy();
      cc.put("version", version);
      try {
        repository.version(owner, row.path("id").asText(), cc);
      } catch (RuntimeException failed) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Detected store version persistence failed");
      }
    }
    return version;
  }

  String probe(String engine, JsonNode cc) {
    if (Set.of("sqlite", "postgres").contains(engine)) {
      if (engine.equals("sqlite") || cc.path("use_default_connection").asBoolean()) return "";
      throw new ApiException(
              503,
              "VECTOR_STORE_ENGINE_NOT_MIGRATED",
              "Native standalone postgres connection management is not migrated")
          .withAppCode(2201);
    }
    String address = cc.path("addr").asText();
    try {
      var uri = new URI(address);
      if (uri.getScheme() == null) throw new Exception();
    } catch (Exception e) {
      throw VectorStorePublicForms.error(
          400, 1000, "failed to create elasticsearch request: invalid address");
    }
    var headers = new LinkedHashMap<String, String>();
    if (!cc.path("username").asText().isEmpty())
      headers.put(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (cc.path("username").asText() + ":" + cc.path("password").asText())
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    try (var call = CallContext.timeout(Duration.ofSeconds(10));
        var response = http.getWithoutRedirects(call, address, headers, Duration.ofSeconds(10))) {
      if (response.status() != 200)
        throw VectorStorePublicForms.error(
            400, 1000, "failed to connect to elasticsearch: authentication failed or server error");
      try {
        return VectorStorePublicForms.version(response.body().readNBytes(4096), json);
      } catch (Exception invalid) {
        return "";
      }
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException failed) {
      throw VectorStorePublicForms.error(
          400,
          1000,
          "failed to connect to elasticsearch: connection refused or authentication failed");
    }
  }

  static void supported(String engine) {
    if (!Set.of("elasticsearch", "sqlite", "postgres").contains(engine))
      throw new ApiException(
              503,
              "VECTOR_STORE_ENGINE_NOT_MIGRATED",
              "Native vector store connection management is not migrated for " + engine)
          .withAppCode(2201);
  }

  static String goError(ApiException error) {
    return "error code: " + error.appCode() + ", error message: " + error.getMessage();
  }

  private static ApiException validation(String message) {
    return VectorStorePublicForms.error(400, 1010, message);
  }

  private void inputPolicy(String engine, JsonNode cc) {
    String addr = cc.path("addr").asText();
    if (engine.equals("qdrant")) {
      addr = cc.path("host").asText();
      if (cc.path("port").asInt() != 0) addr += ":" + cc.path("port").asInt();
    } else if (engine.equals("weaviate")) {
      checkAddress(cc.path("host").asText());
      addr = cc.path("grpc_address").asText();
    }
    checkAddress(addr);
  }

  private void checkAddress(String address) {
    try {
      policy.validate(address);
    } catch (ApiException e) {
      String message = e.getMessage().replace("Base URL", "vector store address");
      if (address.matches("(?i)^https?://\\[[^\\]]*$"))
        message =
            message.replace(
                "invalid URL: invalid URL format",
                "invalid URL: parse \"" + address + "\": missing ']' in host");
      throw validation(message);
    }
  }

  private void validateConnection(String engine, JsonNode cc) {
    String field =
        switch (engine) {
          case "qdrant", "weaviate" -> "host";
          default -> "addr";
        };
    if (cc.path(field).asText().isEmpty())
      throw validation(
          field
              + " is required for "
              + (engine.equals("doris") ? "doris (FE MySQL host:port)" : engine));
    if (engine.equals("tencent_vectordb"))
      for (String required : List.of("username", "api_key"))
        if (cc.path(required).asText().isEmpty())
          throw validation(required + " is required for " + engine);
    if (engine.equals("doris") && cc.path("database").asText().isEmpty())
      throw validation("database is required for doris");
  }

  private void validateIndex(JsonNode ix) {
    for (String name : List.of("index_name", "collection_prefix", "collection_name"))
      if (!ix.path(name).asText().isEmpty()
          && !ix.path(name).asText().matches("[a-zA-Z][a-zA-Z0-9_-]{0,127}"))
        throw validation(
            name
                + " must start with a letter and contain only alphanumeric, underscore, or hyphen"
                + " characters (max 128)");
    for (String name :
        List.of(
            "number_of_shards",
            "number_of_replicas",
            "shard_number",
            "replication_factor",
            "shards_num",
            "replica_number",
            "desired_shard_count",
            "buckets_num",
            "replication_num")) {
      int max =
          Set.of("number_of_replicas", "replication_factor", "replica_number", "replication_num")
                  .contains(name)
              ? 10
              : 64;
      long n = ix.path(name).asLong();
      if (n < 0 || n > max) throw validation(name + " must be between 0 and " + max);
    }
  }

  private boolean duplicate(String engine, JsonNode cc, JsonNode ix, JsonNode row) {
    return engine.equals(row.path("engine_type").asText())
        && endpoint(cc).equals(endpoint(row.path("connection_config")))
        && index(engine, ix).equals(index(engine, row.path("index_config")));
  }

  private String endpoint(JsonNode cc) {
    String addr = cc.path("addr").asText();
    if (!addr.isEmpty())
      return addr
          + (cc.path("database").asText().isEmpty() ? "" : "/" + cc.path("database").asText());
    if (!cc.path("host").asText().isEmpty())
      return cc.path("host").asText()
          + ":"
          + (cc.path("port").asInt() == 0 ? 6334 : cc.path("port").asInt());
    return cc.path("use_default_connection").asBoolean() ? "__default_postgres__" : "";
  }

  private String index(String engine, JsonNode ix) {
    String name = ix.path("index_name").asText();
    return name.isEmpty() && engine.equals("elasticsearch") ? "xwrag_default" : name;
  }
}
