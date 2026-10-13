package com.tencent.weknora.chunker;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DocumentProfile(int totalChars, int totalLines, double avgLineLen, double stdLineLen,
        Map<Integer,Integer> mdHeadingCounts, int mdHeadingTotal, int numberedSectionCount,
        int allCapsShortLineCount, int blankParagraphBreaks, int formFeedCount, int visualSepCount,
        int germanChapterCount, int englishChapterCount, int chineseChapterCount, int repeatedFooterCount,
        boolean hasTables, boolean hasCode, double codeRatio, List<String> detectedLangs) {
    public int dominantHeadingLevel() {
        for (int i=1;i<=6;i++) if (mdHeadingCounts.getOrDefault(i,0)>=3) return i;
        for (int i=6;i>=1;i--) if (mdHeadingCounts.getOrDefault(i,0)>0) return i;
        return 0;
    }
    public List<String> strategyChain() {
        List<String> chain = new ArrayList<>();
        if (mdHeadingTotal>=3 && totalLines>0 && (double)mdHeadingTotal/totalLines>0.005 && dominantHeadingLevel()>0) chain.add("heading");
        int markers = numberedSectionCount+germanChapterCount+englishChapterCount+chineseChapterCount+allCapsShortLineCount+visualSepCount+formFeedCount;
        if (markers>=5 || formFeedCount>0 || germanChapterCount+englishChapterCount+chineseChapterCount>0) chain.add("heuristic");
        chain.add("legacy");
        return List.copyOf(chain);
    }
    public static DocumentProfile of(String text) {
        Map<Integer,Integer> headings = new LinkedHashMap<>();
        if (text.isEmpty()) return new DocumentProfile(0,0,0,0,headings,0,0,0,0,0,0,0,0,0,0,false,false,0,null);
        int numbered=0,caps=0,visual=0,german=0,english=0,chinese=0,footer=0,codeChars=0;
        boolean inFence=false,tables=false,code=false;
        String[] lines=text.split("\n",-1);
        List<Integer> lengths=new ArrayList<>();
        for (String line:lines) {
            String trimmed=RuneText.trim(line);
            if (trimmed.startsWith("```")) {inFence=!inFence;code=true;continue;}
            if (inFence) {codeChars+=RuneText.length(line);continue;}
            lengths.add(RuneText.length(line));
            var heading=ChunkPatterns.MarkdownHeadingPattern.matcher(line);
            if (heading.find()) {headings.merge(heading.group(1).length(),1,Integer::sum);continue;}
            if (ChunkPatterns.NumberedSectionPattern.matcher(line).find()) numbered++;
            if (ChunkPatterns.AllCapsHeadingPattern.matcher(line).find()) caps++;
            if (ChunkPatterns.VisualSeparatorPattern.matcher(line).find()) visual++;
            if (ChunkPatterns.GermanChapterPattern.matcher(line).find()) german++;
            if (ChunkPatterns.EnglishChapterPattern.matcher(line).find()) english++;
            if (ChunkPatterns.ChineseChapterPattern.matcher(line).find()) chinese++;
            if (ChunkPatterns.PageFooterPattern.matcher(line).find()) footer++;
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) tables=true;
        }
        double sum=0,variance=0;
        for (int length:lengths) sum+=length;
        double average=lengths.isEmpty()?0:sum/lengths.size();
        for (int length:lengths) {double delta=length-average;variance+=delta*delta;}
        double deviation=lengths.isEmpty()?0:Math.sqrt(variance/lengths.size());
        String language=TokenEstimator.detectLanguage(TokenEstimator.byteSample(text,4096));
        List<String> languages=language.equals("mixed")?List.of("en","de","zh"):List.of(language);
        int total=RuneText.length(text);
        return new DocumentProfile(total,lines.length,average,deviation,headings,headings.values().stream().mapToInt(Integer::intValue).sum(),
                numbered,caps,count(text,"\n\n\n"),count(text,"\f"),visual,german,english,chinese,footer,tables,code,(double)codeChars/total,languages);
    }
    private static int count(String text,String needle) {
        int count=0,at=0,found;
        while ((found=text.indexOf(needle,at))>=0) {count++;at=found+needle.length();}
        return count;
    }
}
