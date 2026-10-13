// Portions ported from Excelize v2.11.0; BSD 3-Clause.
// Copyright (c) 2016-2026 The excelize Authors; (c) 2011-2017 Geoffrey J. Teale.
// See META-INF/licenses/EXCELIZE-LICENSE.txt for the complete notice and disclaimer.
package com.tencent.weknora.sourceloc;

import java.io.*;
import java.util.*;
import javax.xml.stream.XMLStreamConstants;

/** Workbook order, shared/rich strings, sparse row numbers and cached formula values. */
final class OfficeSpreadsheet {
  private record Sheet(String name, String id) {}

  static List<SourceAlignment.Unit> units(byte[] bytes) throws IOException {
    var zip = new OfficeArchive(bytes);
    zip.verifySpreadsheetCapacity();
    var sheets = new ArrayList<Sheet>();
    boolean date1904 = false;
    var stack = new ArrayDeque<String>();
    try (var xml = new OfficeXml(zip.read("xl/workbook.xml"))) {
      OfficeXml.Token token;
      while ((token = xml.next()) != null) {
        if (token.event() == XMLStreamConstants.START_ELEMENT) {
          if (token.name().equals("workbookPr"))
            date1904 = Set.of("1", "true", "TRUE", "True").contains(token.attribute("date1904"));
          if (stack.size() == 2 && stack.peek().equals("sheets") && token.name().equals("sheet"))
            sheets.add(
                new Sheet(
                    token.attribute("name"),
                    token.qualifiedAttribute(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                        "id")));
          stack.push(token.name());
        } else if (token.event() == XMLStreamConstants.END_ELEMENT) stack.pop();
      }
    }
    var rels = new LinkedHashMap<String, String>();
    try (var xml = new OfficeXml(zip.read("xl/_rels/workbook.xml.rels"))) {
      OfficeXml.Token token;
      while ((token = xml.next()) != null)
        if (token.event() == XMLStreamConstants.START_ELEMENT
            && token.name().equals("Relationship"))
          rels.put(token.attribute("Id"), OfficeStructure.resolve("xl", token.attribute("Target")));
    }
    List<String> shared = List.of();
    if (zip.contains("xl/sharedStrings.xml")) {
      try {
        shared = sharedStrings(zip.read("xl/sharedStrings.xml"));
      } catch (IOException invalid) {
        return List.of();
      }
    }
    var formats = new ArrayList<Long>();
    var custom = new LinkedHashMap<Long, String>();
    byte[] styles = zip.contains("xl/styles.xml") ? zip.read("xl/styles.xml") : new byte[0];
    if (!GoSourceText.trim(new String(styles, java.nio.charset.StandardCharsets.UTF_8)).isEmpty())
      try (var xml = new OfficeXml(styles)) {
        OfficeXml.Token token;
        stack.clear();
        while ((token = xml.next()) != null) {
          if (token.event() == XMLStreamConstants.START_ELEMENT) {
            if (stack.isEmpty()
                && (!token.name().equals("styleSheet")
                    || !spreadsheetNamespace(token.namespaceUri())))
              throw new IOException("Invalid Excel style root");
            if (stack.size() == 2 && stack.peek().equals("cellXfs") && token.name().equals("xf"))
              formats.add(styleInteger(token.attribute("numFmtId")));
            if (stack.size() == 2
                && stack.peek().equals("numFmts")
                && token.name().equals("numFmt")) {
              String override =
                  token.qualifiedAttribute(
                      "http://schemas.microsoft.com/office/spreadsheetml/2015/02/main",
                      "formatCode16");
              custom.putIfAbsent(
                  styleInteger(token.attribute("numFmtId")),
                  override.isEmpty() ? token.attribute("formatCode") : override);
            }
            stack.push(token.name());
          } else if (token.event() == XMLStreamConstants.END_ELEMENT) stack.pop();
        }
      }
    var units = new ArrayList<SourceAlignment.Unit>();
    for (var sheet : sheets) {
      String path = rels.get(sheet.id);
      if (path == null) continue;
      byte[] doc;
      try {
        doc = zip.read(path);
      } catch (IOException ignored) {
        continue;
      }
      readSheet(doc, sheet.name, date1904, shared, formats, custom, units);
      if (units.size() >= OfficeStructure.MAX_UNITS) break;
    }
    return List.copyOf(units);
  }

  private static List<String> sharedStrings(byte[] bytes) throws IOException {
    var out = new ArrayList<String>();
    try (var xml = new OfficeXml(bytes)) {
      OfficeXml.Token token;
      boolean root = false;
      while ((token = xml.next()) != null) {
        if (token.event() == XMLStreamConstants.START_ELEMENT && !root) {
          root = true;
          if (!token.name().equals("sst") || !spreadsheetNamespace(token.namespaceUri()))
            throw new IOException("Invalid Excel shared string namespace");
        }
        if (token.event() == XMLStreamConstants.START_ELEMENT && token.name().equals("si"))
          out.add(readRich(xml, "si"));
      }
    }
    return out;
  }

