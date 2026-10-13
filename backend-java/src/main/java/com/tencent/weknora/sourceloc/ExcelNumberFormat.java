// Ported from Excelize v2.11.0 numfmt.go; BSD 3-Clause.
// Copyright (c) 2016-2026 The excelize Authors; (c) 2011-2017 Geoffrey J. Teale.
// See META-INF/licenses/EXCELIZE-LICENSE.txt. Locale tables derive from the same source.
package com.tencent.weknora.sourceloc;

import static com.tencent.weknora.sourceloc.ExcelNumberFormatParser.*;

import com.fasterxml.jackson.databind.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

final class ExcelNumberFormat {
  static final class NonTerminatingFraction extends RuntimeException {
    NonTerminatingFraction() {
      super("Go Excelize fraction formatting does not terminate for this value");
    }
  }

  static final class FractionIterationCapacity extends RuntimeException {
    FractionIterationCapacity() {
      super("Native Excel fraction iteration capacity exceeded (4096 steps)");
    }
  }

  record Options(String culture, String longDate, String longTime) {
    static final Options DEFAULT = new Options("", "", "");
  }

  static final JsonNode TABLES = loadTables();

  private static JsonNode loadTables() {
    try (var in =
        ExcelNumberFormat.class.getResourceAsStream(
            "/META-INF/excelize-number-format-tables.json")) {
      if (in == null) throw new IllegalStateException("Excel locale tables absent");
      return new ObjectMapper().readTree(in);
    } catch (java.io.IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static String format(String value, String code, boolean date1904, int category, Options options) {
    return new ExcelNumberFormat(value, code, date1904, category, options).run();
  }

  private String value, localCode = "", currencyString = "", switchArgument = "", ap = "";
  private final boolean date1904;
  private final int category;
  private final Options opts;
  private final List<Section> sections;
  private List<Token> tokens = List.of();
  private double number;
  private boolean usePositive,
      isNumeric,
      usePointer,
      useScientific,
      useComma,
      useFraction,
      useMillisecond,
      useGannen;
  private int intHolder,
      intPadding,
      fracHolder,
      fracPadding,
      expBaseLen,
      percent,
      scalingFactor,
      scalingStart;
  private ExcelCalendarTime time;
  private StringBuilder result;

  private ExcelNumberFormat(
      String value, String code, boolean date1904, int category, Options opts) {
    this.value = value;
    this.sections = parse(code);
    this.date1904 = date1904;
    this.category = category;
    this.opts = opts == null ? Options.DEFAULT : opts;
  }

  private String run() {
    Double numeric = numeric(value);
    String type = "Text";
    if ((category == 6 || category == 2) && numeric != null) {
      number = numeric;
      isNumeric = true;
      if (number >= 0) type = "Positive";
      else {
        boolean negative = sections.stream().anyMatch(s -> s.type.equals("Negative"));
        type = negative ? "Negative" : "Positive";
        usePositive = !negative;
      }
    }
    for (var s : sections)
      if (s.type.equals(type)) {
        tokens = s.items;
        return alignment(isNumeric ? (type.equals("Positive") ? positive() : negative()) : text());
      }
    return value;
  }

  // Go CellTypeNumber=6 and CellTypeDate=2 (checked by actual enum fixture).
  static Double numeric(String raw) {
    if (raw.contains("_")) return null;
    String l = raw.toLowerCase(Locale.ROOT);
    if (l.equals("nan")) return Double.NaN;
    if (Set.of("inf", "+inf", "infinity", "+infinity").contains(l)) return Double.POSITIVE_INFINITY;
    if (Set.of("-inf", "-infinity").contains(l)) return Double.NEGATIVE_INFINITY;
    if (!(raw.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        || raw.matches("[+-]?0[xX][0-9a-fA-F.]+[pP][+-]?[0-9]+"))) return null;
    try {
      double n = Double.parseDouble(raw);
      return Double.isFinite(n) ? n : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String shortest(double n) {
    if (Double.isNaN(n)) return "NaN";
    if (n == Double.POSITIVE_INFINITY) return "+Inf";
    if (n == Double.NEGATIVE_INFINITY) return "-Inf";
    if (n == 0) return Double.doubleToRawLongBits(n) < 0 ? "-0" : "0";
    return BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
  }

  static int precision(double n) {
    String s = shortest(n);
    return s.length() - (s.contains(".") ? 1 : 0);
  }

  private static int length(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }

  private boolean in(String key, String type) {
    for (JsonNode n : TABLES.path(key)) if (n.asText().equalsIgnoreCase(type)) return true;
    return false;
  }

  private String alignment(String s) {
    if (tokens.isEmpty()) return s;
    return (tokens.getFirst().type().equals(ALIGN) ? " " : "")
        + s
        + (tokens.getLast().type().equals(ALIGN) ? " " : "");
  }

  private String positive() {
    boolean fmtNum = false;
    for (Token token : tokens) {
      String type = token.type();
      if (type.equals(GENERAL)) {
        if (precision(number) > 11) return general(number, 10);
        return value;
      }
      if (in("number_tokens", type)) fmtNum = true;
      if (in("date_tokens", type)) {
        if (fmtNum || number < 0) return value;
        boolean seenDate = false;
        for (Token t : tokens) {
          if (in("date_tokens", t.type())) {
            if (seenDate && useMillisecond) return value;
            seenDate = true;
          }
          if (in("number_tokens", t.type())) {
            if (t.type().equals(ZERO)) {
              useMillisecond = true;
              continue;
            }
            return value;
          }
        }
        return date();
      }
    }
    return number();
  }

  private String negative() {
    for (Token t : tokens)
      if (!in("supported_tokens", t.type())
          || t.type().equals(GENERAL)
          || in("date_tokens", t.type())) return value;
    return number();
  }

  private String text() {
    var out = new StringBuilder();
    for (Token t : tokens) {
      if (t.type().equals(LITERAL)) out.append(t.value());
      if (t.type().equals(TEXT) || t.type().equals(ZERO)) out.append(value);
    }
    return out.toString();
  }

  private void configure() {
    int last = -1;
    for (int i = 0; i < tokens.size(); i++)
      if (Set.of(ZERO, HASH).contains(tokens.get(i).type())) last = i;
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      int len = length(t.value());
      switch (t.type()) {
        case HASH -> {
          if (usePointer) fracHolder += len;
          else intHolder += len;
        }
        case EXP -> useScientific = true;
        case THOUSANDS -> {
          if (i > last) {
            if (scalingFactor == 0) scalingStart = i;
            scalingFactor++;
          } else useComma = true;
        }
        case LITERAL -> {
          if (t.value().equals(",") && i > last && scalingFactor > 0) scalingFactor++;
        }
        case PERCENT -> percent += len;
        case DECIMAL -> usePointer = true;
        case FRACTION -> useFraction = true;
        case SWITCH -> switchArgument = t.value();
        case ZERO -> {
          intHolder = 0;
          if (usePointer) {
            if (useScientific) expBaseLen += len;
            else fracPadding += len;
          } else intPadding += len;
        }
      }
    }
  }

  private String number() {
    configure();
    if (scalingFactor > 0) number /= Math.pow(1000, scalingFactor);
    String[] parts = shortest(Math.abs(number)).split("\\.", -1);
    int intPart = parts[0].length(), fracPart = parts.length == 2 ? parts[1].length() : 0;
    intHolder = Math.min(intHolder, intPart);
    int intLen = Math.max(intPart, intPadding + intHolder), fracLen = fracPart;
    if (fracPart > fracHolder + fracPadding) fracLen = fracHolder + fracPadding;
    if (fracPadding > fracPart) fracLen = fracPadding;
    if (intLen + fracLen > 15 && !useScientific) {
      Double original = numeric(value);
      if (original != null && precision(original) > 15) return printLiteral(big(original, fracLen));
    }
    if (useScientific && expBaseLen != 2) return value;
    double n = number;
    if (percent > 0) n *= Math.pow(100, percent);
    if (useFraction) n = Math.floor(Math.abs(n));
    if (!useScientific) {
      double ratio = Math.pow(10, fracLen);
      n = goRound(n * ratio) / ratio;
    }
    String out =
        useScientific
            ? scientific(Math.abs(n), fracLen)
            : fixed(Math.abs(n), fracLen, intLen + fracLen + (fracLen > 0 ? 1 : 0));
    out += "%".repeat(percent);
    if (useComma) out = comma(out);
    return printLiteral(out);
  }

  private static double goRound(double n) {
    return n < 0 ? -Math.floor(-n + 0.5) : Math.floor(n + 0.5);
  }

  private static String fixed(double n, int digits, int width) {
    if (!Double.isFinite(n)) {
      String s = shortest(n);
      return " ".repeat(Math.max(0, width - s.length())) + s;
    }
    String s = new BigDecimal(n).setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
    return "0".repeat(Math.max(0, width - s.length())) + s;
  }

  private static String scientific(double n, int digits) {
    if (!Double.isFinite(n)) return shortest(n);
    if (n == 0) return (digits == 0 ? "0" : "0." + "0".repeat(digits)) + "E+00";
    BigDecimal x = new BigDecimal(n).round(new MathContext(digits + 1, RoundingMode.HALF_EVEN));
    int exp = x.precision() - x.scale() - 1;
    return x.movePointLeft(exp).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
        + String.format(Locale.ROOT, "E%+03d", exp);
  }

  private static String general(double n, int digits) {
    if (!Double.isFinite(n)) return shortest(n);
    if (n == 0) return shortest(n);
    BigDecimal b =
        new BigDecimal(n)
            .round(new MathContext(digits, RoundingMode.HALF_EVEN))
            .stripTrailingZeros();
    int exp = b.precision() - b.scale() - 1;
    return exp < -4 || exp >= digits
        ? b.movePointLeft(exp).toPlainString() + String.format(Locale.ROOT, "E%+03d", exp)
        : b.toPlainString();
  }

  private String big(double n, int frac) {
    String s = shortest(n * (percent > 0 ? 100 : 1));
    while (s.startsWith("-")) s = s.substring(1);
    if (useComma) s = comma(s);
    if (frac > 0) {
      String[] p = s.split("\\.", -1);
      s =
          p[0]
              + "."
              + (p.length == 2 ? (p[1] + "0".repeat(frac)).substring(0, frac) : "0".repeat(frac));
    }
    return s + (percent > 0 ? "%" : "");
  }

  private static String comma(String text) {
    String[] p = text.split("\\.", -1);
    var out = new StringBuilder();
    for (int i = 0; i < p[0].length(); i++) {
      if (i > 0 && (p[0].length() - i) % 3 == 0) out.append(',');
      out.append(p[0].charAt(i));
    }
    if (p.length == 2) out.append('.').append(p[1]);
    return out.toString();
  }

  private String printLiteral(String text) {
    var out = new StringBuilder(usePositive ? "-" : "");
    int lastNonFraction = Integer.MAX_VALUE;
    boolean appeared = false;
    for (int i = tokens.size() - 1; i >= 0; i--) {
      String type = tokens.get(i).type();
      if (type.equals(FRACTION)) appeared = true;
      else if (appeared && Set.of(HASH, DIGITAL).contains(type)) {
        lastNonFraction = i - 1;
        break;
      }
    }
    int placeholders = 0;
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (Set.of(HASH, ZERO).contains(t.type())
          || t.type().equals(DIGITAL) && lastNonFraction >= 0 && i < lastNonFraction)
        placeholders += length(t.value());
    }
    int consumed = 0, numerator = 0;
    boolean fraction = false;
    double frac = 0;
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      String type = t.type(), v = t.value();
      if (type.equals(CURRENCY)) {
        currency(t);
        out.append(currencyString);
      }
      if (type.equals(LITERAL)) {
        if (!(scalingFactor > 0 && i >= scalingStart && v.equals(","))) out.append(v);
      }
      if (type.equals(DIGITAL) && i > lastNonFraction) {
        if (!fraction) numerator = length(v);
      } else if (Set.of(HASH, ZERO, DIGITAL).contains(type)) {
        int l = length(v);
        if (consumed == 0 && length(text) > placeholders)
          l = length(text) + length(v) - placeholders;
        int start = consumed;
        if (length(text) < placeholders) start += length(text) - placeholders;
        int[] cp = text.codePoints().toArray();
        for (int j = 0; j < l; j++) {
          int k = j + start;
          if (k >= 0 && k < cp.length) out.appendCodePoint(cp[k]);
          else if (type.equals(DIGITAL)) out.append(' ');
        }
        consumed += l;
      }
      if (type.equals(FRACTION)) {
        frac = Math.abs(number - (long) number);
        fraction = true;
      }
      if (fraction) {
        if (type.equals(DIGITAL)) out.append(fraction(frac, numerator, length(v)));
        if (type.equals(DENOM)) {
          Double den = numeric(v);
          if (den == null) den = 0d;
          out.append((long) goRound(frac * den)).append('/').append((long) goRound(den));
        }
      }
    }
    return switched(out.toString());
  }

  private static String fraction(double r, int numHolder, int denHolder) {
    if (!Double.isFinite(r)) throw new NonTerminatingFraction();
    if (denHolder <= 0) return "";
    long limit = (long) Math.pow(10, denHolder),
        p1 = 1,
        q1 = 0,
        p2 = 0,
        q2 = 1,
        lastp = 0,
        lastq = 0,
        num = 0,
        den = 0;
    for (int k = 0; k < 4096; k++) {
      long a = (long) Math.floor(r), p = a * p1 + p2, q = a * q1 + q2;
      p2 = p1;
      q2 = q1;
      p1 = p;
      q1 = q;
      double f = r - a;
      if (q >= limit) {
        num = lastp;
        den = lastq;
        break;
      }
      if (Math.abs(f) < 1e-12) {
        num = p;
        den = q;
        break;
      }
      lastp = p;
      lastq = q;
      r = 1 / f;
      if (k == 4095) throw new FractionIterationCapacity();
    }
    if (num == 0) return " ".repeat(numHolder + denHolder + 1);
    String ns = Long.toString(num), ds = Long.toString(den);
    return " ".repeat(Math.max(0, numHolder - ns.length()))
        + ns
        + "/"
        + ds
        + " ".repeat(Math.max(0, denHolder - ds.length()));
  }

  private String switched(String text) {
    String chars =
        switch (switchArgument) {
          case "" -> null;
          case "[DBNum1]" -> "○一二三四五六七八九";
          case "[DBNum2]" -> "零壹贰叁肆伍陆柒捌玖";
          case "[DBNum3]" -> "０１２３４５６７８９";
          default -> "";
        };
    if (chars == null) return text;
    if (chars.isEmpty()) return value;
    int[] cp = chars.codePoints().toArray();
    var out = new StringBuilder();
    text.codePoints()
        .forEach(
            c -> {
              if (c >= '0' && c <= '9') out.appendCodePoint(cp[c - '0']);
              else out.appendCodePoint(c);
            });
    return out.toString();
  }

  private JsonNode language() {
    return language(localCode);
  }

  private static JsonNode language(String key) {
    String raw = key.length() > 4 ? key.substring(key.length() - 4) : key;
    try {
      JsonNode found = TABLES.path("languages").path(new BigInteger(raw, 16).toString());
      if (!found.isMissingNode()) return found;
    } catch (NumberFormatException e) {
    }
    JsonNode alias = TABLES.path("aliases").path(key);
    if (!alias.isMissingNode()) return alias;
    for (JsonNode n : TABLES.path("languages"))
      for (JsonNode tag : n.path("tags")) if (tag.asText().equalsIgnoreCase(key)) return n;
    return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
  }

  private boolean currency(Token token) {
    for (Part p : token.parts()) {
      Token t = p.token();
      if (!in("supported_tokens", t.type())) return true;
      if (t.type().equals("LanguageInfo")) {
        String lang = t.value();
        if (Set.of("F800", "x-sysdate", "1010000").contains(lang.toUpperCase(Locale.ROOT))
            || lang.equalsIgnoreCase("x-sysdate")) {
          if (!opts.longDate.isEmpty()) {
            value = format(value, opts.longDate, date1904, category, opts);
            return true;
          }
          lang = "409";
        }
        if (lang.equalsIgnoreCase("F400") || lang.equalsIgnoreCase("x-systime")) {
          if (!opts.longTime.isEmpty()) {
            value = format(value, opts.longTime, date1904, category, opts);
            return true;
          }
          lang = "409";
        }
        lang = upper(lang);
        if (language(lang).isMissingNode()) return true;
        localCode = lang;
      }
      if (t.type().equals("CurrencyString")) {
        currencyString = t.value();
        return false;
      }
    }
    return false;
  }

  private String date() {
    time = excelTime(number, date1904);
    if (!useMillisecond && time.getNano() >= 500_000_000) time = time.plusSeconds(1);
    result = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      switch (t.type()) {
        case CURRENCY -> {
          if (currency(t)) return value;
          result.append(currencyString);
        }
        case DATE -> dateToken(i, t);
        case ELAPSED -> elapsed(t);
        case LITERAL -> result.append(t.value());
        case DECIMAL -> result.append('.');
        case SWITCH -> switchArgument = t.value();
        case ZERO -> {
          int count = Math.min(3, length(t.value()));
          result.append(
              String.format(Locale.ROOT, "%03d", time.getNano() / 1_000_000).substring(0, count));
        }
      }
    }
    return switched(result.toString());
  }

