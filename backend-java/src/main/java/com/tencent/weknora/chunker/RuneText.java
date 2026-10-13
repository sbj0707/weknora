package com.tencent.weknora.chunker;

import com.google.re2j.Pattern;

final class RuneText {
    private RuneText() { }
    static int length(String text) { return text.codePointCount(0, text.length()); }
    static String slice(String text, int start, int end) {
        return text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    }
    static String trim(String text) {
        int start = 0, end = text.length();
        while (start < end && space(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start));
        while (end > start && space(text.codePointBefore(end))) end -= Character.charCount(text.codePointBefore(end));
        return text.substring(start, end);
    }
    static boolean space(int cp) {
        return cp >= 9 && cp <= 13 || cp == 32 || cp == 0x85 || cp == 0xA0 || cp == 0x1680
                || cp >= 0x2000 && cp <= 0x200A || cp == 0x2028 || cp == 0x2029
                || cp == 0x202F || cp == 0x205F || cp == 0x3000;
    }
    static Pattern pattern(String expression) {
        // RE2 semantics preserve Go's ASCII classes and avoid recursive backtracking.
        return Pattern.compile(expression);
    }
}