  private static String readRich(OfficeXml xml, String closing) throws IOException {
    var plain = new StringBuilder();
    var runs = new StringBuilder();
    var stack = new ArrayDeque<String>();
    stack.push(closing);
    boolean text = false, run = false;
    OfficeXml.Token token;
    while ((token = xml.next()) != null) {
      if (token.event() == XMLStreamConstants.START_ELEMENT) {
        if (token.name().equals("t")) {
          text = stack.size() == 1 || stack.size() == 2 && stack.peek().equals("r");
          run = stack.size() == 2;
        }
        stack.push(token.name());
      } else if (token.event() == XMLStreamConstants.END_ELEMENT) {
        if (token.name().equals("t")) text = false;
        stack.pop();
        if (stack.isEmpty()) break;
      } else if (text && token.event() == XMLStreamConstants.CHARACTERS)
        (run ? runs : plain).append(token.text());
    }
    return basicString(plain.toString() + runs);
  }

  private record Cell(
      String type, String raw, String inline, int style, int column, boolean formula) {}

  private static Cell cell(OfficeXml xml, OfficeXml.Token start, int previous) throws IOException {
    String reference = start.attribute("r");
    int column = reference.isEmpty() ? previous + 1 : column(reference);
    String raw = "", inline = "";
    boolean formula = false;
    OfficeXml.Token token;
    while ((token = xml.next()) != null) {
      if (token.event() == XMLStreamConstants.START_ELEMENT) {
        switch (token.name()) {
          case "v" -> raw = elementText(xml);
          case "f" -> {
            formula = true;
            elementText(xml);
          }
          case "is" -> inline = readRich(xml, "is");
        }
      } else if (token.event() == XMLStreamConstants.END_ELEMENT && token.name().equals("c")) break;
    }
    return new Cell(
        start.attribute("t"), raw, inline, integer(start.attribute("s"), 0), column, formula);
  }

  private static String elementText(OfficeXml xml) throws IOException {
    int depth = 1;
    var out = new StringBuilder();
    OfficeXml.Token token;
    while ((token = xml.next()) != null) {
      if (token.event() == XMLStreamConstants.START_ELEMENT) depth++;
      else if (token.event() == XMLStreamConstants.END_ELEMENT) {
        if (--depth == 0) break;
      } else if (token.event() == XMLStreamConstants.CHARACTERS) out.append(token.text());
    }
    return out.toString();
  }

  private static void readSheet(
      byte[] bytes,
      String name,
      boolean date1904,
      List<String> shared,
      List<Long> formats,
      Map<Long, String> custom,
      List<SourceAlignment.Unit> units)
      throws IOException {
    int row = 0, column = 0;
    List<String> cells = new ArrayList<>();
    boolean inRow = false;
    try (var xml = new OfficeXml(bytes)) {
      OfficeXml.Token token;
      while ((token = xml.next()) != null && units.size() < OfficeStructure.MAX_UNITS) {
        if (token.event() == XMLStreamConstants.START_ELEMENT) {
          if (token.name().equals("row")) {
            row = integer(token.attribute("r"), row + 1);
            column = 0;
            cells.clear();
            inRow = true;
          } else if (inRow && token.name().equals("c")) {
            Cell cell = cell(xml, token, column);
            column = cell.column;
            String value = value(cell, shared, formats, custom, date1904);
            if (!value.isEmpty() || cell.formula) {
              while (cells.size() + 1 < column) cells.add("");
              cells.add(value);
            }
          }
        } else if (token.event() == XMLStreamConstants.END_ELEMENT && token.name().equals("row")) {
          String text = GoSourceText.trim(String.join(" ", cells));
          if (!text.isEmpty())
            units.add(
                new SourceAlignment.Unit(
                    text,
                    Map.of("type", "sheet", "sheet", name, "row_start", row, "row_end", row)));
          inRow = false;
        }
      }
    } catch (IOException error) {
      if (error instanceof UnsupportedFormat)
        throw error; /* Go stops this sheet on malformed row data. */
    }
  }