  static ExcelCalendarTime excelTime(double value, boolean date1904) {
    long days = ExcelCalendarTime.fromFloat(value);
    if (days <= 61) {
      double jd1 = 2400000.5,
          jd2 = value + (date1904 ? 16480d : 15018d),
          fraction = (jd1 % 1) + (jd2 % 1),
          jd = (jd1 - jd1 % 1) + (jd2 - jd2 % 1);
      if (-.5 < fraction && fraction < .5) fraction += .5;
      else if (fraction >= .5) {
        jd++;
        fraction -= .5;
      } else if (fraction <= -.5) {
        jd--;
        fraction += 1.5;
      }
      long l = ExcelCalendarTime.fromFloat(jd) + 68569, n = 4 * l / 146097;
      l -= (146097 * n + 3) / 4;
      long i = 4000 * (l + 1) / 1461001;
      l = l - 1461 * i / 4 + 31;
      long j = 80 * l / 2447, d = l - 2447 * j / 80;
      l = j / 11;
      long m = j + 2 - 12 * l, y = 100 * (n - 49) + i + l;
      long ns = ExcelCalendarTime.fromFloat(86400000000000d * fraction + 500);
      return ExcelCalendarTime.date(y, m, d, 0, 0, ns / 1000000000, ns % 1000000000 / 1000 * 1000);
    }
    long nanos = ExcelCalendarTime.fromFloat(86400000000000d * (value - days + 1e-9));
    ExcelCalendarTime dt =
        ExcelCalendarTime.date(
                date1904 ? 1904 : 1899, date1904 ? 1 : 12, (date1904 ? 1 : 30) + days, 0, 0, 0, 0)
            .plusNanos(nanos);
    return dt.getNano() / 1000000 > 500
        ? dt.plusSeconds(dt.getNano() >= 500000000 ? 1 : 0).withNano(0)
        : dt.withNano(0);
  }

