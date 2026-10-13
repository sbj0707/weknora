package com.tencent.weknora.sourceloc;

import java.nio.charset.StandardCharsets;
import java.util.*;

// Port of xuri/nfp v0.0.2-0.20250530014748-2ddeb826f9a9. BSD 3-Clause.
// Copyright (c) 2022-2025 Ri Xu. See META-INF/licenses/NFP-LICENSE.txt.
final class ExcelNumberFormatParser {
  static final String ALIGN = "Alignment",
      CONDITION = "Condition",
      CURRENCY = "CurrencyLanguage",
      DATE = "DateTimes",
      DECIMAL = "DecimalPoint",
      DENOM = "Denominator",
      DIGITAL = "DigitalPlaceHolder",
      ELAPSED = "ElapsedDateTimes",
      EXP = "Exponential",
      FRACTION = "Fraction",
      GENERAL = "General",
      HASH = "HashPlaceHolder",
      LITERAL = "Literal",
      PERCENT = "Percent",
      REPEAT = "RepeatsChar",
      SWITCH = "SwitchArgument",
      TEXT = "TextPlaceHolder",
      THOUSANDS = "ThousandsSeparator",
      ZERO = "ZeroPlaceHolder";
  static final List<String> AMPM = List.of("AM/PM", "A/P", "上午/下午");
  static final Set<String> COLORS =
      Set.of("black", "blue", "cyan", "green", "magenta", "red", "white", "yellow");
  static final List<String> SWITCHES =
      List.of(
          "AIUEO",
          "ALPHABETIC",
          "alphabetic",
          "Arabic",
          "ARABICABJAD",
          "ARABICALPHA",
          "ArabicDash",
          "BAHTTEXT",
          "CardText",
          "CHINESENUM1",
          "CHINESENUM2",
          "CHINESENUM3",
          "CHOSUNG",
          "CIRCLENUM",
          "DBCHAR",
          "DBNUM1",
          "DBNUM2",
          "DBNUM3",
          "DBNUM4",
          "DollarText",
          "GANADA",
          "GB1",
          "GB2",
          "GB3",
          "GB4",
          "HEBREW1",
          "HEBREW2",
          "Hex",
          "HINDIARABIC",
          "HINDICARDTEXT",
          "HINDILETTER1",
          "HINDILETTER2",
          "IROHA",
          "KANJINUM1",
          "KANJINUM2",
          "KANJINUM3",
          "Ordinal",
          "OrdText",
          "Roman",
          "roman",
          "SBCHAR",
          "THAIARABIC",
          "THAICARDTEXT",
          "THAILETTER",
          "VIETCARDTEXT",
          "ZODIAC1",
          "ZODIAC2",
          "ZODIAC3");
  static final Set<String> OPERATORS = Set.of("<", "<=", ">", ">=", "<>", "=");
  static final String DATE_CHARS = "ABDEGHMRSY", NUM_CHARS = "0123456789";
  static final List<String> SECTION_TYPES = List.of("Positive", "Negative", "Zero", "Text");

  record Token(String value, String type, List<Part> parts) {
    Token {
      parts = List.copyOf(parts);
    }
  }

  record Part(Token token, String value) {}

  static final class Section {
    String type;
    final List<Token> items = new ArrayList<>();

    Section(String type) {
      this.type = type;
    }
  }

  private static final class MutableToken {
    String value = "", type = "";
    final List<MutableToken> parts = new ArrayList<>();

    MutableToken() {}

    MutableToken(String type) {
      this.type = type;
    }

    MutableToken(String type, String value) {
      this.type = type;
      this.value = value;
    }

    Token freeze() {
      return new Token(value, type, parts.stream().map(p -> new Part(p.freeze(), "")).toList());
    }
  }

  private int[] runes;
  private int offset, section;
  private boolean inBracket, inString;
  private MutableToken token = new MutableToken();
  private final List<Section> sections = new ArrayList<>();

  static List<Section> parse(String format) {
    return new ExcelNumberFormatParser().run(format);
  }

