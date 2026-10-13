package com.tencent.weknora.chunker;

import java.util.List;
import java.util.ArrayList;
import com.google.re2j.Pattern;

final class ChunkPatterns {
    static final Pattern MarkdownHeadingPattern = RuneText.pattern("(?m)^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    static final Pattern FormFeedPattern = RuneText.pattern("\\f");
    static final Pattern NumberedSectionPattern = RuneText.pattern("(?m)^[ \\t]*(?:\\d+(?:\\.\\d+){1,3}\\.?|(?:\\d+|[IVX]{1,5})\\.)[ \\t]+\\S.{0,200}$");
    static final Pattern AllCapsHeadingPattern = RuneText.pattern("(?m)^[ \\t]*([A-ZÄÖÜ][A-ZÄÖÜ \\-]{3,80}):?\\s*$");
    static final Pattern VisualSeparatorPattern = RuneText.pattern("(?m)^[ \\t]*(?:-{3,}|={3,}|\\*{3,}|_{3,})[ \\t]*$");
    static final Pattern ExcessiveBlanksPattern = RuneText.pattern("\\n{3,}");
    static final Pattern PageFooterPattern = RuneText.pattern("(?mi)^[ \\t]*(?:Seite|Page|页码?)\\s+\\d+(?:\\s*(?:von|of|/)\\s*\\d+)?[ \\t]*$");
    static final Pattern GermanChapterPattern = RuneText.pattern("(?m)^[ \\t]*(?:Kapitel|Abschnitt|Teil)\\s+(?:[0-9]+|[IVX]{1,5})[\\.: ].{0,200}$");
    static final Pattern EnglishChapterPattern = RuneText.pattern("(?m)^[ \\t]*(?:Chapter|Section|Part)\\s+(?:[0-9]+|[IVX]{1,5})[\\.: ].{0,200}$");
    static final Pattern ChineseChapterPattern = RuneText.pattern("(?m)^[ \\t]*第[ \\t]*[一二三四五六七八九十百千零〇0-9]+[ \\t]*(?:章|节|節|部分|篇)[ \\t]?.{0,200}$");
    static List<Pattern> chapters(List<String> languages) {
        List<Pattern> patterns = new ArrayList<>();
        for (String language : languages) switch (language) {
            case "de" -> patterns.add(GermanChapterPattern);
            case "en" -> patterns.add(EnglishChapterPattern);
            case "zh" -> patterns.add(ChineseChapterPattern);
            default -> { }
        }
        return patterns.isEmpty() ? List.of(GermanChapterPattern, EnglishChapterPattern, ChineseChapterPattern) : patterns;
    }
}
