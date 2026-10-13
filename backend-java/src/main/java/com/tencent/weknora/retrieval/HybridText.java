package com.tencent.weknora.retrieval;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.util.*;

final class HybridText {
  private static final Map<Integer, Integer> LOWER = new HashMap<>();

  static {
    try (InputStream in = HybridText.class.getResourceAsStream("/go-source-unicode.json")) {
      for (JsonNode pair : new ObjectMapper().readTree(in).path("lower"))
        LOWER.put(pair.get(0).asInt(), pair.get(1).asInt());
    } catch (IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  static String trim(String value) {
    int start = 0, end = value.length();
    while (start < end && GoIndexUnicode.space(value.codePointAt(start)))
      start += Character.charCount(value.codePointAt(start));
    while (end > start && GoIndexUnicode.space(value.codePointBefore(end)))
      end -= Character.charCount(value.codePointBefore(end));
    return value.substring(start, end);
  }

  static String lower(String value) {
    StringBuilder out = new StringBuilder();
    value.codePoints().forEach(c -> out.appendCodePoint(LOWER.getOrDefault(c, c)));
    return out.toString();
  }

  static List<String> fields(String value) {
    List<String> words = new ArrayList<>();
    int start = -1;
    for (int at = 0; at < value.length(); ) {
      int rune = value.codePointAt(at), next = at + Character.charCount(rune);
      if (GoIndexUnicode.space(rune)) {
        if (start >= 0) {
          words.add(value.substring(start, at));
          start = -1;
        }
      } else if (start < 0) start = at;
      at = next;
    }
    if (start >= 0) words.add(value.substring(start));
    return words;
  }

  static Set<String> tokens(String value, GoJiebaSegmenter segmenter) {
    String text = lower(trim(value));
    if (text.isEmpty()) return Set.of();
    boolean chinese = text.codePoints().anyMatch(GoIndexUnicode::han);
    List<String> words;
    if (chinese) {
      if (segmenter == null)
        throw new IllegalStateException(
            "Native Chinese rerank MMR requires the configured Jieba extension and dictionaries");
      words = segmenter.cutForSearch(text);
    } else words = fields(text);
    Set<String> result = new HashSet<>();
    for (String word : words) {
      word = trim(word);
      if (word.codePointCount(0, word.length()) > 1
          && !word.codePoints()
              .allMatch(
                  c ->
                      GoIndexUnicode.punct(c)
                          || GoIndexUnicode.symbol(c)
                          || GoIndexUnicode.space(c))) result.add(word);
    }
    return result;
  }

  static double jaccard(Set<String> a, Set<String> b) {
    if (a.size() > b.size()) return jaccard(b, a);
    int intersection = 0;
    for (String word : a) if (b.contains(word)) intersection++;
    int union = a.size() + b.size() - intersection;
    return union == 0 ? 0 : (double) intersection / union;
  }
}
