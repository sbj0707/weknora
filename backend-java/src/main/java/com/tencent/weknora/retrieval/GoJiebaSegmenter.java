package com.tencent.weknora.retrieval;

import com.tencent.weknora.docreader.GoUtf8;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * JNI calls the exact locked gojieba cppjieba algorithm, without a Go runtime or business bridge.
 */
public final class GoJiebaSegmenter implements AutoCloseable {
  private static Path loaded;
  private long handle;

  public GoJiebaSegmenter(Path library, Path dictionaries) {
    Objects.requireNonNull(library, "Jieba native library path");
    Objects.requireNonNull(dictionaries, "Jieba dictionary directory");
    if (!library.isAbsolute() || !Files.isRegularFile(library))
      throw new IllegalArgumentException(
          "Jieba native library must be an existing absolute operator path");
    if (!dictionaries.isAbsolute())
      throw new IllegalArgumentException(
          "Jieba dictionary directory must be an absolute operator path");
    for (String name :
        List.of(
            "jieba.dict.utf8", "hmm_model.utf8", "user.dict.utf8", "idf.utf8", "stop_words.utf8"))
      if (!Files.isRegularFile(dictionaries.resolve(name)))
        throw new IllegalArgumentException("Jieba dictionary files are unavailable");
    synchronized (GoJiebaSegmenter.class) {
      Path path = library.toAbsolutePath().normalize();
      if (loaded == null) {
        System.load(path.toString());
        loaded = path;
      } else if (!loaded.equals(path))
        throw new IllegalArgumentException("Jieba library cannot change within one process");
    }
    handle = create(dictionaries.toString().getBytes(StandardCharsets.UTF_8));
    if (handle == 0) throw new IllegalStateException("Jieba initialization failed");
  }

  public synchronized List<String> cutForSearch(String text) {
    if (handle == 0) throw new IllegalStateException("Jieba segmenter is closed");
    byte[][] words = cut(handle, text.getBytes(StandardCharsets.UTF_8));
    List<String> out = new ArrayList<>(words.length);
    for (byte[] word : words) out.add(GoUtf8.decode(word));
    return out;
  }

  @Override
  public synchronized void close() {
    if (handle != 0) {
      destroy(handle);
      handle = 0;
    }
  }

  private static native long create(byte[] dictionaryDirectory);

  private static native byte[][] cut(long handle, byte[] text);

  private static native void destroy(long handle);
}