  private JsonNode langOrEnglish() {
    JsonNode l = language();
    return l.isMissingNode() ? language("409") : l;
  }

  private String monthName(int abbr) {
    ExcelCalendarTime named = time;
    if (number < 1) named = excelTime(number + 2, date1904);
    if (number >= 1 && number < 60) named = excelTime(number + 1, date1904);
    return langOrEnglish()
        .path("months")
        .path(Integer.toString(abbr))
        .get(named.getMonthValue() - 1)
        .asText();
  }

  private void dateToken(int i, Token token) {
    String v = token.value(), u = upper(v);
    int len = length(v);
    if (AMPM.contains(u)) {
      if (ap.isEmpty()) {
        int h = hoursNext(i);
        String[] aps = localAmPm(v).split("/", -1);
        ap = aps[h >= 12 ? 1 : 0];
      }
      result.append(ap);
      return;
    }
    if (u.contains("M")) {
      int m = number < 2 ? 1 : number >= 60 && number < 61 ? 2 : time.getMonthValue();
      if ((len == 1 || len == 2) && isMonth(i)) {
        result.append(len == 1 ? Integer.toString(m) : String.format(Locale.ROOT, "%02d", m));
        return;
      }
      if (len >= 3) {
        result.append(monthName(len == 3 ? 3 : len == 5 ? 5 : 4));
        return;
      }
    }
    years(token);
    days(token);
    hours(i, token);
    if (u.contains("M"))
      result.append(
          len == 1
              ? Integer.toString(time.getMinute())
              : String.format(Locale.ROOT, "%02d", time.getMinute()));
    if (u.contains("S"))
      result.append(
          len == 1
              ? Integer.toString(time.getSecond())
              : String.format(Locale.ROOT, "%02d", time.getSecond()));
  }

