package com.tencent.weknora.retrieval;

import java.util.*;

/** Production per-list BM25 rescaling, highest-score deduplication and weighted RRF. */
public final class HybridFusion {
  public record Config(int k, double vectorWeight, double keywordWeight) {
    int effectiveK() {
      return k <= 0 ? 60 : k;
    }

    double vector() {
      return vectorWeight <= 0 ? .7 : vectorWeight;
    }

    double keyword() {
      return keywordWeight <= 0 ? .3 : keywordWeight;
    }
  }

  public static int normalizeMatchCount(long requested) {
    return requested <= 0 ? 50 : (int) Math.min(requested, 500);
  }

  public static int retrievalPool(int requested, int knowledgeBaseCount) {
    return (int)
        Math.min((long) Math.max(normalizeMatchCount(requested) * 5, 50) * knowledgeBaseCount, 500);
  }

  public static List<RetrievalHit> fuse(
      List<List<RetrievalHit>> vectors, List<List<RetrievalHit>> keywords, Config configured) {
    if (keywords.isEmpty()) return deduplicate(flatten(vectors));
    if (vectors.isEmpty()) {
      List<RetrievalHit> all = new ArrayList<>();
      for (List<RetrievalHit> list : keywords) all.addAll(rescale(list));
      return deduplicate(all);
    }
    Config config = configured == null ? new Config(60, .7, .3) : configured;
    Map<String, Integer> vr = bestRanks(vectors), kr = bestRanks(keywords);
    Map<String, RetrievalHit> info = highest(flatten(vectors));
    for (RetrievalHit row : flatten(keywords)) info.putIfAbsent(row.chunkId(), row);
    List<RetrievalHit> out = new ArrayList<>();
    double maximum = (config.vector() + config.keyword()) / (config.effectiveK() + 1.0);
    for (var item : info.entrySet()) {
      double score = 0;
      Integer v = vr.get(item.getKey()), k = kr.get(item.getKey());
      if (v != null) score += config.vector() / (config.effectiveK() + v);
      if (k != null) score += config.keyword() / (config.effectiveK() + k);
      out.add(scored(item.getValue(), score / maximum));
    }
    out.sort(HybridFusion::compare);
    return out;
  }

  static List<RetrievalHit> rescale(List<RetrievalHit> rows) {
    double maximum = 0;
    for (RetrievalHit row : rows)
      if (row != null && Double.isFinite(row.score()) && row.score() > maximum)
        maximum = row.score();
    if (maximum <= 1) return List.copyOf(rows);
    List<RetrievalHit> result = new ArrayList<>();
    for (RetrievalHit row : rows) {
      if (row == null) {
        result.add(null);
        continue;
      }
      double score = row.score();
      score =
          Double.isNaN(score) || score == Double.NEGATIVE_INFINITY || score <= 0
              ? 0
              : score == Double.POSITIVE_INFINITY ? 1 : score / maximum;
      result.add(scored(row, score));
    }
    return result;
  }

  private static List<RetrievalHit> flatten(List<List<RetrievalHit>> lists) {
    List<RetrievalHit> all = new ArrayList<>();
    for (var list : lists) all.addAll(list);
    return all;
  }

  private static Map<String, RetrievalHit> highest(List<RetrievalHit> rows) {
    Map<String, RetrievalHit> byId = new LinkedHashMap<>();
    for (RetrievalHit row : rows) {
      RetrievalHit old = byId.get(row.chunkId());
      if (old == null || row.score() > old.score()) byId.put(row.chunkId(), row);
    }
    return byId;
  }

  static List<RetrievalHit> deduplicate(List<RetrievalHit> rows) {
    List<RetrievalHit> result = new ArrayList<>(highest(rows).values());
    result.sort(HybridFusion::compare);
    return result;
  }

  private static Map<String, Integer> bestRanks(List<List<RetrievalHit>> lists) {
    Map<String, Integer> ranks = new HashMap<>();
    for (var list : lists) {
      List<RetrievalHit> unique = deduplicate(list);
      for (int i = 0; i < unique.size(); i++)
        ranks.merge(unique.get(i).chunkId(), i + 1, Math::min);
    }
    return ranks;
  }

  private static int compare(RetrievalHit a, RetrievalHit b) {
    return a.score() > b.score() ? -1 : a.score() < b.score() ? 1 : java.util.Arrays.compareUnsigned(a.chunkId().getBytes(java.nio.charset.StandardCharsets.UTF_8), b.chunkId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static RetrievalHit scored(RetrievalHit r, double score) {
    return new RetrievalHit(
        r.id(),
        r.content(),
        r.sourceId(),
        r.sourceType(),
        r.chunkId(),
        r.knowledgeId(),
        r.knowledgeBaseId(),
        r.tagId(),
        score,
        r.matchType());
  }
}
