package com.tencent.weknora.knowledge;

import com.tencent.weknora.model.ModelEgressPolicy;
import com.tencent.weknora.model.ModelURLPolicy;
import com.tencent.weknora.model.client.*;
import java.io.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Go file_url download contract; every redirect uses checked, pinned DNS. */
public final class RemoteDocumentDownloads implements AutoCloseable {
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  private static final Duration TIMEOUT = Duration.ofSeconds(60);
  public record Download(byte[] bytes, String filename, String fileType) {
    public Download { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
  }
  private final ModelURLPolicy urls;
  private final SafeModelHttp http;
  private final ScheduledExecutorService guards = Executors.newSingleThreadScheduledExecutor(r -> {
    var thread = new Thread(r, "document-download-cancellation"); thread.setDaemon(true); return thread;
  });

  public RemoteDocumentDownloads(ModelURLPolicy urls, ModelEgressPolicy egress) {
    this.urls = urls;
    http = new SafeModelHttp(egress, 10, true);
  }

  public void validate(String url) { urls.validate(url); }

  public Download download(String url, String filename, String fileType, Runnable guard)
      throws IOException {
    guard.run();
    try (var call = CallContext.timeout(TIMEOUT)) {
      var stopped = new AtomicReference<RuntimeException>();
      var monitor = guards.scheduleWithFixedDelay(() -> {
        try { guard.run(); }
        catch (RuntimeException error) { stopped.compareAndSet(null, error); call.cancel(); }
      }, 50, 50, TimeUnit.MILLISECONDS);
      try (var response = http.get(call, url, Map.of(), TIMEOUT)) {
        if (response.status() != 200)
          throw new IOException("remote server returned status " + response.status());
        long length = contentLength(response.headers().get("content-length"));
        if (length > MAX_BYTES)
          throw new IOException("file size " + length + " bytes exceeds limit of " + MAX_BYTES + " bytes (10MB)");
        if (filename.isEmpty()) filename = dispositionFilename(response.headers().getOrDefault("content-disposition", ""));
        if (filename.isEmpty()) filename = urlFilename(url);
        if (fileType.isEmpty() && !filename.isEmpty()) fileType = KnowledgeProcessConfig.fileType(filename);
        byte[] bytes;
        try { bytes = response.body().readNBytes(MAX_BYTES + 1); }
        catch (IOException | ModelClientException error) {
          throw new IOException("failed to write temp file: " + error.getMessage(), error);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("file size exceeds limit of 10MB");
        guard.run();
        return new Download(bytes, filename, fileType);
      } catch (ModelClientException error) {
        throw new IOException("failed to download file from URL: " + error.getMessage(), error);
      } finally {
        monitor.cancel(false);
        if (stopped.get() != null) throw stopped.get();
      }
    }
  }

  private static long contentLength(String raw) {
    try { return raw == null ? -1 : Long.parseLong(raw); }
    catch (NumberFormatException ignored) { return -1; }
  }

  static String dispositionFilename(String header) {
    for (String part : header.split(";", -1)) {
      part = KnowledgeUploadForms.goTrim(part);
      if (!part.regionMatches(true, 0, "filename=", 0, 9)) continue;
      String name = part.substring(9);
      int start = 0, end = name.length();
      while (start < end && (name.charAt(start) == '\'' || name.charAt(start) == '"')) start++;
      while (end > start && (name.charAt(end - 1) == '\'' || name.charAt(end - 1) == '"')) end--;
      if (end > start) return name.substring(start, end);
    }
    return "";
  }

  static String urlFilename(String url) {
    try {
      String path = URI.create(url).getPath();
      if (path == null || path.isEmpty()) return "";
      // Go path.Base strips trailing slashes but does not clean dot segments.
      int end = path.length();
      while (end > 0 && path.charAt(end - 1) == '/') end--;
      if (end == 0) return "";
      String base = path.substring(path.lastIndexOf('/', end - 1) + 1, end);
      return base.equals(".") ? "" : base;
    } catch (IllegalArgumentException error) { return ""; }
  }

  @Override public void close() { guards.shutdownNow(); http.close(); }
}
