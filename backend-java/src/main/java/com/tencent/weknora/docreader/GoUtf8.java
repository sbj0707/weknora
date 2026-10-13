package com.tencent.weknora.docreader;

/** Go utf8.DecodeRune replaces each invalid byte independently. */
public final class GoUtf8 {
    private GoUtf8() { }
    public static String decode(byte[] data) {
        StringBuilder output = new StringBuilder(data.length);
        for (int at = 0; at < data.length;) {
            int first = data[at] & 255;
            if (first < 128) { output.append((char)first); at++; continue; }
            int width = first >= 0xC2 && first <= 0xDF ? 2 : first >= 0xE0 && first <= 0xEF ? 3 : first >= 0xF0 && first <= 0xF4 ? 4 : 0;
            int codePoint = width == 2 ? first & 31 : width == 3 ? first & 15 : first & 7;
            boolean valid = width != 0 && at + width <= data.length;
            for (int i = 1; valid && i < width; i++) {
                int next = data[at+i] & 255;
                if ((next & 0xC0) != 0x80) valid = false;
                else codePoint = (codePoint << 6) | (next & 63);
            }
            if (valid && (codePoint < (width == 2 ? 0x80 : width == 3 ? 0x800 : 0x10000)
                    || codePoint > 0x10FFFF || codePoint >= 0xD800 && codePoint <= 0xDFFF)) valid = false;
            if (valid) { output.appendCodePoint(codePoint); at += width; }
            else { output.append('\uFFFD'); at++; }
        }
        return output.toString();
    }
    static boolean isSpace(int cp) {
        return cp >= 9 && cp <= 13 || cp == 32 || cp == 0x85 || cp == 0xA0 || cp == 0x1680
                || cp >= 0x2000 && cp <= 0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000;
    }
}
