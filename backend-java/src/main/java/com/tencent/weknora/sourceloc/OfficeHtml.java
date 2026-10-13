package com.tencent.weknora.sourceloc;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Token-order HTML block text, without DOM repair or reordering. */
final class OfficeHtml {
  record Blocks(String title, List<String> blocks) {}

  static Blocks blocks(byte[] data) {
    String html = new String(data, StandardCharsets.UTF_8);
    var output = new ArrayList<String>();
    var text = new StringBuilder();
    String title = "";
    boolean heading = false;
    int skip = 0, at = 0;
    String raw = null;
    while (at < html.length()) {
      if (raw != null) {
        int end = raw.equals("plaintext") ? -1 : rawEnd(html, at, raw);
        String value = html.substring(at, end < 0 ? html.length() : end);
        if (skip == 0)
          text.append(
                  decoded(
                      value,
                      !Set.of(
                              "script",
                              "style",
                              "xmp",
                              "iframe",
                              "noembed",
                              "noframes",
                              "plaintext")
                          .contains(raw)))
              .append(' ');
        at = end < 0 ? html.length() : end;
        raw = null;
        continue;
      }
      if (html.startsWith("<!--", at)) {
        int end = html.indexOf("-->", at + 4), bang = html.indexOf("--!>", at + 4);
        if (bang >= 0 && (end < 0 || bang < end)) {
          at = bang + 4;
        } else at = end < 0 ? html.length() : end + 3;
        continue;
      }
      char current = html.charAt(at);
      boolean endTag = current == '<' && at + 1 < html.length() && html.charAt(at + 1) == '/';
      int nameAt = at + (endTag ? 2 : 1);
      if (current == '<' && nameAt < html.length() && asciiLetter(html.charAt(nameAt))) {
        int p = nameAt;
        while (p < html.length()
            && !asciiWhitespace(html.charAt(p))
            && html.charAt(p) != '/'
            && html.charAt(p) != '>') p++;
        String tag = html.substring(nameAt, p).toLowerCase(Locale.ROOT);
        char quote = 0;
        int close = p;
        for (; close < html.length(); close++) {
          char ch = html.charAt(close);
          if (quote != 0) {
            if (ch == quote) quote = 0;
          } else if (ch == '\'' || ch == '"') quote = ch;
          else if (ch == '>') break;
        }
        if (close == html.length()) {
          at = html.length();
          break;
        }
        boolean selfClose = close > at && html.charAt(close - 1) == '/';
        if (Set.of("script", "style", "head").contains(tag)) {
          if (!endTag && !selfClose) skip++;
          else if (endTag && skip > 0) skip--;
        } else if (Set.of(
                "p",
                "div",
                "li",
                "tr",
                "h1",
                "h2",
                "h3",
                "h4",
                "h5",
                "h6",
                "blockquote",
                "pre",
                "section",
                "article",
                "table",
                "dt",
                "dd",
                "br",
                "figcaption")
            .contains(tag)) {
          String value = fields(text.toString());
          if (!value.isEmpty()) {
            output.add(value);
            if (heading && title.isEmpty()) title = value;
          }
          text.setLength(0);
          if (tag.length() == 2 && tag.charAt(0) == 'h') heading = !endTag && !selfClose;
        }
        at = close + 1;
        if (!endTag
            && Set.of(
                    "script",
                    "style",
                    "title",
                    "textarea",
                    "xmp",
                    "iframe",
                    "noembed",
                    "noframes",
                    "plaintext")
                .contains(tag)) raw = tag;
        continue;
      }
      if (current == '<'
          && at + 1 < html.length()
          && (html.charAt(at + 1) == '!' || html.charAt(at + 1) == '?')) {
        int end = html.indexOf('>', at + 2);
        at = end < 0 ? html.length() : end + 1;
        continue;
      }
      int end = at + 1;
      while (end < html.length() && html.charAt(end) != '<') end++;
      if (skip == 0) text.append(decoded(html.substring(at, end), true)).append(' ');
      at = end;
    }
    String value = fields(text.toString());
    if (!value.isEmpty()) {
      output.add(value);
      if (heading && title.isEmpty()) title = value;
    }
    return new Blocks(title, List.copyOf(output));
  }

  private static int rawEnd(String html, int from, String tag) {
    for (int at = html.indexOf("</", from); at >= 0; at = html.indexOf("</", at + 2)) {
      int end = at + 2 + tag.length();
      if (end > html.length()) continue;
      boolean match = true;
      for (int i = 0; i < tag.length(); i++) {
        char cp = html.charAt(at + 2 + i);
        if (cp >= 'A' && cp <= 'Z') cp = (char) (cp + 32);
        if (cp != tag.charAt(i)) {
          match = false;
          break;
        }
      }
      if (match
          && (end == html.length()
              || asciiWhitespace(html.charAt(end))
              || html.charAt(end) == '/'
              || html.charAt(end) == '>')) return at;
    }
    return -1;
  }

  private static boolean asciiWhitespace(char cp) {
    return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || cp == '\f';
  }

  private static String decoded(String text, boolean entities) {
    text = text.replace("\r\n", "\n").replace('\r', '\n');
    return entities ? GoSourceText.unescape(text) : text;
  }

  private static String fields(String text) {
    var out = new StringBuilder();
    boolean space = false;
    for (int cp : text.codePoints().toArray()) {
      if (GoSourceText.trim(new String(Character.toChars(cp))).isEmpty()) {
        space = !out.isEmpty();
        continue;
      }
      if (space) {
        out.append(' ');
        space = false;
      }
      out.appendCodePoint(cp);
    }
    return out.toString();
  }

  private static boolean asciiLetter(char cp) {
    return cp >= 'a' && cp <= 'z' || cp >= 'A' && cp <= 'Z';
  }
}
