package com.tencent.weknora.docreader;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of Go's JSON semantic splitter, including byte budgets and root-to-leaf paths. */
final class JsonDocumentConverter {
    private static final int MAX_BYTES = 1536, MIN_BYTES = 1336;
    private static final ObjectMapper JSON = new ObjectMapper();
    private JsonDocumentConverter() { }

    static String toMarkdown(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191 ? 3 : 0;
        if (bytes.length == start) throw new IllegalArgumentException("json conversion failed: empty JSON content");
        String input = GoUtf8.decode(java.util.Arrays.copyOfRange(bytes, start, bytes.length));
        Object normalized;
        try (var parser = JSON.createParser(input)) {
            if (parser.nextToken() == null) throw new IOException("empty");
            normalized = normalize(parse(parser));
            if (parser.nextToken() != null) throw new IOException("trailing JSON data");
        } catch (IOException ex) {
            throw new IllegalArgumentException("json conversion failed: invalid JSON content", ex);
        }
        if (size(normalized) <= MAX_BYTES) return fence(serialize(normalized, true));
        List<Map<String, Object>> chunks = new ArrayList<>(); chunks.add(new LinkedHashMap<>());
        split(normalized, List.of(), chunks);
        List<String> blocks = new ArrayList<>();
        for (var chunk : chunks) if (!chunk.isEmpty()) blocks.add(fence(serialize(chunk, true)));
        return blocks.isEmpty() ? fence(serialize(normalized, true)) : String.join("\n\n", blocks);
    }

    private static Object parse(JsonParser parser) throws IOException {
        if (parser.currentToken() == null) throw new IOException("incomplete JSON");
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IOException("invalid object");
                    String key = unicode(parser.currentName()); parser.nextToken(); map.put(key, parse(parser));
                }
                yield map;
            }
            case START_ARRAY -> {
                List<Object> array = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) array.add(parse(parser));
                yield array;
            }
            case VALUE_STRING -> unicode(parser.getText());
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                String raw = parser.getText(); double number = Double.parseDouble(raw);
                if (!Double.isFinite(number)) throw new IllegalArgumentException("json conversion failed: failed to parse JSON: json: cannot unmarshal number " + raw + " into Go value of type float64");
                yield number;
            }
            default -> throw new IOException("invalid value");
        };
    }

    private static String unicode(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i+1 < value.length() && Character.isLowSurrogate(value.charAt(i+1))) result.append(c).append(value.charAt(++i));
                else result.append('\uFFFD');
            } else if (Character.isLowSurrogate(c)) result.append('\uFFFD');
            else result.append(c);
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<String,Object>)map).forEach((key, child) -> result.put(key, normalize(child))); return result;
        }
        if (value instanceof List<?> list) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (int i = 0; i < list.size(); i++) result.put(Integer.toString(i), normalize(list.get(i))); return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static void split(Object value, List<String> path, List<Map<String, Object>> chunks) {
        if (!(value instanceof Map<?,?> raw)) { if (!path.isEmpty()) set(chunks.getLast(), path, value); return; }
        Map<String,Object> dict = (Map<String,Object>)raw;
        List<String> keys = new ArrayList<>(dict.keySet());
        boolean numeric = keys.stream().allMatch(key -> { try { Long.parseLong(key); return true; } catch (NumberFormatException ex) { return false; } });
        keys.sort(numeric ? Comparator.comparingLong(Long::parseLong) : JsonDocumentConverter::compareKeys);
        for (String key : keys) {
            Object child = dict.get(key);
            List<String> next = new ArrayList<>(path); next.add(key);
            int currentSize = size(chunks.getLast()), itemSize = size(java.util.Collections.singletonMap(key, child));
            if (itemSize <= MAX_BYTES - currentSize) set(chunks.getLast(), next, child);
            else {
                if (currentSize >= MIN_BYTES) chunks.add(new LinkedHashMap<>());
                Object normalized = normalize(child);
                if (normalized instanceof Map<?, ?> nested && canSplit(nested)) split(normalized, next, chunks);
                else set(chunks.getLast(), next, child);
            }
        }
    }

    private static boolean canSplit(Map<?,?> value) {
        if (value.size() > 1) return true;
        if (value.size() == 1 && value.values().iterator().next() instanceof Map<?,?> nested) return nested.size() > 1;
        return false;
    }
    @SuppressWarnings("unchecked")
    private static void set(Map<String,Object> target, List<String> path, Object value) {
        for (int i = 0; i < path.size()-1; i++) {
            String key = path.get(i);
            if (!(target.get(key) instanceof Map<?,?>)) target.put(key, new LinkedHashMap<String,Object>());
            target = (Map<String,Object>)target.get(key);
        }
        target.put(path.getLast(), value);
    }
    private static int size(Object value) { return serialize(value, false).getBytes(StandardCharsets.UTF_8).length; }
    private static String fence(String value) { return "```json\n" + value + "\n```"; }
    private static String serialize(Object value, boolean pretty) { StringBuilder out = new StringBuilder(); write(out, value, pretty, 0); return out.toString(); }
    @SuppressWarnings("unchecked")
    private static void write(StringBuilder out, Object value, boolean pretty, int depth) {
        if (value == null) out.append("null");
        else if (value instanceof String text) quote(out, text);
        else if (value instanceof Boolean bool) out.append(bool);
        else if (value instanceof Double number) out.append(number(number));
        else if (value instanceof Map<?,?> raw) {
            Map<String,Object> map = (Map<String,Object>)raw;
            List<String> keys = new ArrayList<>(map.keySet()); keys.sort(JsonDocumentConverter::compareKeys);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                if (pretty) out.append('\n').append("  ".repeat(depth + 1));
                String key = keys.get(i); quote(out,key); out.append(pretty ? ": " : ":"); write(out,map.get(key),pretty,depth+1);
            }
            if (pretty && !keys.isEmpty()) out.append('\n').append("  ".repeat(depth));
            out.append('}');
        } else throw new IllegalArgumentException("unsupported normalized JSON value");
    }
    private static String number(double value) {
        if (value == 0 && Double.doubleToRawLongBits(value) < 0) return "-0";
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        double abs = Math.abs(value);
        if (abs != 0 && (abs < 1e-6 || abs >= 1e21)) {
            String[] parts = decimal.toString().toLowerCase(java.util.Locale.ROOT).split("e");
            if (parts.length == 2) return parts[0] + "e" + (parts[1].startsWith("-") || parts[1].startsWith("+") ? parts[1] : "+" + parts[1]);
        }
        return decimal.toPlainString();
    }
    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i=0; i<text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || c == '<' || c == '>' || c == '&' || c == '\u2028' || c == '\u2029') out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
    private static int compareKeys(String left, String right) {
        var a = left.codePoints().iterator(); var b = right.codePoints().iterator();
        while (a.hasNext() && b.hasNext()) { int result = Integer.compare(a.nextInt(), b.nextInt()); if (result != 0) return result; }
        return a.hasNext() ? 1 : b.hasNext() ? -1 : 0;
    }
}
