package com.tencent.weknora.knowledge;

import com.google.re2j.Pattern;
import java.util.*;

/** Actual security.CleanMarkdown/ValidateInput patterns; Go regexp's dot excludes newlines. */
final class ManualKnowledgeText {
  private static final List<Pattern> PATTERNS =
      List.of(
              "<script[^>]*>.*?</script>",
              "<iframe[^>]*>.*?</iframe>",
              "<object[^>]*>.*?</object>",
              "<embed[^>]*>.*?</embed>",
              "<embed[^>]*>",
              "<form[^>]*>.*?</form>",
              "<input[^>]*>",
              "<button[^>]*>.*?</button>",
              "javascript:",
              "vbscript:",
              "onload\\s*=",
              "onerror\\s*=",
              "onclick\\s*=",
              "onmouseover\\s*=",
              "onfocus\\s*=",
              "onblur\\s*=")
          .stream()
          .map(p -> Pattern.compile("(?i)" + p))
          .toList();

  private static com.tencent.weknora.security.ApiException validation(String message) {
    return new com.tencent.weknora.security.ApiException(400, "VALIDATION_ERROR", message)
        .withAppCode(1010);
  }

  static String clean(String content) {
    for (var pattern : PATTERNS) content = pattern.matcher(content).replaceAll("");
    return content;
  }

  static String title(String input) {
    if (input.codePoints().anyMatch(cp -> cp < 32 && cp != 9 && cp != 10 && cp != 13)
        || PATTERNS.stream().anyMatch(p -> p.matcher(input).find()))
      throw validation("标题包含非法字符或超出长度限制");
    return KnowledgeUploadForms.goTrim(input);
  }

  static String content(String input) {
    String clean = clean(input);
    if (KnowledgeUploadForms.goTrim(clean).isEmpty()) throw validation("内容不能为空");
    if (clean.codePointCount(0, clean.length()) > 200000) throw validation("内容长度超出限制（最多200000个字符）");
    return clean;
  }

  static String status(String input) {
    String status = KnowledgeUploadForms.goTrim(input).toLowerCase(Locale.ROOT);
    if (status.isEmpty()) status = "draft";
    if (!Set.of("draft", "publish").contains(status)) throw validation("状态仅支持 draft 或 publish");
    return status;
  }

  static String filename(String title) {
    String clean = KnowledgeUploadForms.goTrim(title);
    return clean.toLowerCase(Locale.ROOT).endsWith(".md") ? clean : clean + ".md";
  }
}
