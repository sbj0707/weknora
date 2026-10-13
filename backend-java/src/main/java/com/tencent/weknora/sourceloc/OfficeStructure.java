package com.tencent.weknora.sourceloc;

import com.tencent.weknora.sourceloc.SourceAlignment.Unit;
import java.io.*;
import java.util.*;
import javax.xml.stream.XMLStreamConstants;

/** Original Office/EPUB structural units, independent of converted Markdown. */
public final class OfficeStructure {
  static final int MAX_UNITS = 200000;

  private OfficeStructure() {}

  /** Unported behavior is distinct from a damaged supported document. */
  public static class UnsupportedCapability extends IOException {
    public UnsupportedCapability(String message) {
      super(message);
    }
  }

  public static String normalizeType(String type) {
    String ft = GoSourceText.trim(type);
    return (ft.startsWith(".") ? ft.substring(1) : ft).toLowerCase(Locale.ROOT);
  }

  public static boolean supports(String type) {
    return Set.of("docx", "docm", "pptx", "pptm", "xlsx", "xlsm", "csv", "epub")
        .contains(normalizeType(type));
  }

  public static List<Unit> units(String type, byte[] bytes) throws IOException {
    return switch (normalizeType(type)) {
      case "docx", "docm" -> docx(new OfficeArchive(bytes));
      case "pptx", "pptm" -> pptx(new OfficeArchive(bytes));
      case "epub" -> epub(new OfficeArchive(bytes));
      case "csv" -> SourceAlignment.csvUnits(bytes);
      case "xlsx", "xlsm" -> OfficeSpreadsheet.units(bytes);
      default -> List.of();
    };
  }

  public static List<com.tencent.weknora.docreader.DocumentReadResult.SourceBlock> align(
      String type, byte[] bytes, String markdown) throws IOException {
    return SourceAlignment.align(markdown, units(type, bytes));
  }

  private static List<Unit> docx(OfficeArchive zip) throws IOException {
    byte[] document = zip.read("word/document.xml");
    var units = new ArrayList<Unit>();
    int depth = 0, block = 0, tableDepth = 0, skipText = 0;
    boolean inBody = false, inText = false;
    var text = new StringBuilder();
    try (var xml = new OfficeXml(document)) {
      OfficeXml.Token token;
      while ((token = xml.next()) != null) {
        String name = token.name();
        if (token.event() == XMLStreamConstants.START_ELEMENT) {
          if (!inBody) {
            if (name.equals("body")) inBody = true;
            continue;
          }
          if (Set.of("sdt", "sdtContent", "sdtPr", "sdtEndPr").contains(name)) {
            if (name.equals("sdtPr") || name.equals("sdtEndPr")) skipText++;
            continue;
          }
          depth++;
          if (depth == 1 && (name.equals("p") || name.equals("tbl"))) {
            block++;
            text.setLength(0);
          }
          switch (name) {
            case "tbl" -> tableDepth++;
            case "t" -> inText = true;
            case "tab", "br", "cr" -> text.append(' ');
            case "delText", "instrText" -> skipText++;
          }
        } else if (token.event() == XMLStreamConstants.END_ELEMENT) {
          if (!inBody) continue;
          if (name.equals("body")) {
            inBody = false;
            continue;
          }
          if (Set.of("sdt", "sdtContent", "sdtPr", "sdtEndPr").contains(name)) {
            if (name.equals("sdtPr") || name.equals("sdtEndPr")) skipText--;
            continue;
          }
          switch (name) {
            case "t" -> inText = false;
            case "delText", "instrText" -> skipText--;
            case "tr" -> {
              if (tableDepth == 1) flushDoc(units, text, block);
            }
            case "tc" -> text.append(' ');
            case "tbl" -> tableDepth--;
          }
          if (depth == 1 && name.equals("p") && tableDepth == 0) flushDoc(units, text, block);
          depth--;
        } else if (token.event() == XMLStreamConstants.CHARACTERS && inText && skipText == 0)
          text.append(token.text());
      }
    } catch (IOException ignored) {
      /* Go retains completed units when XML becomes malformed. */
    }
    return List.copyOf(units);
  }

  private static void flushDoc(List<Unit> units, StringBuilder text, int block) {
    String value = GoSourceText.trim(text.toString());
    text.setLength(0);
    if (!value.isEmpty() && units.size() < MAX_UNITS)
      units.add(new Unit(value, Map.of("type", "docx", "block", block)));
  }

  private record Relationship(String type, String target) {}

  private static Map<String, Relationship> relations(OfficeArchive zip, String name)
      throws IOException {
    var out = new LinkedHashMap<String, Relationship>();
    int depth = 0;
    try (var xml = new OfficeXml(zip.read(name))) {
      OfficeXml.Token token;
      while ((token = xml.next()) != null) {
        if (token.event() == XMLStreamConstants.START_ELEMENT) {
          depth++;
          if (depth == 2 && token.name().equals("Relationship"))
            out.put(
                token.attribute("Id"),
                new Relationship(token.attribute("Type"), token.attribute("Target")));
        } else if (token.event() == XMLStreamConstants.END_ELEMENT) depth--;
      }
    }
    return out;
  }