  private static String value(
      Cell cell,
      List<String> shared,
      List<Long> formats,
      Map<Long, String> custom,
      boolean date1904)
      throws IOException {
    String value = cell.raw;
    boolean numeric = false;
    int category = 6;
    switch (cell.type) {
      case "b":
        {
          category = 1;
          if (value.equals("1")) return "TRUE";
          if (value.equals("0")) return "FALSE";
          break;
        }
      case "s":
        {
          category = 7;
          if (!value.isEmpty()) {
            int id = integer(GoSourceText.trim(value), 0);
            if (id < 0 || id >= shared.size()) return "";
            value = shared.get(id);
          }
          break;
        }
      case "str":
        return value;
      case "inlineStr":
        category = 5;
        value = cell.inline;
        break;
      case "d":
        category = 2;
        value = isoDate(value);
        break;
      default:
        numeric = true;
    }
    if (numeric) value = OfficeNumberFormat.canonical(value);
    if (cell.style <= 0 || cell.style >= formats.size()) return value;
    long id = formats.get(cell.style);
    String code = custom.get(id);
    if (code == null) {
      var builtin = ExcelNumberFormat.TABLES.path("builtin").path(Long.toString(id));
      if (builtin.isMissingNode()) return value;
      code = builtin.asText();
    }
    try {
      return ExcelNumberFormat.format(value, code, date1904, category, null);
    } catch (ExcelNumberFormat.NonTerminatingFraction
        | ExcelNumberFormat.FractionIterationCapacity error) {
      throw new UnsupportedFormat(error.getMessage());
    }
  }

  private static String isoDate(String value) {
    try {
      String normalized = value.replace(',', '.');
      java.time.LocalDateTime date;
      if (normalized.endsWith("Z")) {
        String pattern = normalized.contains("-") ? "uuuu-MM-dd'T'HH:mm:ss" : "uuuuMMdd'T'HHmmss";
        var formatter =
            new java.time.format.DateTimeFormatterBuilder()
                .appendPattern(pattern)
                .optionalStart()
                .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true)
                .optionalEnd()
                .appendLiteral('Z')
                .toFormatter()
                .withResolverStyle(java.time.format.ResolverStyle.STRICT);
        date = java.time.LocalDateTime.parse(normalized, formatter);
      } else if (normalized.contains("-")) {
        date =
            java.time.LocalDateTime.parse(
                normalized,
                java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss'Z'")
                    .withResolverStyle(java.time.format.ResolverStyle.STRICT));
      } else {
        var formatter =
            new java.time.format.DateTimeFormatterBuilder()
                .appendPattern("uuuuMMdd'T'HHmmss")
                .optionalStart()
                .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND, 0, 9, true)
                .optionalEnd()
                .toFormatter()
                .withResolverStyle(java.time.format.ResolverStyle.STRICT);
        date = java.time.LocalDateTime.parse(normalized, formatter);
      }
      var base = java.time.LocalDate.of(1899, 12, 31).atStartOfDay();
      if (date.isBefore(base)) return "0";
      var duration = java.time.Duration.between(base, date);
      double serial = duration.getSeconds() / 86400d + duration.getNano() / 86400000000000d;
      if (!date.isBefore(java.time.LocalDate.of(1900, 3, 1).atStartOfDay())) serial++;
      return OfficeNumberFormat.goPrecision15(serial);
    } catch (java.time.DateTimeException error) {
      return value;
    }
  }

  private static int column(String reference) throws IOException {
    int column = 0, at = 0;
    while (at < reference.length() && Character.isLetter(reference.charAt(at))) {
      char letter = Character.toUpperCase(reference.charAt(at++));
      if (letter < 'A' || letter > 'Z') throw new IOException("Invalid Excel cell reference");
      column = column * 26 + letter - 'A' + 1;
    }
    if (column <= 0
        || column > 16384
        || at >= reference.length()
        || !reference.substring(at).matches("[0-9]+"))
      throw new IOException("Invalid Excel cell reference");
    return column;
  }

  private static boolean spreadsheetNamespace(String uri) {
    return uri.equals("http://schemas.openxmlformats.org/spreadsheetml/2006/main")
        || uri.equals("http://purl.oclc.org/ooxml/spreadsheetml/main");
  }

  private static long styleInteger(String value) throws IOException {
    value = GoSourceText.trim(value);
    if (value.isEmpty()) return 0;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IOException("Invalid Excel number format ID", e);
    }
  }

  private static int integer(String text, int fallback) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException error) {
      return fallback;
    }
  }

  static String basicString(String value) {
    var pattern = java.util.regex.Pattern.compile("_x([a-fA-F0-9]{4})_");
    var matcher = pattern.matcher(value);
    var out = new StringBuilder();
    int cursor = 0;
    while (matcher.find()) {
      out.append(value, cursor, matcher.start());
      int cp = Integer.parseInt(matcher.group(1), 16);
      if (cp < 0xd800 || cp > 0xdfff) out.append((char) cp);
      cursor = matcher.end();
    }
    return out.append(value, cursor, value.length()).toString();
  }

  static final class UnsupportedFormat extends OfficeStructure.UnsupportedCapability {
    UnsupportedFormat(String message) {
      super(message);
    }
  }
}
