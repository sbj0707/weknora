package com.tencent.weknora.retrieval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import java.math.*;
import java.util.*;

/**
 * The production shared reranking stage: passage budget, threshold degradation, composite scoring
 * and MMR.
 */
public final class RerankStage {
  public record Options(
      double threshold,
      int topK,
      double fallbackMinScore,
      int maxCandidates,
      double faqScoreBoost) {}

  public record Outcome(
      List<ObjectNode> results,
      List<Integer> indices,
      ObjectNode diagnostics,
      List<ObjectNode> candidates,
      List<String> passages,
      List<RerankModel.Result> modelScores,
      List<ObjectNode> scored) {}

  private static final ObjectMapper JSON = new ObjectMapper();
  private final GoJiebaSegmenter jieba;

  public RerankStage(GoJiebaSegmenter jieba) {
    this.jieba = jieba;
  }

  public Outcome run(
      CallContext call, RerankModel model, String query, List<ObjectNode> rows, Options opts) {
    ObjectNode diag =
        JSON.createObjectNode()
            .put("applied", false)
            .put("outcome", "")
            .put("threshold", opts.threshold)
            .put("effective_threshold", opts.threshold)
            .put("top_score", 0d)
            .put("candidate_count", 0)
            .put("result_count", 0);
    List<ObjectNode> candidates = new ArrayList<>();
    List<Integer> candidateIdx = new ArrayList<>();
    List<String> passages = new ArrayList<>();
    Set<Integer> keep = topByScore(rows, opts.maxCandidates);
    for (int i = 0; i < rows.size(); i++) {
      ObjectNode row = rows.get(i);
      if (row == null || keep != null && !keep.contains(i)) continue;
      String passage = RerankPassage.model(row);
      if (HybridText.trim(passage).isEmpty()) continue;
      candidates.add(row);
      candidateIdx.add(i);
      passages.add(passage);
    }
    diag.put("candidate_count", candidates.size());
    if (candidates.isEmpty()) {
      diag.put("outcome", "no_candidates");
      return out(List.of(), List.of(), diag, candidates, passages, List.of(), List.of());
    }
    int limit = model.maxPassageRunes(query);
    if (limit > 0)
      for (int i = 0; i < passages.size(); i++) {
        String passage = passages.get(i);
        if (passage.codePointCount(0, passage.length()) > limit)
          passages.set(i, passage.substring(0, passage.offsetByCodePoints(0, limit)));
      }
    List<RerankModel.Result> scores;
    try {
      scores = model.rerank(call, query, passages);
    } catch (ModelClientException ex) {
      diag.put("outcome", "model_error")
          .put("error", ex.getMessage())
          .put("result_count", rows.size());
      List<Integer> indices = new ArrayList<>();
      for (int i = 0; i < rows.size(); i++) indices.add(i);
      return out(rows, indices, diag, candidates, passages, List.of(), List.of());
    }
    List<RerankModel.Result> valid = new ArrayList<>();
    for (var score : scores)
      if (score.index() >= 0 && score.index() < candidates.size()) valid.add(score);
    diag.put("applied", true);
    RerankModel.Result best = best(valid);
    if (best != null) diag.put("top_score", best.relevanceScore());
    double threshold = opts.threshold;
    List<RerankModel.Result> passing = filter(valid, threshold);
    String outcome = "ok";
    if (passing.isEmpty()) {
      if (threshold > .3) {
        threshold = Math.max(threshold * .7, .3);
        passing = filter(valid, threshold);
        if (!passing.isEmpty()) outcome = "threshold_degraded";
      }
      if (passing.isEmpty()) {
        if (best != null && best.relevanceScore() >= opts.fallbackMinScore) {
          passing = List.of(best);
          outcome = "fallback_top1";
        } else outcome = "all_below_threshold";
      }
    }
    diag.put("outcome", outcome).put("effective_threshold", threshold);
    record Scored(ObjectNode row, int index) {}
    List<Scored> indexed = new ArrayList<>();
    for (var score : passing)
      indexed.add(
          new Scored(
              scoredCopy(candidates.get(score.index()), score.relevanceScore(), opts.faqScoreBoost),
              candidateIdx.get(score.index())));
    indexed.sort(
        (a, b) -> {
          double x = a.row.path("score").asDouble(), y = b.row.path("score").asDouble();
          if (x != y) return x > y ? -1 : 1;
          return Double.compare(preBoost(b.row), preBoost(a.row));
        });
    List<ObjectNode> scored = new ArrayList<>();
    for (var item : indexed) scored.add(item.row);
    List<Integer> picks =
        opts.topK > 0 ? mmr(scored, Math.min(opts.topK, scored.size()), .7) : new ArrayList<>();
    if (opts.topK <= 0) for (int i = 0; i < scored.size(); i++) picks.add(i);
    List<ObjectNode> result = new ArrayList<>();
    List<Integer> indices = new ArrayList<>();
    for (int pick : picks) {
      result.add(scored.get(pick));
      indices.add(indexed.get(pick).index);
    }
    diag.put("result_count", result.size());
    return out(result, indices, diag, candidates, passages, valid, scored);
  }

