package com.tencent.weknora.docreader;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import java.io.IOException;
import java.util.*;

/** Gin's first-value binding of ParserEngineConfig, including its first saved type error. */
final class GoParserConfigRequest {
    private final ObjectMapper json = ParserEngineSettings.goMapper();
    private String firstError;
    private final IdentityHashMap<JsonNode,List<JsonNode>> sliceBacking = new IdentityHashMap<>();

    static JsonNode decode(byte[] bytes, JsonNode schema) throws IOException {
        int length = GoJsonFirstValue.length(bytes);
        var decoder = new GoParserConfigRequest();
        JsonNode result;
        try (var parser = decoder.json.createParser(GoUtf8.decode(Arrays.copyOf(bytes, length)))) {
            parser.nextToken();
            result = decoder.read(parser, schema, null, "types.ParserEngineConfig", "", true);
        }
        if (decoder.firstError != null) throw new IOException(decoder.firstError);
        return result;
    }

    private JsonNode read(JsonParser parser, JsonNode schema, JsonNode old,
                          String owner, String path, boolean root) throws IOException {
        String kind = schema.path("kind").asText();
        if (kind.equals("pointer")) return parser.currentToken() == JsonToken.VALUE_NULL
                ? NullNode.instance : read(parser, schema.path("items"), old, owner, path, root);
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            if (kind.equals("slice")) return NullNode.instance;
            return old == null ? zero(kind) : old;
        }
        if (kind.equals("struct")) {
            String type = path.isEmpty() ? "types.ParserEngineConfig" : "types.ParserEngineRule";
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                error(parser, owner, path, type, root); parser.skipChildren();
                return old == null ? zero(kind) : old;
            }
            ObjectNode out = old != null && old.isObject() ? (ObjectNode)old : json.createObjectNode();
            JsonNode fields = schema.path("fields");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String name = parser.currentName(), resolved = fields.has(name) ? name : null;
                if (resolved == null) {
                    var keys = fields.fieldNames();
                    while (keys.hasNext()) {
                        String candidate = keys.next();
                        if (fold(name).equals(fold(candidate))) { resolved = candidate; break; }
                    }
                }
                parser.nextToken();
                if (resolved == null) parser.skipChildren();
                else out.set(resolved, read(parser, fields.get(resolved), out.get(resolved),
                        type.substring(6), path.isEmpty() ? resolved : path + "." + resolved, false));
            }
            return out;
        }
        if (kind.equals("slice")) {
            JsonNode child = schema.path("items");
            String expected = child.path("kind").asText().equals("struct") ? "[]types.ParserEngineRule" : "[]string";
            if (parser.currentToken() != JsonToken.START_ARRAY) {
                error(parser, owner, path, expected, root); parser.skipChildren();
                return old == null ? NullNode.instance : old;
            }
            ArrayNode out = json.createArrayNode();
            List<JsonNode> backing = new ArrayList<>();
            if (old != null && old.isArray()) {
                if (sliceBacking.containsKey(old)) backing.addAll(sliceBacking.get(old));
                else old.forEach(backing::add);
            }
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                int index = out.size();
                JsonNode previous = index < backing.size() ? backing.get(index) : null;
                JsonNode value = read(parser, child, previous, owner, path, false);
                if (index < backing.size()) backing.set(index, value); else backing.add(value);
                out.add(value);
            }
            if (out.isEmpty()) backing.clear();
            sliceBacking.put(out, backing);
            return out;
        }
        if (kind.equals("string") && parser.currentToken() == JsonToken.VALUE_STRING)
            return TextNode.valueOf(unicode(parser.getText()));
        if (kind.equals("bool") && Set.of(JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE).contains(parser.currentToken()))
            return BooleanNode.valueOf(parser.getBooleanValue());
        error(parser, owner, path, kind, root); parser.skipChildren();
        return old == null ? zero(kind) : old;
    }

    private JsonNode zero(String kind) {
        return switch (kind) {
            case "struct" -> json.createObjectNode();
            case "string" -> TextNode.valueOf("");
            case "bool" -> BooleanNode.FALSE;
            default -> NullNode.instance;
        };
    }

    private void error(JsonParser parser, String owner, String path, String expected, boolean root) {
        if (firstError != null) return;
        String value = switch (parser.currentToken()) {
            case START_OBJECT -> "object";
            case START_ARRAY -> "array";
            case VALUE_STRING -> "string";
            case VALUE_TRUE, VALUE_FALSE -> "bool";
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> "number";
            default -> "null";
        };
        firstError = "json: cannot unmarshal " + value + " into " + (root ? "Go value of type "
                : "Go struct field " + owner + "." + path + " of type ") + expected;
    }

    private static String fold(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> out.appendCodePoint(cp == 0x17f ? 'S' : cp == 0x212a ? 'K'
                : cp >= 'a' && cp <= 'z' ? cp - 32 : cp));
        return out.toString();
    }

    private static String unicode(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) out.append(c).append(text.charAt(++i));
                else out.append('\uFFFD');
            } else out.append(Character.isLowSurrogate(c) ? '\uFFFD' : c);
        }
        return out.toString();
    }
}
