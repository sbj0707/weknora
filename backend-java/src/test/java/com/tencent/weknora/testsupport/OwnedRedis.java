package com.tencent.weknora.testsupport;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** A loopback-only, nonpersistent Redis subprocess owned exclusively by one test scope. */
public final class OwnedRedis implements AutoCloseable {
  private final Path directory;
  private final Process process;
  private final int port;

  public OwnedRedis() throws IOException, InterruptedException {
    directory = Files.createTempDirectory("weknora-owned-redis-");
    try (var reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = reservation.getLocalPort();
    }
    process =
        new ProcessBuilder(
                System.getProperty("weknora.redis.executable", "redis-server"),
                "--bind",
                "127.0.0.1",
                "--port",
                Integer.toString(port),
                "--save",
                "",
                "--appendonly",
                "no",
                "--dir",
                directory.toString())
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("redis.log").toFile())
            .start();
    try {
      long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
      while (true) {
        try (var socket = new Socket()) {
          socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
          socket.setSoTimeout(250);
          socket.getOutputStream().write("PING\r\n".getBytes(StandardCharsets.US_ASCII));
          String reply =
              new BufferedReader(
                      new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                  .readLine();
          if ("+PONG".equals(reply)) break;
          throw new IOException("Unexpected owned Redis PING response");
        } catch (IOException waiting) {
          if (!process.isAlive() || System.nanoTime() >= deadline)
            throw new IOException(
                "Owned Redis failed to start; log: " + directory.resolve("redis.log"), waiting);
          Thread.sleep(20);
        }
      }
    } catch (IOException | InterruptedException failure) {
      close();
      throw failure;
    }
  }

  public String address() {
    return "127.0.0.1:" + port;
  }

  public String uri() {
    return "redis://" + address();
  }

  public Path directory() {
    return directory;
  }

  @Override
  public void close() throws InterruptedException {
    process.destroy();
    if (!process.waitFor(3, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      if (!process.waitFor(3, TimeUnit.SECONDS))
        throw new IllegalStateException("Owned Redis process did not stop");
    }
  }
}
