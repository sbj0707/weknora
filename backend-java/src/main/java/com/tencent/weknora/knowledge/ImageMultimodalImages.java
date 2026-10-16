package com.tencent.weknora.knowledge;

import com.tencent.weknora.model.client.*;
import com.tencent.weknora.storage.*;
import com.tencent.weknora.storage.FileStore;
import com.tencent.weknora.task.TaskRecord;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Resolve resource bytes by their owner/backend, independent of a later KB storage change. */
public final class ImageMultimodalImages implements ImageMultimodalHandler.Images, AutoCloseable {
  private final StorageResolver storage;
  private final SafeModelHttp http;

  public ImageMultimodalImages(StorageResolver storage, SafeModelHttp http) {
    this.storage = storage;
    this.http = http;
  }

  public byte[] read(TaskRecord job, ImageMultimodalRepository.Source source, CallContext call)
      throws Exception {
    String url = job.payload().path("image_url").asText();
    call.check();
    if (url.startsWith("resource://") || url.startsWith("local://") || url.startsWith("storage:")) {
      FileStore files;
      if (url.startsWith("resource://")) {
        var r = storage.catalog().resolve(job.tenantId(), url);
        files = storage.resolve(job.tenantId(), r.storageBackendId(), r.provider()).files();
      } else {
        long resourceOwner = FileUrlSigner.tenantFromPath(url);
        if (resourceOwner != 0 && resourceOwner != job.tenantId())
          throw new IOException("image resource owner binding mismatch");
        if (url.startsWith("local://") && !url.startsWith("local://" + job.tenantId() + "/"))
          throw new IOException("image resource owner binding mismatch");
        files = storage.forKnowledgeBase(job.tenantId(), source.base().path("id").asText()).files();
      }
      try (var stream = files.open(url)) {
        return stream.readAllBytes();
      } catch (NoSuchFileException missing) {
        throw new IOException(
            "file service get "
                + url
                + ": failed to open file: open "
                + missing.getFile()
                + ": no such file or directory",
            missing);
      }
    }
    if (!job.payload().path("image_local_path").asText().isEmpty())
      throw new IOException("Native legacy image_local_path requires an owned storage reference");
    if (url.startsWith("http://") || url.startsWith("https://")) {
      try (var response = http.get(call, url, Map.of(), Duration.ofSeconds(30))) {
        if (response.status() < 200 || response.status() >= 300)
          throw new IOException("download " + url + ": unexpected status: " + response.status());
        return response.bytes(10 * 1024 * 1024);
      }
    }
    throw new IOException("Native image storage reference is not supported");
  }

  public void close() {
    http.close();
  }
}
