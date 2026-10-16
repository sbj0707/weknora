package com.tencent.weknora.retrieval;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Go typed image/document metadata; any saved type error discards enrichment as Go does. */
public final class RerankMetadata {
  private static final ObjectMapper JSON = new ObjectMapper();
  private boolean invalid;

  enum Kind {
    IMAGE,
    ATTRS,
    SOURCE,
    DOCUMENT,
    QUESTION
  }

  static JsonNode images(String source) throws IOException {
    RerankMetadata decoder = new RerankMetadata();
    syntax(source);
    JsonNode result;
    try (JsonParser p = JSON.createParser(source)) {
      p.nextToken();
      result = decoder.array(p, Kind.IMAGE);
    }
    if (decoder.invalid) throw new IOException("invalid image metadata");
    return result;
  }

  public static JsonNode document(String source) throws IOException {
    RerankMetadata decoder = new RerankMetadata();
    syntax(source);
    JsonNode result;
    try (JsonParser p = JSON.createParser(source)) {
      p.nextToken();
      result = decoder.object(p, Kind.DOCUMENT, null);
    }
    if (decoder.invalid) throw new IOException("invalid document metadata");
    return result;
  }

  private static void syntax(String source) throws IOException {
    byte[] data = source.getBytes(StandardCharsets.UTF_8);
    int end = GoJsonFirstValue.length(data);
    for (int i = end; i < data.length; i++)
      if (data[i] != 9 && data[i] != 10 && data[i] != 13 && data[i] != 32)
        throw new IOException("trailing metadata");
  }

  private JsonNode array(JsonParser p, Kind child) throws IOException {
    if (p.currentToken() == JsonToken.VALUE_NULL) return NullNode.instance;
    if (p.currentToken() != JsonToken.START_ARRAY) {
      invalid = true;
      p.skipChildren();
      return NullNode.instance;
    }
    ArrayNode out = JSON.createArrayNode();
    while (p.nextToken() != JsonToken.END_ARRAY) out.add(object(p, child, null));
    return out;
  }

  private JsonNode object(JsonParser p, Kind kind, JsonNode previous) throws IOException {
    if (p.currentToken() == JsonToken.VALUE_NULL)
      return previous != null ? previous : JSON.createObjectNode();
    if (p.currentToken() != JsonToken.START_OBJECT) {
      invalid = true;
      p.skipChildren();
      return previous != null ? previous : JSON.createObjectNode();
    }
    ObjectNode out =
        previous != null && previous.isObject() ? (ObjectNode) previous : JSON.createObjectNode();
    while (p.nextToken() != JsonToken.END_OBJECT) {
      String key = fold(p.currentName());
      p.nextToken();
      String type = type(kind, key);
      if (type == null) {
        p.skipChildren();
        continue;
      }
      if (p.currentToken() == JsonToken.VALUE_NULL) {
        if (Set.of("questions", "map").contains(type)
            || kind == Kind.QUESTION && key.equals("content_revision"))
          out.set(key, NullNode.instance);
        continue;
      }
      switch (type) {
        case "string":
          if (p.currentToken() == JsonToken.VALUE_STRING) out.put(key, surrogates(p.getText()));
          else {
            invalid = true;
            p.skipChildren();
          }
          break;
        case "int":
          if (p.currentToken() == JsonToken.VALUE_NUMBER_INT
              && p.getBigIntegerValue().bitLength() < 64) out.put(key, p.getLongValue());
          else {
            invalid = true;
            p.skipChildren();
          }
          break;
        case "attrs":
          out.set(key, object(p, Kind.ATTRS, out.get(key)));
          break;
        case "source":
          out.set(key, object(p, Kind.SOURCE, out.get(key)));
          break;
        case "bool":
          if (p.currentToken() == JsonToken.VALUE_TRUE || p.currentToken() == JsonToken.VALUE_FALSE)
            out.put(key, p.getBooleanValue());
          else {
            invalid = true;
            p.skipChildren();
          }
          break;
        case "questions":
          out.set(key, array(p, Kind.QUESTION));
          break;
        case "map":
          if (p.currentToken() == JsonToken.START_OBJECT) out.set(key, any(p));
          else {
            invalid = true;
            p.skipChildren();
          }
          break;
      }
    }
    return out;
  }

  private JsonNode any(JsonParser p) throws IOException {
    if (p.currentToken().isNumeric()) {
      double value = Double.parseDouble(p.getText());
      if (!Double.isFinite(value)) invalid = true;
      return DoubleNode.valueOf(value);
    }
    if (p.currentToken() == JsonToken.START_OBJECT) {
      ObjectNode out = JSON.createObjectNode();
      while (p.nextToken() != JsonToken.END_OBJECT) {
        String key = surrogates(p.currentName());
        p.nextToken();
        out.set(key, any(p));
      }
      return out;
    }
    if (p.currentToken() == JsonToken.START_ARRAY) {
      ArrayNode out = JSON.createArrayNode();
      while (p.nextToken() != JsonToken.END_ARRAY) out.add(any(p));
      return out;
    }
    return p.currentToken() == JsonToken.VALUE_STRING
        ? TextNode.valueOf(surrogates(p.getText()))
        : JSON.readTree(p);
  }

  private static String surrogates(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) out.append(c).append(text.charAt(++i));
      else out.append(Character.isSurrogate(c) ? '\ufffd' : c);
    }
    return out.toString();
  }

  private static String fold(String name) {
    StringBuilder out = new StringBuilder();
    name.codePoints()
        .forEach(
            cp -> {
              if (cp >= 'A' && cp <= 'Z') cp += 32;
              else if (cp == 0x17f) cp = 's';
              else if (cp == 0x212a) cp = 'k';
              out.appendCodePoint(cp);
            });
    return out.toString();
  }

  private static String type(Kind kind, String key) {
    return switch (kind) {
      case IMAGE ->
          Set.of("sha256", "url", "original_url", "caption", "ocr_text").contains(key)
              ? "string"
              : Set.of("start_pos", "end_pos").contains(key)
                  ? "int"
                  : key.equals("attrs") ? "attrs" : null;
      case ATTRS ->
          key.equals("schema")
              ? "string"
              : key.equals("source") ? "source" : key.equals("attrs") ? "map" : null;
      case SOURCE ->
          Set.of("schema", "prompt").contains(key)
              ? "string"
              : key.equals("custom") ? "bool" : null;
      case DOCUMENT ->
          key.equals("generated_questions")
              ? "questions"
              : key.equals("generated_questions_revision") ? "int" : null;
      case QUESTION ->
          Set.of("id", "question").contains(key)
              ? "string"
              : key.equals("content_revision") ? "int" : null;
    };
  }
}