  private static Outcome out(
      List<ObjectNode> r,
      List<Integer> i,
      ObjectNode d,
      List<ObjectNode> c,
      List<String> p,
      List<RerankModel.Result> m,
      List<ObjectNode> s) {
    return new Outcome(r, i, d, c, p, m, s);
  }

  private static Set<Integer> topByScore(List<ObjectNode> rows, int limit) {
    if (limit <= 0 || rows.size() <= limit) return null;
    List<Integer> order = new ArrayList<>();
    Set<Integer> keep = new HashSet<>();
    for (int i = 0; i < rows.size(); i++) {
      ObjectNode r = rows.get(i);
      if (r == null) continue;
      if (r.path("match_type").asInt() == 6) keep.add(i);
      else order.add(i);
    }
    if (order.size() <= limit) return null;
    order.sort(
        (a, b) ->
            Double.compare(
                rows.get(b).path("score").asDouble(), rows.get(a).path("score").asDouble()));
    keep.addAll(order.subList(0, limit));
    return keep;
  }

  private static RerankModel.Result best(List<RerankModel.Result> scores) {
    RerankModel.Result best = null;
    for (var row : scores)
      if (best == null || row.relevanceScore() > best.relevanceScore()) best = row;
    return best;
  }

  private static List<RerankModel.Result> filter(
      List<RerankModel.Result> scores, double threshold) {
    List<RerankModel.Result> result = new ArrayList<>();
    for (var row : scores) if (row.relevanceScore() >= threshold) result.add(row);
    return result;
  }

  static double composite(ObjectNode row, double model, double base) {
    if (row.path("match_type").asInt() == 6) base = model;
    double weight = row.path("knowledge_source").asText().equalsIgnoreCase("web_search") ? .95 : 1;
    // Matches Go CompositeScore's explicit, architecture-independent fused operations.
    return Math.min(Math.max(Math.fma(.1, weight, Math.fma(.3, base, .6 * model)), 0), 1);
  }

  private static ObjectNode scoredCopy(ObjectNode row, double model, double boost) {
    ObjectNode copy = row.deepCopy();
    ObjectNode metadata =
        copy.path("metadata").isObject()
            ? (ObjectNode) copy.get("metadata")
            : JSON.createObjectNode();
    copy.set("metadata", metadata);
    double base = row.path("score").asDouble(), score = composite(row, model, base);
    metadata
        .put("base_score", fixed(base))
        .put("model_score", fixed(model))
        .put("composite_score", fixed(score));
    if (boost > 1 && row.path("chunk_type").asText().equals("faq")) {
      metadata.put("faq_boosted", "true").put("faq_original_score", fixed(score));
      score = Math.min(score * boost, 1);
    }
    copy.put("score", score);
    return copy;
  }

  private static String fixed(double value) {
    BigDecimal rounded = new BigDecimal(value).setScale(4, RoundingMode.HALF_EVEN);
    String text = rounded.toPlainString();
    return rounded.signum() == 0 && Double.doubleToRawLongBits(value) < 0 ? "-" + text : text;
  }

  static double preBoost(ObjectNode row) {
    String value = row.path("metadata").path("composite_score").asText();
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException ex) {
      return row.path("score").asDouble();
    }
  }

  public List<Integer> mmr(List<ObjectNode> rows, int count, double lambda) {
    if (count <= 0 || rows.isEmpty()) return List.of();
    List<Integer> remaining = new ArrayList<>(), selected = new ArrayList<>();
    List<Set<String>> tokens = new ArrayList<>();
    double[] redundancy = new double[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      remaining.add(i);
      tokens.add(HybridText.tokens(RerankPassage.enriched(rows.get(i)), jieba));
    }
    while (selected.size() < count && !remaining.isEmpty()) {
      int best = 0;
      double high = Double.NEGATIVE_INFINITY;
      for (int pos = 0; pos < remaining.size(); pos++) {
        int i = remaining.get(pos);
        double score = lambda * rows.get(i).path("score").asDouble() - (1 - lambda) * redundancy[i];
        if (score > high) {
          high = score;
          best = pos;
        }
      }
      int chosen = remaining.remove(best);
      selected.add(chosen);
      for (int i : remaining)
        redundancy[i] =
            Math.max(redundancy[i], HybridText.jaccard(tokens.get(i), tokens.get(chosen)));
    }
    return selected;
  }
}