  private String localAmPm(String fallback) {
    JsonNode l = language();
    return l.isMissingNode() ? fallback : l.path("ampm").asText();
  }

  private int hoursNext(int i) {
    for (int j = i + 1; j < tokens.size(); j++)
      if (tokens.get(j).type().equals(DATE) && upper(tokens.get(j).value()).contains("H"))
        return excelTime(number, false).getHour();
    return -1;
  }

  private String[] apNext(int i) {
    for (int j = i + 1; j < tokens.size(); j++) {
      Token t = tokens.get(j);
      if (t.type().equals(DATE)) {
        if (upper(t.value()).contains("H")) return null;
        if (AMPM.stream().anyMatch(a -> a.equalsIgnoreCase(t.value())))
          return localAmPm(t.value()).split("/", -1);
      }
    }
    return null;
  }

  private void hours(int i, Token token) {
    if (!upper(token.value()).contains("H")) return;
    int h = time.getHour();
    String[] aps = apNext(i);
    if (aps != null) {
      ap = aps[h >= 12 ? 1 : 0];
      if (h > 12) h -= 12;
    }
    if (!ap.isEmpty()) {
      if (hoursNext(i) == -1 && h > 12) h -= 12;
      if (h == 0) h = 12;
    }
    result.append(
        length(token.value()) == 1 ? Integer.toString(h) : String.format(Locale.ROOT, "%02d", h));
  }