  private String current() {
    return new String(Character.toChars(runes[offset]));
  }

  private String next() {
    return offset + 1 < runes.length ? new String(Character.toChars(runes[offset + 1])) : "";
  }

  private String two() {
    return offset + 1 < runes.length ? new String(runes, offset, 2) : "";
  }

  static String upper(String value) {
    var out = new StringBuilder();
    value.codePoints().forEach(cp -> out.appendCodePoint(Character.toUpperCase(cp)));
    return out.toString();
  }

  private boolean contains(String set, String value) {
    return value.codePoints().anyMatch(cp -> set.indexOf(cp) >= 0);
  }

  private boolean folded(Collection<String> values, String value) {
    return values.stream().anyMatch(v -> v.equalsIgnoreCase(value));
  }

  private void add(Token value) {
    while (sections.size() <= section) sections.add(new Section(SECTION_TYPES.get(section)));
    sections.get(section).items.add(value);
  }

  private void add() {
    add(token.freeze());
  }

  private void add(String value, String type) {
    add(new Token(value, type, token.freeze().parts()));
  }

  private void reset() {
    token = new MutableToken();
  }

  private String pattern(Collection<String> values) {
    for (String value : values) {
      int count = value.getBytes(StandardCharsets.UTF_8).length;
      if (offset + count <= runes.length) {
        String candidate = new String(runes, offset, count);
        if (candidate.equalsIgnoreCase(value)) return candidate;
      }
    }
    return null;
  }

