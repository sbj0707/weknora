package com.tencent.weknora.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.tomcat.util.http.fileupload.*;

/** Streaming multipart parsing after KB authorization, without enabling Spring's eager parser. */
public final class KnowledgeMultipartUpload implements AutoCloseable {
  private final Path directory;
  private Path file;
  private String filename;
  private long size;
  private final Map<String, String> fields = new LinkedHashMap<>();

  private KnowledgeMultipartUpload(Path directory) {
    this.directory = directory;
  }

  public String filename() {
    return filename;
  }

  public long size() {
    return size;
  }

  public Map<String, String> fields() {
    return Collections.unmodifiableMap(fields);
  }

  public InputStream open() throws IOException {
    return Files.newInputStream(file);
  }

  public byte[] bytes() throws IOException {
    return Files.readAllBytes(file);
  }

  public static KnowledgeMultipartUpload read(HttpServletRequest request, long maxSizeMB)
      throws IOException {
    long maxBytes;
    try {
      maxBytes = Math.multiplyExact(maxSizeMB, 1L << 20);
    } catch (ArithmeticException error) {
      maxBytes = Long.MAX_VALUE - (1L << 20);
    }
    long bodyLimit = maxBytes + (1L << 20);
    var result = new KnowledgeMultipartUpload(Files.createTempDirectory("weknora-upload-"));
    FileUpload parser = new FileUpload();
    parser.setHeaderEncoding("UTF-8");
    parser.setFileCountMax(1000);
    parser.setPartHeaderSizeMax(1 << 20);
    InputStream body = new LimitedBody(request.getInputStream(), bodyLimit);
    try {
      FileItemIterator parts =
          parser.getItemIterator(
              new UploadContext() {
                public long contentLength() {
                  return request.getContentLengthLong();
                }

                public String getCharacterEncoding() {
                  return "UTF-8";
                }

                public String getContentType() {
                  return request.getContentType();
                }

                public InputStream getInputStream() {
                  return body;
                }
              });
      while (parts.hasNext()) {
        FileItemStream item = parts.next();
        try (InputStream content = item.openStream()) {
          if (item.isFormField()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            content.transferTo(bytes);
            result.fields.putIfAbsent(
                item.getFieldName(), new String(bytes.toByteArray(), StandardCharsets.UTF_8));
          } else if (result.file == null
              && item.getFieldName().equals("file")
              && !Objects.toString(item.getName(), "").isEmpty()) {
            String original = item.getName();
            result.filename = original.substring(original.lastIndexOf('/') + 1);
            result.file = Files.createTempFile(result.directory, "file-", ".upload");
            try (OutputStream output = Files.newOutputStream(result.file)) {
              result.size = content.transferTo(output);
            }
          } else content.transferTo(OutputStream.nullOutputStream());
        }
      }
      if (result.file == null)
        throw KnowledgeUploadForms.bad("File upload failed", "http: no such file");
      if (result.size > maxBytes) throw tooLarge(maxSizeMB);
      return result;
    } catch (IOException error) {
      result.close();
      if (exceedsBodyLimit(error)) throw tooLarge(maxSizeMB);
      throw KnowledgeUploadForms.bad(
          "File upload failed", multipartDetails(error, request.getContentType()));
    } catch (RuntimeException error) {
      result.close();
      throw error;
    }
  }

  private static String multipartDetails(Exception error, String type) {
    if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))
      return "request Content-Type isn't multipart/form-data";
    if (!type.toLowerCase(Locale.ROOT).contains("boundary="))
      return "no multipart boundary param in Content-Type";
    return error.getMessage();
  }

  private static boolean exceedsBodyLimit(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof BodyLimit) return true;
    return false;
  }

  private static RuntimeException tooLarge(long mb) {
    return KnowledgeUploadForms.bad("文件大小不能超过" + mb + "MB", null);
  }

  @Override
  public void close() throws IOException {
    if (file != null) Files.deleteIfExists(file);
    Files.deleteIfExists(directory);
  }

  private static final class BodyLimit extends IOException {
    BodyLimit() {
      super("http: request body too large");
    }
  }

  private static final class LimitedBody extends FilterInputStream {
    private long remaining;

    LimitedBody(InputStream input, long max) {
      super(input);
      remaining = max;
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value < 0) return -1;
      if (remaining-- <= 0) throw new BodyLimit();
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = in.read(bytes, offset, (int) Math.min(length, Math.max(1, remaining + 1)));
      if (read > 0 && (remaining -= read) < 0) throw new BodyLimit();
      return read;
    }

    @Override
    public long skip(long count) throws IOException {
      byte[] bytes = new byte[8192];
      long skipped = 0;
      while (skipped < count) {
        int n = read(bytes, 0, (int) Math.min(bytes.length, count - skipped));
        if (n < 0) break;
        skipped += n;
      }
      return skipped;
    }
  }
}
