package com.tencent.weknora.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

final class HeuristicSplitter {
    static List<Chunk> split(String text,SplitterConfig config) {
        int length=RuneText.length(text);
        if (length<=config.chunkSize()) return TextSplitter.splitText(text,config);
        List<Integer> bounds=boundaries(text,config.languages());
        List<TextSplitter.Span> protectedSpans=TextSplitter.protectedRuneSpans(text);
        bounds.removeIf(position -> protectedSpans.stream().anyMatch(s -> position>s.start() && position<s.end()));
        if (bounds.isEmpty()) return TextSplitter.splitText(text,config);
        bounds.add(length);
        if (bounds.getFirst()!=0) bounds.addFirst(0);
        int start=bounds.getFirst(),currentEnd=start,minimum=Math.max(50,config.chunkSize()/4);
        List<Chunk> output=new ArrayList<>();
        for (int i=1;i<bounds.size();i++) {
            int nextEnd=bounds.get(i),blockLength=nextEnd-currentEnd;
            if (blockLength>config.chunkSize()) {
                if (currentEnd-start>0) append(output,text,start,currentEnd);
                for (Chunk sub:TextSplitter.splitText(RuneText.slice(text,currentEnd,nextEnd),config))
                    output.add(new Chunk(sub.content(),"",output.size(),currentEnd+sub.start(),currentEnd+sub.end()));
                currentEnd=nextEnd;start=nextEnd;continue;
            }
            if (nextEnd-start>config.chunkSize() && currentEnd-start>=minimum) {
                append(output,text,start,currentEnd);
                start=overlap(text,currentEnd,config.chunkOverlap(),bounds);
            }
            currentEnd=nextEnd;
        }
        if (currentEnd>start) append(output,text,start,currentEnd);
        return output;
    }
    private static void append(List<Chunk> output,String text,int start,int end) {
        if (end<=start) return;
        String content=RuneText.slice(text,start,end);
        if (!RuneText.trim(content).isEmpty()) output.add(new Chunk(content,"",output.size(),start,end));
    }
    private static List<Integer> boundaries(String text,List<String> languages) {
        TreeSet<Integer> bounds=new TreeSet<>();
        int position=0;
        for (int cp:text.codePoints().toArray()) {if (cp=='\f') bounds.add(position);position++;}
        var chapters=ChunkPatterns.chapters(languages);
        position=0;boolean fence=false;
        String[] lines=text.split("\n",-1);
        for (int i=0;i<lines.length;i++) {
            String line=lines[i];
            if (RuneText.trim(line).startsWith("```")) fence=!fence;
            else if (!fence && (chapters.stream().anyMatch(p -> p.matcher(line).find())
                    || ChunkPatterns.NumberedSectionPattern.matcher(line).find()
                    || ChunkPatterns.AllCapsHeadingPattern.matcher(line).find()
                    || ChunkPatterns.VisualSeparatorPattern.matcher(line).find()
                    || ChunkPatterns.PageFooterPattern.matcher(line).find())) bounds.add(position);
            position+=RuneText.length(line)+(i<lines.length-1?1:0);
        }
        var blanks=ChunkPatterns.ExcessiveBlanksPattern.matcher(text);
        while (blanks.find()) bounds.add(text.codePointCount(0,blanks.end()));
        return new ArrayList<>(bounds);
    }
    private static int overlap(String text,int end,int overlap,List<Integer> bounds) {
        if (overlap<=0) return end;
        int target=Math.max(0,end-overlap),window=Math.max(0,end-2*overlap),best=-1;
        for (int bound:bounds) if (bound>=window && bound<end && bound>best) best=bound;
        if (best>=0) return best;
        int[] runes=text.codePoints().toArray();
        for (int i=target;i>window && i<runes.length;i--) if (runes[i]=='\n') return i+1;
        return target;
    }
}