  private List<Section> run(String format) {
    runes = GoSourceText.trim(format).codePoints().toArray();
    while (offset < runes.length) {
      String c = current(), next = next();
      if (inBracket) {
        if (token.type.equals(CURRENCY)) {
          if (!c.equals("-") && !c.equals("]")) {
            if (token.parts.isEmpty()) token.parts.add(new MutableToken("CurrencyString"));
            token.parts.getLast().value += c;
          }
          if (c.equals("-") && !next.equals("]")) {
            int size = token.parts.size();
            if (size == 0) token.parts.add(new MutableToken("LanguageInfo"));
            else if (!token.parts.getFirst().type.equals("LanguageInfo")
                && !token.parts.getFirst().value.isEmpty())
              token.parts.add(new MutableToken("LanguageInfo"));
            else token.parts.getLast().value += c;
          }
          if (c.equals(",")) {
            token.value += c;
            offset++;
            continue;
          }
        }
        if (token.value.length() > 1
            && OPERATORS.contains(token.value.substring(1))
            && (c.equals("-") || contains(NUM_CHARS, c))) {
          token.type = CONDITION;
          token.parts.clear();
          token.parts.add(new MutableToken("Operator", token.value.substring(1)));
          token.parts.add(new MutableToken("Operand"));
          token.value = c;
          offset++;
          continue;
        }
        if (c.equals("]")) {
          inBracket = false;
          if (token.type.equals(CONDITION) && token.parts.size() == 2) {
            token.parts.get(1).value = token.value;
            add(token.parts.get(0).value + token.parts.get(1).value, token.type);
            reset();
            offset++;
            continue;
          }
          token.value += c;
          if (token.value.getBytes(StandardCharsets.UTF_8).length > 2) {
            byte[] rawToken = token.value.getBytes(StandardCharsets.UTF_8);
            String literal = new String(rawToken, 1, rawToken.length - 2, StandardCharsets.UTF_8);
            if (folded(COLORS, literal)) {
              add(literal, "Color");
              reset();
              offset++;
              continue;
            }
            if (folded(SWITCHES, literal)) {
              add(token.value, SWITCH);
              reset();
              offset++;
              continue;
            }
            if (token.type.equals(CURRENCY)) {
              add();
              reset();
              offset++;
              continue;
            }
            token.type = "Unknown";
            token.value = literal;
            boolean date =
                literal
                    .codePoints()
                    .allMatch(cp -> contains(DATE_CHARS, upper(new String(Character.toChars(cp)))));
            if (date) token.type = ELAPSED;
            add();
            reset();
            offset++;
            continue;
          }
        }
      }
      if (!inBracket) {
        if (contains(NUM_CHARS, c)) {
          if (token.type.equals(ZERO) || token.type.equals(DENOM)) {
            token.value += c;
            offset++;
            continue;
          }
          if (token.type.equals(FRACTION)) {
            add();
            token = new MutableToken(DENOM, c);
            offset++;
            continue;
          }
          if (upper(token.value).equals("B")) {
            if (c.equals("1") || c.equals("2")) {
              add(token.value + c, token.type);
              reset();
              offset++;
              continue;
            }
            token.type = LITERAL;
            token.value += c;
            offset++;
            continue;
          }
          if (!token.type.isEmpty()) {
            add();
            reset();
          }
          token.type = c.equals("0") ? ZERO : LITERAL;
          token.value += c;
          offset++;
          continue;
        }
        if (c.equals("#")) {
          if (!token.type.equals(HASH) && !token.type.isEmpty()) {
            if (token.value.equals(".")) token.type = DECIMAL;
            add();
            reset();
          }
          token.type = HASH;
          token.value += c;
          offset++;
          continue;
        }
        if (c.equals(".")) {
          if (token.type.equals(ZERO) || token.type.equals(HASH)) {
            add();
            add(c, DECIMAL);
            reset();
            offset++;
            continue;
          }
          if (token.type.equals(DATE) && !contains(NUM_CHARS, next)) {
            add();
            add(c, LITERAL);
            reset();
            offset++;
            continue;
          }
          if (!inString) {
            if (!token.type.isEmpty() && contains(NUM_CHARS, next)) {
              add();
              reset();
            }
            token.type = DECIMAL;
          }
          token.value += c;
          offset++;
          continue;
        }
      }
      if (contains("$-+(): ", c)) {
        if (inBracket) {
          token.value += c;
          token.type = CURRENCY;
          offset++;
          continue;
        }
        if (!token.type.equals(LITERAL) && !token.type.equals(DATE) && !token.type.isEmpty()) {
          add();
          token = new MutableToken(LITERAL, c);
          offset++;
          continue;
        }
        if (!token.value.equals("\\") && token.type.isEmpty() && !folded(AMPM, token.value))
          token.type = LITERAL;
        if (token.type.equals(LITERAL)) {
          token.value += c;
          offset++;
          continue;
        }
      }
      if (c.equals("_")) {
        if (!token.type.isEmpty()) add();
        token.value = " ";
        token.type = ALIGN;
        offset += 2;
        continue;
      }
      if (c.equals("*")) {
        if (!token.value.isEmpty()) {
          add();
          reset();
        }
        add(next, REPEAT);
        reset();
        offset += 2;
        continue;
      }
      if (c.equals("\\")) {
        if (!token.value.isEmpty()) {
          add();
          reset();
        }
        add(next, LITERAL);
        reset();
        offset += 2;
        continue;
      }
      if (c.equals("-")) {
        if (!token.type.isEmpty()) add();
        token.type = LITERAL;
        if (!c.equals(next)) add(c, token.type);
        reset();
        offset++;
        continue;
      }
      if (c.equals(",")) {
        if (token.type.equals(ZERO) || token.type.equals(HASH)) {
          add();
          add(c, THOUSANDS);
          reset();
          offset++;
          continue;
        }
        if (!inString) {
          if (token.type.equals(LITERAL)) {
            add();
            token = new MutableToken(THOUSANDS);
          }
          if (token.type.equals(DATE)) {
            add();
            token = new MutableToken(LITERAL);
          }
          if (!c.equals(next)) {
            if (token.type.isEmpty()) token.type = LITERAL;
            add(c, token.type);
          }
          reset();
          offset++;
          continue;
        }
        token.type = LITERAL;
        token.value += c;
        offset++;
        continue;
      }
      if (c.equals(" ")) {
        if (!token.type.isEmpty() && !token.type.equals(LITERAL)) add();
        token.type = LITERAL;
        add(c, token.type);
        reset();
        offset++;
        continue;
      }
      if (c.equals("/")) {
        if (token.type.equals(DATE)) {
          add();
          add(c, LITERAL);
          reset();
          offset++;
          continue;
        }
        if (token.type.equals(DIGITAL)) {
          add();
          token = new MutableToken(FRACTION, c);
          offset++;
          continue;
        }
      }
      if (c.equals(":") && token.type.equals(DATE)) {
        add();
        add(c, LITERAL);
        reset();
        offset++;
        continue;
      }
      if (c.equals("\"")) {
        offset++;
        if (inString) {
          add(token.value, LITERAL);
          reset();
          inString = false;
          continue;
        }
        if (!token.value.isEmpty()) add();
        inString = true;
        token = new MutableToken(LITERAL);
        continue;
      }
      if (c.equals("@")) {
        if (sections.size() <= section) sections.add(new Section("Text"));
        sections.get(section).type = "Text";
        if (!token.type.isEmpty() && !inBracket) add();
        token = new MutableToken(TEXT, c);
        offset++;
        continue;
      }
      if (c.equals("[")) {
        if (!token.type.isEmpty() && !inBracket) {
          add();
          reset();
        }
        inBracket = true;
        token.value += c;
        offset++;
        continue;
      }
      if (c.equals("?")) {
        if (!token.type.isEmpty() && !token.type.equals(DIGITAL)) {
          add();
          reset();
        }
        token.type = DIGITAL;
        token.value += c;
        offset++;
        continue;
      }
      if (c.equals("%")) {
        if (!token.type.isEmpty()) {
          add();
          reset();
        }
        token.type = PERCENT;
        token.value += c;
        offset++;
        continue;
      }
      if (c.equals(";")) {
        if (!token.type.isEmpty()) add();
        if (sections.size() <= section) sections.add(new Section(SECTION_TYPES.get(section)));
        section++;
        if (section > 3) return List.of();
        reset();
        sections.add(new Section(SECTION_TYPES.get(section)));
        offset++;
        continue;
      }
      if (two().equalsIgnoreCase("E+")) {
        if (!token.type.isEmpty()) {
          add();
          reset();
        }
        token.type = EXP;
        token.value += two();
        offset += 2;
        continue;
      }
      String matched = pattern(AMPM);
      if (matched != null) {
        add(matched, DATE);
        reset();
        offset += matched.getBytes(StandardCharsets.UTF_8).length;
        continue;
      }
      matched = pattern(List.of(GENERAL));
      if (matched != null) {
        add(matched, GENERAL);
        reset();
        offset += matched.getBytes(StandardCharsets.UTF_8).length;
        continue;
      }
      if (!inBracket && !inString) {
        if (contains(DATE_CHARS, upper(c))) {
          if (folded(AMPM, token.value)) {
            token.type = DATE;
            add();
            reset();
          }
          if (token.type.equals(LITERAL) || token.type.equals(DATE) && !contains(token.value, c)) {
            add();
            reset();
          }
          if (!token.type.isEmpty() && !token.type.equals(DATE)) {
            add();
            reset();
          }
          token.type = DATE;
          token.value += c;
          offset++;
          continue;
        }
        if (contains(DATE_CHARS, upper(token.value))) {
          add(token.value, DATE);
          token = new MutableToken(LITERAL, c);
          offset++;
          continue;
        }
        if (contains(DATE_CHARS, upper(next))) {
          token.value += c;
          token.type = LITERAL;
          offset++;
          continue;
        }
        if (c.equals("'")) {
          offset++;
          continue;
        }
        if (!contains(NUM_CHARS, c) && token.type.equals(ZERO)) {
          add();
          reset();
          continue;
        }
        token.type = LITERAL;
      }
      token.value += c;
      if (folded(AMPM, token.value)) {
        token.type = DATE;
        add();
        reset();
        offset++;
        continue;
      }
      offset++;
    }
    if (!token.value.isEmpty()) {
      add(new Token(token.value, token.type.isEmpty() ? LITERAL : token.type, List.of()));
    }
    return List.copyOf(sections);
  }
}
