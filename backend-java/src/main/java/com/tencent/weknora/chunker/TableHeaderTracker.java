package com.tencent.weknora.chunker;

import com.google.re2j.Pattern;

/** Production Go header_tracker.go state transitions, including table boundaries. */
final class TableHeaderTracker {
    private static final Pattern START = RuneText.pattern("(?si)^\\s*(?:\\|[^|\\n]*)+[\\r\\n]+\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?[\\r\\n]+\\z");
    private static final Pattern END = RuneText.pattern("(?si)^\\s*\\z|^\\s*[^|\\s].*\\z");
    private static final Pattern ROW = RuneText.pattern("(?m)^\\s*(?:\\|[^|\\n]*)+\\|\\s*$");
    private String header = "";
    private boolean ended, pendingExtend, pendingBreak;
    boolean endedThisUnit;

    void update(String text) {
        endedThisUnit = false;
        if (pendingBreak) {
            pendingBreak = false;
            if (!header.isEmpty()) {
                if (firstRowColumns(text) > 0) endedThisUnit = true;
                clear();
            }
        }
        if (!header.isEmpty() && END.matcher(text).find()) clear();
        if (!header.isEmpty() && !pendingExtend) {
            int end = text.length();
            while (end > 0 && " \t\r".indexOf(text.charAt(end-1)) >= 0) end--;
            String trimmed = text.substring(0, end);
            if (trimmed.endsWith("\n\n") || trimmed.endsWith("\r\n\r\n")) pendingBreak = true;
            else if (mismatch(header, text)) { clear(); endedThisUnit = true; }
        }
        if (pendingExtend) {
            if (!header.isEmpty() && ROW.matcher(text).find()) header = text + separator(header);
            pendingExtend = false;
        }
        if (header.isEmpty() && !ended) {
            var match = START.matcher(text);
            if (match.find()) {
                header = match.group();
                int newline = header.indexOf('\n');
                pendingExtend = newline >= 0 && pipesOnly(RuneText.trim(header.substring(0, newline)));
            }
        }
        if (header.isEmpty()) ended = false;
    }
    String headers() { return header; }
    private void clear() { header = ""; ended = true; pendingExtend = false; }
    private static boolean pipesOnly(String text) {
        return text.codePoints().allMatch(cp -> cp == '|' || cp == ' ' || cp == '\t');
    }
    private static String separator(String text) {
        for (String line : text.split("\n", -1)) if (line.contains("---")) return line + "\n";
        return "";
    }
    static int rowColumns(String text) {
        String line = RuneText.trim(text);
        if (!line.startsWith("|")) return 0;
        String[] parts = line.split("\\|", -1);
        int count = parts.length;
        if (RuneText.trim(parts[0]).isEmpty()) count--;
        if (RuneText.trim(parts[parts.length-1]).isEmpty()) count--;
        return count;
    }
    static int firstRowColumns(String text) {
        for (String line : text.split("\n", -1)) {
            line = RuneText.trim(line);
            if (!line.isEmpty() && ROW.matcher(line).find()) return rowColumns(line);
        }
        return 0;
    }
    static int headerColumns(String text) {
        for (String line : text.split("\n", -1)) {
            line = RuneText.trim(line);
            if (line.isEmpty() || line.contains("---")) continue;
            int count = rowColumns(line);
            if (count > 0) return count;
        }
        return 0;
    }
    static boolean mismatch(String header, String text) {
        int left = headerColumns(header), right = firstRowColumns(text);
        return left > 0 && right > 0 && left != right;
    }
    static boolean alreadyPresent(String header, String overlap, String text) {
        if (overlap.contains(header) || text.contains(header)) return true;
        for (String line : header.split("\n", -1)) {
            line = RuneText.trim(line);
            if (!line.isEmpty() && !line.contains("---") && !pipesOnly(line))
                return overlap.contains(line) || text.contains(line);
        }
        return false;
    }
}
