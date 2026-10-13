package com.tencent.weknora.docreader;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import docreader.Docreader;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

/** Typed Go encoding/json wire binding, retaining duplicate-field/null semantics. */
final class DocReaderHttpJson {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(10000).maxNumberLength(Integer.MAX_VALUE)
                    .maxStringLength(Integer.MAX_VALUE).maxNameLength(Integer.MAX_VALUE).build()).build());
    private final List<DocumentReadResult.ImageRef> imageBacking = new ArrayList<>();
    private final IdentityHashMap<DocumentReadResult.ImageRef, List<Integer>> imageByteBacking = new IdentityHashMap<>();
    private final List<Docreader.SourceBlock> blockBacking = new ArrayList<>();
    private final List<DocumentReadResult.ParserEngineInfo> engineBacking = new ArrayList<>();
    private final IdentityHashMap<List<String>, List<String>> stringBacking = new IdentityHashMap<>();
    private final IdentityHashMap<byte[], List<Integer>> bytesBacking = new IdentityHashMap<>();
    private static final class Value {
        String text = "", directory = "", error = "";
        List<DocumentReadResult.ImageRef> images = List.of();
        List<Docreader.SourceBlock> blocks = List.of();
        Map<String, String> metadata = Map.of();
        List<DocumentReadResult.ParserEngineInfo> engines = List.of();
    }
    static DocumentReadResult read(byte[] bytes) {
        Value v = new DocReaderHttpJson().decode(bytes, false);
        return new DocumentReadResult(v.text, v.images, v.directory, v.metadata, v.error, SourceBlockDecoder.decode(v.blocks));
    }
    static List<DocumentReadResult.ParserEngineInfo> engines(byte[] bytes) { return new DocReaderHttpJson().decode(bytes, true).engines; }
    private Value decode(byte[] bytes, boolean engines) {
        String operation = engines ? "list-engines" : "read";
        try {
            int length = GoJsonFirstValue.length(bytes);
            try (JsonParser p = JSON.createParser(GoUtf8.decode(Arrays.copyOf(bytes, length)))) {
                Value v = new Value(); p.nextToken();
                String type = engines ? "httpListEnginesResponse" : "httpReadResponse";
                if (p.currentToken() == JsonToken.VALUE_NULL) return v;
                object(p, "Go value of type docparser." + type);
                String key;
                while ((key = p.nextFieldName()) != null) {
                    p.nextToken(); String name = fold(key), path = type + "." + name;
                    if (engines && name.equals("engines")) v.engines = engineArray(p, path);
                    else if (!engines) switch (name) {
                        case "markdown_content" -> v.text = scalarString(p, path, v.text);
                        case "image_dir_path" -> v.directory = scalarString(p, path, v.directory);
                        case "error" -> v.error = scalarString(p, path, v.error);
                        case "metadata" -> v.metadata = stringMap(p, path, v.metadata);
                        case "image_refs" -> v.images = imageArray(p, path);
                        case "source_blocks" -> v.blocks = blockArray(p, path);
                        default -> p.skipChildren();
                    } else p.skipChildren();
                }
                return v;
            }
        } catch (IOException ex) { throw new IllegalStateException("http decode " + operation + " response: " + ex.getMessage(), ex); }
    }
    private List<DocumentReadResult.ImageRef> imageArray(JsonParser p, String path) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) { imageBacking.clear(); return List.of(); }
        array(p, path, "[]docparser.httpImageRef"); List<DocumentReadResult.ImageRef> out = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            var old = out.size() < imageBacking.size() ? imageBacking.get(out.size()) : new DocumentReadResult.ImageRef("", "", "", "", new byte[0]);
            String filename = old.filename(), original = old.originalRef(), mime = old.mimeType(), storage = old.storageKey(); byte[] data = old.imageData();
            if (imageByteBacking.containsKey(old)) bytesBacking.put(data, imageByteBacking.get(old));
            if (p.currentToken() != JsonToken.VALUE_NULL) {
                object(p, field(path, "docparser.httpImageRef")); String key;
                while ((key = p.nextFieldName()) != null) {
                    p.nextToken(); String name = fold(key), at = "httpImageRef.image_refs." + name;
                    switch (name) {
                        case "filename" -> filename = scalarString(p, at, filename);
                        case "original_ref" -> original = scalarString(p, at, original);
                        case "mime_type" -> mime = scalarString(p, at, mime);
                        case "storage_key" -> storage = scalarString(p, at, storage);
                        case "image_data" -> data = byteArray(p, at, data);
                        default -> p.skipChildren();
                    }
                }
            }
            var next = new DocumentReadResult.ImageRef(filename, original, mime, storage, data);
            if (bytesBacking.containsKey(data)) imageByteBacking.put(next, bytesBacking.get(data));
            if (out.size() < imageBacking.size()) imageBacking.set(out.size(), next); else imageBacking.add(next);
            out.add(next);
        }
        if (out.isEmpty()) imageBacking.clear();
        return List.copyOf(out);
    }
    private List<Docreader.SourceBlock> blockArray(JsonParser p, String path) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) { blockBacking.clear(); return List.of(); }
        array(p, path, "[]docparser.wireSourceBlock"); List<Docreader.SourceBlock> out = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            var old = out.size() < blockBacking.size() ? blockBacking.get(out.size()) : Docreader.SourceBlock.getDefaultInstance();
            long start = Integer.toUnsignedLong(old.getStart()), end = Integer.toUnsignedLong(old.getEnd()); String locator = old.getLocatorJson();
            if (p.currentToken() != JsonToken.VALUE_NULL) {
                object(p, field(path, "docparser.wireSourceBlock")); String key;
                while ((key = p.nextFieldName()) != null) {
                    p.nextToken(); String name = fold(key), at = "wireSourceBlock.source_blocks." + name;
                    switch (name) {
                        case "start" -> {if (p.currentToken() != JsonToken.VALUE_NULL) start = unsigned(p, at, "uint32", 32);}
                        case "end" -> {if (p.currentToken() != JsonToken.VALUE_NULL) end = unsigned(p, at, "uint32", 32);}
                        case "locator_json" -> locator = scalarString(p, at, locator);
                        default -> p.skipChildren();
                    }
                }
            }
            var next = Docreader.SourceBlock.newBuilder().setStart((int)start).setEnd((int)end).setLocatorJson(locator).build();
            if (out.size() < blockBacking.size()) blockBacking.set(out.size(), next); else blockBacking.add(next);
            out.add(next);
        }
        if (out.isEmpty()) blockBacking.clear();
        return out;
    }
    private List<DocumentReadResult.ParserEngineInfo> engineArray(JsonParser p, String path) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) { engineBacking.clear(); return List.of(); }
        array(p, path, "[]docparser.httpParserEngineInfo"); List<DocumentReadResult.ParserEngineInfo> out = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            var old = out.size() < engineBacking.size() ? engineBacking.get(out.size()) : new DocumentReadResult.ParserEngineInfo("", "", List.of(), false, "", true);
            String name = old.name(), description = old.description(), reason = old.unavailableReason(); boolean available = old.available(); List<String> types = old.fileTypes(); boolean nilTypes = old.fileTypesNil();
            if (p.currentToken() != JsonToken.VALUE_NULL) {
                object(p, field(path, "docparser.httpParserEngineInfo")); String key;
                while ((key = p.nextFieldName()) != null) {
                    p.nextToken(); String k = fold(key), at = "httpParserEngineInfo.engines." + k;
                    switch (k) {
                        case "name" -> name = scalarString(p, at, name);
                        case "description" -> description = scalarString(p, at, description);
                        case "unavailable_reason" -> reason = scalarString(p, at, reason);
                        case "file_types" -> {nilTypes = p.currentToken() == JsonToken.VALUE_NULL; types = strings(p, at, types); }
                        case "available" -> {
                            if (p.currentToken() == JsonToken.VALUE_NULL) continue;
                            if (p.currentToken() != JsonToken.VALUE_TRUE && p.currentToken() != JsonToken.VALUE_FALSE) throw wrong(p, field(at, "bool"), false);
                            available = p.getBooleanValue();
                        }
                        default -> p.skipChildren();
                    }
                }
            }
            var next = new DocumentReadResult.ParserEngineInfo(name, description, types, available, reason, nilTypes);
            if (out.size() < engineBacking.size()) engineBacking.set(out.size(), next); else engineBacking.add(next);
            out.add(next);
        }
        if (out.isEmpty()) engineBacking.clear();
        return List.copyOf(out);
    }
    private static Map<String, String> stringMap(JsonParser p, String path, Map<String, String> previous) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) return Map.of();
        object(p, field(path, "map[string]string")); Map<String, String> out = new LinkedHashMap<>(previous); String key;
        while ((key = p.nextFieldName()) != null) {
            p.nextToken(); out.put(clean(key), scalarString(p, path, ""));
        }
        return Map.copyOf(out);
    }
    private List<String> strings(JsonParser p, String path, List<String> previous) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) return List.of();
        array(p, path, "[]string"); List<String> out = new ArrayList<>();
        List<String> backing = stringBacking.getOrDefault(previous, new ArrayList<>(previous));
        while (p.nextToken() != JsonToken.END_ARRAY) {
            int i = out.size(); String old = i < backing.size() ? backing.get(i) : "";
            String value = scalarString(p, path, old);
            if (i < backing.size()) backing.set(i, value); else backing.add(value);
            out.add(value);
        }
        List<String> result = List.copyOf(out);
        if (!out.isEmpty()) stringBacking.put(result, backing);
        return result;
    }
    private byte[] byteArray(JsonParser p, String path, byte[] previous) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) return new byte[0];
        if (p.currentToken() == JsonToken.START_ARRAY) {
            var out = new java.io.ByteArrayOutputStream();
            List<Integer> backing = bytesBacking.get(previous);
            if (backing == null) { backing = new ArrayList<>(); for (byte value : previous) backing.add(value & 255); }
            while (p.nextToken() != JsonToken.END_ARRAY) {
                int i = out.size(), old = i < backing.size() ? backing.get(i) : 0;
                int value = p.currentToken() == JsonToken.VALUE_NULL ? old : (int)unsigned(p, path, "uint8", 8);
                if (i < backing.size()) backing.set(i, value); else backing.add(value);
                out.write(value);
            }
            byte[] result = out.toByteArray();
            if (result.length > 0) bytesBacking.put(result, backing);
            return result;
        }

        if (p.currentToken() != JsonToken.VALUE_STRING) throw wrong(p, field(path, "[]uint8"), false);
        String raw = clean(p.getText()); byte[] input = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int cursor = 0;
        while (cursor < input.length) {
            for (int group = 0; group < 4; group++) {
                while (cursor < input.length && (input[cursor] == '\r' || input[cursor] == '\n')) cursor++;
                if (cursor == input.length) {
                    if (group == 0) break;
                    throw base64(cursor - group);
                }
                int value = input[cursor++] & 255;
                boolean alphabet = value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                        || value >= '0' && value <= '9' || value == '+' || value == '/';
                if (alphabet) continue;
                if (value != '=' || group < 2) throw base64(cursor - 1);
                if (group == 2) {
                    while (cursor < input.length && (input[cursor] == '\r' || input[cursor] == '\n')) cursor++;
                    if (cursor == input.length) throw base64(cursor);
                    if (input[cursor] != '=') throw base64(cursor - 1);
                    cursor++;
                }
                while (cursor < input.length && (input[cursor] == '\r' || input[cursor] == '\n')) cursor++;
                if (cursor < input.length) throw base64(cursor);
                break;
            }
        }
        return Base64.getDecoder().decode(raw.replace("\r", "").replace("\n", ""));
    }

    private static IOException base64(int index) { return new IOException("illegal base64 data at input byte " + index); }
    private static long unsigned(JsonParser p, String path, String type, int bits) throws IOException {
        if (p.currentToken() != JsonToken.VALUE_NUMBER_INT) throw wrong(p, field(path, type), p.currentToken() == JsonToken.VALUE_NUMBER_FLOAT);
        BigInteger n = p.getBigIntegerValue();
        if (p.getText().startsWith("-") || n.signum() < 0 || n.bitLength() > bits) throw wrong(p, field(path, type), true);
        return n.longValue();
    }
    private static String scalarString(JsonParser p, String path, String previous) throws IOException {
        if (p.currentToken() == JsonToken.VALUE_NULL) return previous;
        if (p.currentToken() != JsonToken.VALUE_STRING) throw wrong(p, field(path, "string"), false);
        return clean(p.getText());
    }
    private static String clean(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i+1))) out.append(c).append(s.charAt(++i));
            else out.append(Character.isSurrogate(c) ? '\uFFFD' : c);
        }
        return out.toString();
    }
    private static String fold(String s) { return s.toLowerCase(Locale.ROOT).replace("ſ", "s"); }
    private static String field(String path, String type) { return "Go struct field " + path + " of type " + type; }
    private static void object(JsonParser p, String dest) throws IOException { if (p.currentToken() != JsonToken.START_OBJECT) throw wrong(p, dest, false); }
    private static void array(JsonParser p, String path, String type) throws IOException { if (p.currentToken() != JsonToken.START_ARRAY) throw wrong(p, field(path, type), false); }
    private static IOException wrong(JsonParser p, String dest, boolean numeric) throws IOException {
        String type = switch (p.currentToken()) { case START_ARRAY -> "array"; case START_OBJECT -> "object";
            case VALUE_TRUE, VALUE_FALSE -> "bool"; case VALUE_STRING -> "string"; default -> "number"; };
        return new IOException("json: cannot unmarshal " + type + (numeric ? " " + p.getText() : "") + " into " + dest);
    }
}