  private boolean isMonth(int i) {
    boolean previous = false, next = false;
    for (int j = i - 1; j >= 0; j--) {
      Token t = tokens.get(j);
      if (t.type().equals(DATE)) {
        String u = upper(t.value());
        previous = u.contains("H") || u.contains("S");
        break;
      }
      if (t.type().equals(ELAPSED)) {
        previous = true;
        break;
      }
    }
    for (int j = i + 1; j < tokens.size(); j++)
      if (tokens.get(j).type().equals(DATE)) {
        next = upper(tokens.get(j).value()).contains("S");
        break;
      }
    return !previous && !next;
  }

  private void years(Token t) {
    String u = upper(t.value());
    int len = length(t.value());
    long year = number < 2 ? 1900 : time.getYear();
    if (u.contains("Y")) {
      if (opts.culture.equals("ko-KR")) year += 2333;
      String s = Long.toString(year);
      result.append(len <= 2 ? s.substring(2) : s);
      return;
    }
    JsonNode info = language();
    boolean roc = opts.culture.equals("zh-TW"), japanese = opts.culture.equals("ja-JP");
    for (JsonNode tag : info.path("tags")) {
      roc |= tag.asText().equalsIgnoreCase("zh-TW");
      japanese |= tag.asText().equalsIgnoreCase("ja-JP");
    }
    if (roc) {
      long era = year - 1912 + 1;
      if (u.contains("G")) {
        if (era == 1) useGannen = info.path("gannen").asBoolean();
        result.append(len < 3 ? "民國" : "中華民國");
        if (era < 0) result.append("前");
      }
      if (u.contains("E")) {
        if (era < 0) era = 1912 - time.getYear();
        if (era == 1 && useGannen) result.append('元');
        else if (len == 1 && !useGannen) result.append(era);
      }
      return;
    }
    if (japanese) {
      int index = 0;
      long era = -1;
      JsonNode starts = TABLES.path("era_starts");
      for (int j = starts.size() - 1; j > 0; j--) {
        LocalDateTime start = OffsetDateTime.parse(starts.get(j).asText()).toLocalDateTime();
        if (time.isAfter(
            ExcelCalendarTime.date(
                start.getYear(), start.getMonthValue(), start.getDayOfMonth(), 0, 0, 0, 0))) {
          index = j;
          era = time.getYear() - start.getYear() + 1;
          break;
        }
      }
      if (u.contains("G") && era != -1) {
        useGannen = info.path("gannen").asBoolean();
        if (len == 1) {
          useGannen = false;
          result.append(TABLES.path("era_symbols").get(index).asText());
        } else {
          String name = TABLES.path("era_names").get(index).asText();
          result.append(len == 2 ? name.substring(0, name.offsetByCodePoints(0, 1)) : name);
        }
      }
      if (u.contains("E")) {
        if (era == -1) result.append(time.getYear());
        else if (era == 1 && useGannen) result.append('元');
        else if (len == 1 && !useGannen) result.append(era);
        else if (len == 2) result.append(String.format(Locale.ROOT, "%02d", era));
      }
      return;
    }
    if (u.contains("E")) result.append(year);
  }

