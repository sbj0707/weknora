package com.tencent.weknora.sourceloc;

import java.io.*;
import java.util.*;
import javax.xml.stream.*;

/** Go uses local element names and ignores directives; no DTD/entity or network resolution. */
final class OfficeXml implements AutoCloseable {
  record Token(
      int event,
      String name,
      String text,
      Map<String, String> attributes,
      Map<String, String> namespaces,
      String qualifiedName) {
    String namespaceUri() {
      int colon = qualifiedName.indexOf(':');
      String prefix = colon < 0 ? "" : qualifiedName.substring(0, colon);
      return namespaces.getOrDefault(prefix, "");
    }

    String attribute(String key) {
      String value = "";
      for (var entry : attributes.entrySet())
        if (!entry.getKey().startsWith("xmlns") && local(entry.getKey()).equals(key))
          value = entry.getValue();
      return value;
    }

    String qualifiedAttribute(String uri, String local) {
      for (var a : attributes.entrySet()) {
        int colon = a.getKey().indexOf(':');
        String prefix = colon < 0 ? "" : a.getKey().substring(0, colon);
        String part = colon < 0 ? a.getKey() : a.getKey().substring(colon + 1);
        if (part.equals(local) && namespaces.getOrDefault(prefix, "").equals(uri))
          return a.getValue();
      }
      return "";
    }
  }

  private final XMLStreamReader reader;
  private final Deque<Map<String, String>> namespaces = new ArrayDeque<>();
  private boolean pop;

  OfficeXml(byte[] bytes) throws IOException {
    if (bytes.length >= 2
        && ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 254
            || (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255))
      throw new IOException("Invalid XML encoding");
    String declaration =
        new String(bytes, 0, Math.min(bytes.length, 512), java.nio.charset.StandardCharsets.UTF_8);
    var encoding =
        java.util.regex.Pattern.compile(
                "^\\uFEFF?\\s*<\\?xml[^?]*encoding\\s*=\\s*[\"']([^\"']+)[\"']")
            .matcher(declaration);
    if (encoding.find() && !encoding.group(1).equalsIgnoreCase("UTF-8"))
      throw new IOException("Unsupported XML encoding");
    try {
      var factory = XMLInputFactory.newFactory();
      factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
      factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
      factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
      factory.setXMLResolver(
          (publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External entities are disabled");
          });
      reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
      namespaces.push(Map.of());
    } catch (XMLStreamException error) {
      throw new IOException("Invalid XML", error);
    }
  }

  Token next() throws IOException {
    try {
      if (pop) {
        namespaces.pop();
        pop = false;
      }
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          var scope = new LinkedHashMap<>(namespaces.peek());
          var attributes = new LinkedHashMap<String, String>();
          for (int i = 0; i < reader.getAttributeCount(); i++) {
            String prefix = reader.getAttributePrefix(i);
            String
                name =
                    (prefix == null || prefix.isEmpty() ? "" : prefix + ":")
                        + reader.getAttributeLocalName(i),
                value = reader.getAttributeValue(i);
            attributes.put(name, value);
            if (name.equals("xmlns")) scope.put("", value);
            else if (name.startsWith("xmlns:")) scope.put(name.substring(6), value);
          }
          namespaces.push(scope);
          return new Token(
              event, local(reader.getLocalName()), "", attributes, scope, reader.getLocalName());
        }
        if (event == XMLStreamConstants.END_ELEMENT) {
          pop = true;
          return new Token(
              event,
              local(reader.getLocalName()),
              "",
              Map.of(),
              namespaces.peek(),
              reader.getLocalName());
        }
        if (event == XMLStreamConstants.CHARACTERS
            || event == XMLStreamConstants.CDATA
            || event == XMLStreamConstants.SPACE)
          return new Token(
              XMLStreamConstants.CHARACTERS, "", reader.getText(), Map.of(), namespaces.peek(), "");
        if (event == XMLStreamConstants.ENTITY_REFERENCE)
          throw new IOException("Invalid XML entity");
      }
      return null;
    } catch (XMLStreamException error) {
      throw new IOException("Invalid XML", error);
    }
  }

  static String local(String name) {
    int colon = name.lastIndexOf(':');
    return colon < 0 ? name : name.substring(colon + 1);
  }

  public void close() {
    try {
      reader.close();
    } catch (XMLStreamException ignored) {
    }
  }
}
