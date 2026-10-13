package com.tencent.weknora.docreader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonToken;
import docreader.Docreader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Go json.Unmarshal typed locator rules: malformed locators are dropped, unknown fields ignored. */
final class SourceBlockDecoder {
    private static final Set<String> TEXT_FIELDS = Set.of("type", "mapping", "source_id", "source_hash", "sheet", "title", "quote");
    private static final Set<String> INT_FIELDS = Set.of("page", "block", "slide", "row_start", "row_end", "start", "end", "section", "start_ms", "end_ms");
    private static final ObjectMapper JSON = new ObjectMapper();
    private SourceBlockDecoder() { }

    static List<DocumentReadResult.SourceBlock> decode(List<Docreader.SourceBlock> blocks) {
        List<DocumentReadResult.SourceBlock> result = new ArrayList<>();
        for (var block : blocks) {
            long start = Integer.toUnsignedLong(block.getStart()), end = Integer.toUnsignedLong(block.getEnd());
            if (end <= start || block.getLocatorJson().isEmpty()) continue;
            try (var parser = JSON.createParser(block.getLocatorJson())) {
                if (parser.nextToken() != JsonToken.START_OBJECT) continue;
                Map<String, Object> locator = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IllegalArgumentException("invalid object");
                    String name = parser.currentName().toLowerCase(Locale.ROOT);
                    parser.nextToken();
                    JsonNode value = JSON.readTree(parser);
                    if (value == null) throw new IllegalArgumentException("missing field value");
                    if (value.isNull()) {
                        if ("bbox".equals(name)) locator.remove(name); // Go clears a slice on null; scalars remain unchanged.
                        continue;
                    }
                    if (TEXT_FIELDS.contains(name)) {
                        if (!value.isTextual()) throw new IllegalArgumentException("invalid string");
                        if (!value.textValue().isEmpty()) locator.put(name, value.textValue()); else locator.remove(name);
                    } else if (INT_FIELDS.contains(name)) {
                        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw new IllegalArgumentException("invalid integer");
                        if (value.longValue() != 0) locator.put(name, value.longValue()); else locator.remove(name);
                    } else if ("partial".equals(name)) {
                        if (!value.isBoolean()) throw new IllegalArgumentException("invalid boolean");
                        if (value.booleanValue()) locator.put(name, true); else locator.remove(name);
                    } else if ("bbox".equals(name)) {
                        if (!value.isArray()) throw new IllegalArgumentException("invalid bounding box");
                        List<Double> coordinates = new ArrayList<>();
                        for (JsonNode point : value) {
                            if (point.isNull()) { coordinates.add(0.0); continue; }
                            if (!point.isNumber() || !Double.isFinite(point.doubleValue())) throw new IllegalArgumentException("invalid coordinate");
                            coordinates.add(point.doubleValue());
                        }
                        if (!coordinates.isEmpty()) locator.put(name, List.copyOf(coordinates)); else locator.remove(name);
                    }
                }
                if (parser.nextToken() != null) throw new IllegalArgumentException("trailing JSON data");
                if (!(locator.get("type") instanceof String type) || type.isEmpty()) continue;
                result.add(new DocumentReadResult.SourceBlock(start, end, locator));
            } catch (Exception ignored) { /* Go drops source mapping with invalid JSON/type data. */ }
        }
        return List.copyOf(result);
    }
}