  private void days(Token t) {
    String u = upper(t.value());
    int len = length(t.value()), weekday = time.weekday();
    JsonNode l = langOrEnglish(), names = l.path("weekdays"), abbr = l.path("weekday_abbr");
    if (names.size() != 7) names = language("409").path("weekdays");
    if (abbr.size() != 7) abbr = language("409").path("weekday_abbr");
    if (u.contains("A")) {
      if (len == 3) result.append(abbr.get(weekday).asText());
      if (len > 3) result.append(names.get(weekday).asText());
      return;
    }
    if (u.contains("D")) {
      int d = time.getDayOfMonth();
      if (number < 1) d = 0;
      else if (number < 60) d = excelTime(number + 1, date1904).getDayOfMonth();
      else if (number < 61) d = 29;
      result.append(
          len == 1
              ? Integer.toString(d)
              : len == 2
                  ? String.format(Locale.ROOT, "%02d", d)
                  : (len == 3 ? abbr : names).get(weekday).asText());
    }
  }

  private void elapsed(Token t) {
    String u = upper(t.value());
    long unit =
        u.contains("H")
            ? 3600000000000L
            : u.contains("M") ? 60000000000L : u.contains("S") ? 1000000000L : 0;
    if (unit != 0) result.append(fixed(Math.floor(time.elapsed(unit)), 0, 0));
  }
}
