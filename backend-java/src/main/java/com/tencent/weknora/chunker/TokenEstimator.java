package com.tencent.weknora.chunker;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class TokenEstimator {
    private TokenEstimator() { }
    private static double ratio(String language) {
        return switch (language) { case "en" -> 4.0; case "de" -> 4.5; case "zh" -> 1.7; default -> 3.0; };
    }
    public static int approximate(String text, String language) { return approximate(RuneText.length(text), language); }
    public static int approximate(int runeLength, String language) {
        if (runeLength <= 0) return 0;
        double value = runeLength / ratio(language);
        return value < 1 ? 1 : (int)(value + 0.5);
    }
    public static int charsForTokenLimit(int tokens, String language) {
        return tokens <= 0 ? 0 : (int)(tokens * ratio(language) * 0.9);
    }
    public static String detectLanguage(String text) {
        int cjk=0, latin=0, umlaut=0;
        for (int cp : text.codePoints().toArray()) {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) cjk++;
            else if ("äöüÄÖÜß".indexOf(cp)>=0) { umlaut++; latin++; }
            else if (cp>='a' && cp<='z' || cp>='A' && cp<='Z') latin++;
        }
        int total = cjk+latin;
        if (total == 0) return "mixed";
        double cjkRatio = (double)cjk/total, latinRatio = (double)latin/total;
        if (cjkRatio>=0.15 && latinRatio>=0.15) return "mixed";
        if (cjkRatio>0.3) return "zh";
        return umlaut>0 || hasGermanWords(text) ? "de" : "en";
    }
    static String byteSample(String text, int maximum) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new String(Arrays.copyOf(bytes, Math.min(bytes.length, maximum)), StandardCharsets.UTF_8);
    }
    private static boolean hasGermanWords(String text) {
        String sample = byteSample(text, 512);
        StringBuilder lowercase = new StringBuilder(sample.length());
        for (char cp : sample.toCharArray()) lowercase.append(cp>='A' && cp<='Z' ? (char)(cp+32) : cp);
        for (String word : List.of(" der ", " die ", " das ", " und ", " ist ", " nicht ", " mit ", " auf "))
            if (lowercase.indexOf(word)>=0) return true;
        return false;
    }
}
