package com.tencent.weknora.chunker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import com.google.re2j.Pattern;
import java.nio.charset.StandardCharsets;

/** Native port of Go's legacy splitter. Adaptive strategy dispatch is added separately. */
public final class TextSplitter {
    private static final int ABSOLUTE_MAX = 7500;
    private static final List<Pattern> PROTECTED = List.of(
            RuneText.pattern("(?s)\\$\\$.*?\\$\\$"),
            RuneText.pattern("!\\[[^\\]\\n]{0,200}\\]\\([^)\\n]{1,500}\\)"),
            RuneText.pattern("\\[[^\\]\\n]{1,200}\\]\\([^)\\n]{1,500}\\)"),
            RuneText.pattern("(?m)[ ]*(?:\\|[^|\\n]*)+\\|[\\r\\n]+\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|[\\r\\n]+"),
            RuneText.pattern("(?m)[ ]*(?:\\|[^|\\n]*)+\\|[\\r\\n]+"),
            RuneText.pattern("(?s)```(?:\\w+)?[\\r\\n].*?```"),
            RuneText.pattern("`[^`\\r\\n]+`"));
    record Span(int start, int end) { }
    private static final Pattern IMAGE = RuneText.pattern("!\\[([^\\]]*)\\]\\(([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*)\\)");
    private static final Pattern LINKED_IMAGE = RuneText.pattern("\\[!\\[([^\\]]*)\\]\\(([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*)\\)\\]\\([^()\\s]*(?:\\([^)]*\\)[^()\\s]*)*\\)");
    /** These offsets intentionally remain UTF-8 bytes in the unwrapped text, matching Go. */
    public record ImageRef(String originalRef, String altText, int start, int end) { }
    private record Unit(String text, int start, int end) { }
    public record ChildChunk(Chunk chunk, int parentIndex) { }
    public record ParentChildResult(List<Chunk> parents, List<ChildChunk> children) {
        public ParentChildResult { parents = List.copyOf(parents); children = List.copyOf(children); }
    }
    private TextSplitter() { }

    public static List<ImageRef> extractImageRefs(String text) {
        text = LINKED_IMAGE.matcher(text).replaceAll("![$1]($2)");
        List<ImageRef> refs = new ArrayList<>();
        var matcher = IMAGE.matcher(text);
        while (matcher.find()) refs.add(new ImageRef(matcher.group(2), matcher.group(1),
                text.substring(0, matcher.start()).getBytes(StandardCharsets.UTF_8).length,
                text.substring(0, matcher.end()).getBytes(StandardCharsets.UTF_8).length));
        return List.copyOf(refs);
    }

