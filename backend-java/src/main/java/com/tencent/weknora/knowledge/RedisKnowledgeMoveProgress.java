package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** Uses the production Go Redis key and TTL; opening a connection is lazy. */
public final class RedisKnowledgeMoveProgress implements KnowledgeMoveProgress, AutoCloseable {
  public static final long TTL_SECONDS = 86400;
  private final ObjectMapper json;
  private final Supplier<RedisClient> clients;
  private RedisClient client;
  private StatefulRedisConnection<byte[], byte[]> connection;

  public RedisKnowledgeMoveProgress(ObjectMapper json, Supplier<RedisClient> clients) {
    this.json = json;
    this.clients = clients;
  }

  private synchronized StatefulRedisConnection<byte[], byte[]> connection() {
    if (connection == null) {
      if (client == null) client = clients.get();
      connection = client.connect(ByteArrayCodec.INSTANCE);
    }
    return connection;
  }

  private static byte[] key(String id) {
    return ("knowledge_move_progress:" + id).getBytes(StandardCharsets.UTF_8);
  }

  private byte[] bytes(ObjectNode progress) {
    return KnowledgeMoveJson.progress(json, progress)
        .toString()
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
        .replace("&", "\\u0026")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public void initialize(ObjectNode progress) {
    connection()
        .sync()
        .set(
            key(progress.path("task_id").asText()),
            bytes(progress),
            SetArgs.Builder.nx().ex(TTL_SECONDS));
  }

  @Override
  public void save(ObjectNode progress) {
    connection()
        .sync()
        .set(
            key(progress.path("task_id").asText()),
            bytes(progress),
            SetArgs.Builder.ex(TTL_SECONDS));
  }

  @Override
  public ObjectNode get(String id) {
    byte[] value;
    try {
      value = connection().sync().get(key(id));
    } catch (RuntimeException failure) {
      throw new IllegalStateException(
          "failed to get move progress from Redis: " + failure.getMessage(), failure);
    }
    if (value == null)
      throw new ApiException(404, "NOT_FOUND", "Knowledge move task not found").withAppCode(1003);
    try {
      return KnowledgeMoveJson.progress(
          json,
          new GoUploadJson(json)
              .decode(GoUtf8.decode(value), KnowledgeMoveJson.progressSchema(json)));
    } catch (IOException failure) {
      throw new IllegalStateException(
          "failed to unmarshal move progress: " + failure.getMessage(), failure);
    }
  }

  @Override
  public synchronized void close() {
    if (connection != null) connection.close();
    if (client != null) client.shutdown();
    connection = null;
    client = null;
  }
}