  private static List<Unit> pptx(OfficeArchive zip) throws IOException {
    var slides = new ArrayList<String>();
    var stack = new ArrayDeque<String>();
    try (var xml = new OfficeXml(zip.read("ppt/presentation.xml"))) {
      OfficeXml.Token token;
      while ((token = xml.next()) != null) {
        if (token.event() == XMLStreamConstants.START_ELEMENT) {
          if (stack.size() == 2 && stack.peek().equals("sldIdLst") && token.name().equals("sldId"))
            slides.add(
                token.qualifiedAttribute(
                    "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"));
          stack.push(token.name());
        } else if (token.event() == XMLStreamConstants.END_ELEMENT) stack.pop();
      }
    }
    var rels = relations(zip, "ppt/_rels/presentation.xml.rels");
    var units = new ArrayList<Unit>();
    int slide = 0;
    for (String rid : slides) {
      slide++;
      var rel = rels.get(rid);
      if (rel == null) continue;
      String part = resolve("ppt", rel.target);
      byte[] doc;
      try {
        doc = zip.read(part);
      } catch (IOException ignored) {
        continue;
      }
      var locator = Map.<String, Object>of("type", "slide", "slide", slide);
      for (String paragraph : drawing(doc)) units.add(new Unit(paragraph, locator));
      try {
        for (var note : relations(zip, relsPath(part)).values())
          if (note.type.endsWith("/notesSlide")) {
            try {
              for (String paragraph : drawing(zip.read(resolve(directory(part), note.target))))
                units.add(new Unit(paragraph, locator));
            } catch (IOException ignored) {
            }
          }
      } catch (IOException ignored) {
      }
      if (units.size() >= MAX_UNITS) break;
    }
    return List.copyOf(units);
  }

  private static List<String> drawing(byte[] data) {
    var out = new ArrayList<String>();
    var text = new StringBuilder();
    boolean inText = false;
    try (var xml = new OfficeXml(data)) {
      OfficeXml.Token token;
      while ((token = xml.next()) != null) {
        if (token.event() == XMLStreamConstants.START_ELEMENT) {
          if (token.name().equals("t")) inText = true;
          else if (token.name().equals("br")) text.append(' ');
        } else if (token.event() == XMLStreamConstants.END_ELEMENT) {
          if (token.name().equals("t")) inText = false;
          else if (token.name().equals("p")) {
            String value = GoSourceText.trim(text.toString());
            if (!value.isEmpty()) out.add(value);
            text.setLength(0);
          }
        } else if (inText && token.event() == XMLStreamConstants.CHARACTERS)
          text.append(token.text());
      }
    } catch (IOException ignored) {
    }
    return out;
  }

  private static List<Unit> epub(OfficeArchive zip) throws IOException {
    String packagePart = null;
    var stack = new ArrayDeque<String>();
    try (var xml = new OfficeXml(zip.read("META-INF/container.xml"))) {
      OfficeXml.Token token;
      while ((token = xml.next()) != null) {
        if (token.event() == XMLStreamConstants.START_ELEMENT) {
          if (stack.size() == 2
              && stack.peek().equals("rootfiles")
              && token.name().equals("rootfile")
              && packagePart == null) packagePart = token.attribute("full-path");
          stack.push(token.name());
        } else if (token.event() == XMLStreamConstants.END_ELEMENT) stack.pop();
      }
    } catch (IOException error) {
      throw new IOException("epub: no rootfile");
    }
    if (packagePart == null) throw new IOException("epub: no rootfile");
    var manifest = new LinkedHashMap<String, String>();
    var spine = new ArrayList<String>();
    stack.clear();
    try (var xml = new OfficeXml(zip.read(packagePart))) {
      OfficeXml.Token token;
      while ((token = xml.next()) != null) {
        if (token.event() == XMLStreamConstants.START_ELEMENT) {
          if (stack.size() == 2 && stack.peek().equals("manifest") && token.name().equals("item"))
            manifest.put(token.attribute("id"), token.attribute("href"));
          if (stack.size() == 2 && stack.peek().equals("spine") && token.name().equals("itemref"))
            spine.add(token.attribute("idref"));
          stack.push(token.name());
        } else if (token.event() == XMLStreamConstants.END_ELEMENT) stack.pop();
      }
    }
    var units = new ArrayList<Unit>();
    int section = 0;
    for (String id : spine) {
      section++;
      String href = manifest.get(id);
      if (href == null) continue;
      int fragment = href.indexOf('#');
      if (fragment >= 0) href = href.substring(0, fragment);
      byte[] doc;
      try {
        doc = zip.read(resolve(directory(packagePart), href));
      } catch (IOException ignored) {
        continue;
      }
      var parsed = OfficeHtml.blocks(doc);
      var locator = new LinkedHashMap<String, Object>();
      locator.put("type", "section");
      locator.put("section", section);
      if (!parsed.title().isEmpty()) locator.put("title", parsed.title());
      for (String block : parsed.blocks()) units.add(new Unit(block, locator));
      if (units.size() >= MAX_UNITS) break;
    }
    return List.copyOf(units);
  }

  static String directory(String part) {
    int slash = part.lastIndexOf('/');
    return slash < 0 ? "." : part.substring(0, slash);
  }

  static String relsPath(String part) {
    int slash = part.lastIndexOf('/');
    String base = slash < 0 ? part : part.substring(slash + 1);
    return resolve(directory(part), "_rels/" + base + ".rels");
  }

  static String resolve(String dir, String target) {
    String path = target.startsWith("/") ? target : dir + "/" + target;
    boolean root = path.startsWith("/");
    var stack = new ArrayList<String>();
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".")) continue;
      if (segment.equals("..")) {
        if (!stack.isEmpty() && !stack.getLast().equals("..")) stack.removeLast();
        else if (!root) stack.add(segment);
      } else stack.add(segment);
    }
    String result = String.join("/", stack);
    return result.isEmpty() ? (root ? "" : ".") : result;
  }
}