    public static List<Chunk> splitText(String text, SplitterConfig config) {
        if (text.isEmpty()) return List.of();
        int size = config.chunkSize() <= 0 ? 512 : config.chunkSize();
        return List.copyOf(merge(buildUnits(text, protectedSpans(text), config.separators(), size),
                size, Math.max(0, config.chunkOverlap())));
    }
    static List<Span> protectedSpans(String text) {
        List<Span> matches = new ArrayList<>();
        for (Pattern pattern : PROTECTED) {
            var matcher = pattern.matcher(text);
            while (matcher.find()) if (matcher.end() > matcher.start())
                matches.add(new Span(matcher.start(), matcher.end()));
        }
        matches.sort(Comparator.comparingInt(Span::start)
                .thenComparing(Comparator.comparingInt((Span s) -> s.end-s.start).reversed()));
        List<Span> result = new ArrayList<>();
        int lastEnd = 0;
        for (Span span : matches) if (span.start >= lastEnd) { result.add(span); lastEnd = span.end; }
        return result;
    }
    static List<Span> protectedRuneSpans(String text) {
        return protectedSpans(text).stream().map(s -> new Span(
                text.codePointCount(0, s.start), text.codePointCount(0, s.end))).toList();
    }
    private static List<String> splitBySeparators(String text, List<String> separators, int size) {
        if (text.isEmpty() || separators.isEmpty() || size > 0 && RuneText.length(text) <= size)
            return List.of(text);
        for (int i = 0; i < separators.size(); i++) {
            String separator = separators.get(i);
            if (separator.isEmpty()) continue;
            List<String> pieces = new ArrayList<>();
            int start = 0, found;
            while ((found = text.indexOf(separator, start)) >= 0) {
                if (found > start) pieces.add(text.substring(start, found));
                pieces.add(separator);
                start = found + separator.length();
            }
            if (start == 0) continue;
            if (start < text.length()) pieces.add(text.substring(start));
            if (pieces.size() <= 1) continue;
            List<String> output = new ArrayList<>();
            List<String> remaining = separators.subList(i+1, separators.size());
            for (String piece : pieces) {
                if (size > 0 && RuneText.length(piece) > size && !remaining.isEmpty())
                    output.addAll(splitBySeparators(piece, remaining, size));
                else output.add(piece);
            }
            return output;
        }
        return List.of(text);
    }
    private static int appendParts(List<Unit> units, String text, int runeStart, List<String> separators, int size) {
        for (String part : splitBySeparators(text, separators, size)) {
            int length = RuneText.length(part);
            units.add(new Unit(part, runeStart, runeStart + length));
            runeStart += length;
        }
        return runeStart;
    }
    private static List<Unit> buildUnits(String text, List<Span> spans, List<String> separators, int size) {
        List<Unit> units = new ArrayList<>();
        int charPos = 0, runePos = 0;
        for (Span span : spans) {
            if (span.start > charPos)
                runePos = appendParts(units, text.substring(charPos, span.start), runePos, separators, size);
            String protectedText = text.substring(span.start, span.end);
            int length = RuneText.length(protectedText);
            if (length > ABSOLUTE_MAX) {
                int[] runes = protectedText.codePoints().toArray();
                for (int offset = 0; offset < runes.length;) {
                    int end = forcedEnd(runes, offset);
                    units.add(new Unit(new String(runes, offset, end-offset), runePos+offset, runePos+end));
                    offset = end;
                }
            } else units.add(new Unit(protectedText, runePos, runePos+length));
            runePos += length;
            charPos = span.end;
        }
        if (charPos < text.length()) appendParts(units, text.substring(charPos), runePos, separators, size);
        return units;
    }
    private static int forcedEnd(int[] runes, int offset) {
        int end = Math.min(offset + ABSOLUTE_MAX, runes.length);
        if (offset + ABSOLUTE_MAX <= runes.length) {
            for (int i = end-1; i > offset && i > end-200; i--)
                if (runes[i] == '\n' || runes[i] == ' ') { end = i+1; break; }
        }
        return end;
    }
    private static List<Chunk> merge(List<Unit> units, int size, int overlap) {
        List<Chunk> output = new ArrayList<>();
        List<Unit> current = new ArrayList<>();
        TableHeaderTracker tracker = new TableHeaderTracker();
        int currentLength = 0;
        for (Unit unit : units) {
            int length = RuneText.length(unit.text);
            if (length > ABSOLUTE_MAX) {
                if (!current.isEmpty()) { output.add(buildChunk(current, output.size())); current.clear(); currentLength = 0; }
                tracker.update(unit.text);
                int[] runes = unit.text.codePoints().toArray();
                for (int offset = 0; offset < runes.length;) {
                    int end = forcedEnd(runes, offset);
                    output.add(new Chunk(new String(runes, offset, end-offset), "", output.size(), unit.start+offset, unit.start+end));
                    offset = end;
                }
                continue;
            }
            tracker.update(unit.text);
            if (tracker.endedThisUnit && !current.isEmpty()) {
                output.add(buildChunk(current, output.size())); current.clear(); currentLength = 0;
            }
            String headers = tracker.headers();
            int headerLength = RuneText.length(headers);
            if (headerLength > size) { headers = ""; headerLength = 0; }
            if ((long) currentLength + length + headerLength > size && !current.isEmpty()) {
                output.add(buildChunk(current, output.size()));
                current = computeOverlap(current, overlap, size, length);
                currentLength = RuneText.length(unitsText(current));
                if (!headers.isEmpty() && headerLength + length <= size) {
                    while (!current.isEmpty() && currentLength + length + headerLength > size)
                        currentLength -= RuneText.length(current.removeFirst().text);
                    if (!TableHeaderTracker.alreadyPresent(headers, unitsText(current), unit.text)
                            && !TableHeaderTracker.mismatch(headers, unit.text)) {
                        int position = current.isEmpty() ? unit.start : current.getFirst().start;
                        current.addFirst(new Unit(headers, position, position));
                        currentLength += headerLength;
                    }
                }
            }
            if (currentLength + length > ABSOLUTE_MAX && !current.isEmpty()) {
                output.add(buildChunk(current, output.size())); current.clear(); currentLength = 0;
            }
            current.add(unit);
            currentLength += length;
        }
        if (!current.isEmpty()) output.add(buildChunk(current, output.size()));
        return output;
    }
    private static String unitsText(List<Unit> units) {
        StringBuilder text = new StringBuilder();
        for (Unit unit : units) text.append(unit.text);
        return text.toString();
    }
    private static Chunk buildChunk(List<Unit> units, int seq) {
        return new Chunk(unitsText(units), "", seq, units.getFirst().start, units.getLast().end);
    }
    private static List<Unit> computeOverlap(List<Unit> current, int overlap, int size, int nextLength) {
        int maxOverlap = Math.min(overlap, size-nextLength);
        if (maxOverlap <= 0) return new ArrayList<>();
        int remaining = maxOverlap > Integer.MAX_VALUE-4 ? Integer.MAX_VALUE : maxOverlap+4;
        List<Unit> window = new ArrayList<>();
        for (int i = current.size()-1; i >= 0 && remaining > 0; i--) {
            Unit unit = current.get(i);
            int length = RuneText.length(unit.text);
            if (length == 0) continue;
            if (unit.start == unit.end || unit.end-unit.start != length) break;
            if (length <= remaining) { window.add(unit); remaining -= length; }
            else {
                int start = length - remaining;
                window.add(new Unit(RuneText.slice(unit.text, start, length), unit.start+start, unit.end));
                remaining = 0;
            }
        }
        Collections.reverse(window);
        String text = unitsText(window);
        int boundary = semanticBoundary(text, Math.max(0, RuneText.length(text)-maxOverlap));
        if (boundary < 0) return new ArrayList<>();
        List<Unit> output = new ArrayList<>();
        remaining = boundary;
        for (Unit unit : window) {
            int length = RuneText.length(unit.text);
            if (remaining >= length) { remaining -= length; continue; }
            if (remaining > 0) {
                unit = new Unit(RuneText.slice(unit.text, remaining, length), unit.start+remaining, unit.end);
                remaining = 0;
            }
            output.add(unit);
        }
        String outputText = unitsText(output);
        int length = RuneText.length(outputText);
        return length <= 0 || length > maxOverlap || RuneText.trim(outputText).isEmpty() ? new ArrayList<>() : output;
    }
    private record Boundary(int start, int end, int priority) { }
    private static int semanticBoundary(String text, int minimumEnd) {
        int[] runes = text.codePoints().toArray();
        List<Boundary> candidates = new ArrayList<>();
        boolean[] paragraph = new boolean[runes.length];
        for (int i = 0; i < runes.length; i++) {
            int width = i+3 < runes.length && runes[i]=='\r' && runes[i+1]=='\n' && runes[i+2]=='\r' && runes[i+3]=='\n' ? 4
                    : i+1 < runes.length && runes[i]=='\n' && runes[i+1]=='\n' ? 2 : 0;
            if (width > 0) {
                candidates.add(new Boundary(i, i+width, 1));
                for (int j=i; j<i+width; j++) paragraph[j] = true;
                i += width-1;
            }
        }
        for (int i=0; i<runes.length; i++) {
            if (paragraph[i]) continue;
            if (runes[i]=='\r' && i+1<runes.length && runes[i+1]=='\n' && !paragraph[i+1])
                { candidates.add(new Boundary(i, i+2, 2)); i++; }
            else if (runes[i]=='\n') candidates.add(new Boundary(i, i+1, 2));
        }
        for (int i=0; i<runes.length; i++) {
            if (runes[i]=='。' || runes[i]=='？' || runes[i]=='！') candidates.add(new Boundary(i, i+1, 3));
            else if ((runes[i]=='.' || runes[i]=='?' || runes[i]=='!') && i+1<runes.length && runes[i+1]==' ')
                candidates.add(new Boundary(i, i+2, 3));
        }
        List<Span> spans = protectedRuneSpans(text);
        return candidates.stream().filter(b -> b.end >= minimumEnd && b.end < runes.length
                        && spans.stream().noneMatch(s -> b.start >= s.start && b.start < s.end)
                        && !RuneText.trim(new String(runes, b.end, runes.length-b.end)).isEmpty())
                .min(Comparator.comparingInt(Boundary::priority).thenComparingInt(Boundary::start))
                .map(Boundary::end).orElse(-1);
    }
    public static ParentChildResult splitTextParentChild(String text, SplitterConfig parentConfig, SplitterConfig childConfig) {
        List<Chunk> parents = splitText(text, parentConfig);
        List<Chunk> retained = new ArrayList<>();
        List<ChildChunk> children = new ArrayList<>();
        for (Chunk parent : parents) {
            List<Chunk> subs = splitText(parent.content(), childConfig);
            int parentIndex = -1;
            if (subs.size()>1 || subs.size()==1 && !subs.getFirst().content().equals(parent.content())) {
                parentIndex = retained.size(); retained.add(parent);
            }
            for (Chunk sub : subs) children.add(new ChildChunk(new Chunk(sub.content(), sub.contextHeader(),
                    children.size(), parent.start()+sub.start(), parent.start()+sub.end()), parentIndex));
        }
        return new ParentChildResult(retained, children);
    }
}
