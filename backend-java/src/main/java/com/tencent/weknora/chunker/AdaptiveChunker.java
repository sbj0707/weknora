package com.tencent.weknora.chunker;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.ArrayList;
import java.util.List;

public final class AdaptiveChunker {
    public record Rejection(String tier,String reason) { }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Diagnostics(String selectedTier,List<String> tierChain,List<Rejection> rejected,DocumentProfile profile) { }
    public record Result(List<Chunk> chunks,Diagnostics diagnostics) { }
    public record ParentChildWithDiagnostics(TextSplitter.ParentChildResult result,Diagnostics diagnostics) { }
    public record ConfigPair(SplitterConfig parent,SplitterConfig child) { }
    public record Validation(boolean ok,String reason) { }
    private AdaptiveChunker() { }

    public static List<Chunk> split(String text,SplitterConfig config) { return splitWithDiagnostics(text,config).chunks; }
    public static Result splitWithDiagnostics(String text,SplitterConfig config) {
        if (text.isEmpty()) return new Result(List.of(),new Diagnostics("legacy",null,null,null));
        config=ensureDefaults(config);
        List<String> chain;
        DocumentProfile profile=null;
        switch (config.strategy()) {
            case "heading" -> chain=List.of("heading","legacy");
            case "heuristic" -> chain=List.of("heuristic","legacy");
            case "legacy","recursive","" -> chain=List.of("legacy");
            default -> {profile=DocumentProfile.of(text);chain=profile.strategyChain();}
        }
        List<Rejection> rejected=new ArrayList<>();
        List<Chunk> last=null;
        for (String tier:chain) {
            List<Chunk> output=switch (tier) {
                case "heading" -> HeadingSplitter.split(text,config,profile);
                case "heuristic" -> HeuristicSplitter.split(text,config);
                default -> TextSplitter.splitText(text,config);
            };
            Validation validation=validate(output,RuneText.length(text),config.chunkSize());
            if (validation.ok) return new Result(List.copyOf(output),new Diagnostics(tier,chain,rejected.isEmpty()?null:List.copyOf(rejected),profile));
            rejected.add(new Rejection(tier,validation.reason));
            if (tier.equals("legacy")) last=output;
        }
        return new Result(last==null?TextSplitter.splitText(text,config):List.copyOf(last),
                new Diagnostics("legacy",chain,List.copyOf(rejected),profile));
    }
    public static SplitterConfig ensureDefaults(SplitterConfig config) {
        config=config.normalized();
        int size=config.chunkSize(),overlap=config.chunkOverlap();
        if (config.tokenLimit()>0) {
            String language=config.languages().isEmpty()?"mixed":config.languages().getFirst();
            int budget=TokenEstimator.charsForTokenLimit(config.tokenLimit(),language);
            if (budget>0 && budget<size) size=budget;
        }
        if (size>0 && overlap>size/2) overlap=size/2;
        return new SplitterConfig(size,overlap,config.separators(),config.strategy(),config.tokenLimit(),config.languages());
    }
    public static Validation validate(List<Chunk> chunks,int totalChars,int size) {
        if (chunks.isEmpty()) return new Validation(false,"no chunks produced");
        if (chunks.size()==1 && totalChars>2L*size) return new Validation(false,"single chunk for large document");
        int max=0,tiny=0;
        for (int i=0;i<chunks.size();i++) {
            int length=RuneText.length(chunks.get(i).content());
            max=Math.max(max,length);
            if (i<chunks.size()-1 && length<50) tiny++;
        }
        if (tiny>chunks.size()/4 && tiny>2) return new Validation(false,"too many tiny chunks");
        if (max<size/4 && totalChars>size) return new Validation(false,"all chunks far below target size");
        if (size>0 && max>2L*size) return new Validation(false,"chunk exceeds 2x target size");
        return new Validation(true,"");
    }
    public static TextSplitter.ParentChildResult splitParentChild(String text,SplitterConfig parent,SplitterConfig child) {
        return splitParentChildWithDiagnostics(text,parent,child).result;
    }
    public static ParentChildWithDiagnostics splitParentChildWithDiagnostics(String text,SplitterConfig parent,SplitterConfig child) {
        parent=ensureDefaults(parent);child=ensureDefaults(child);
        Result split=splitWithDiagnostics(text,parent);
        List<Chunk> retained=new ArrayList<>();
        List<TextSplitter.ChildChunk> children=new ArrayList<>();
        for (Chunk chunk:split.chunks) {
            List<Chunk> subs=split(chunk.content(),child);
            int parentIndex=-1;
            if (subs.size()>1 || subs.size()==1 && !subs.getFirst().content().equals(chunk.content())) {
                parentIndex=retained.size();retained.add(chunk);
            }
            for (Chunk sub:subs) children.add(new TextSplitter.ChildChunk(new Chunk(sub.content(),
                    mergeBreadcrumbs(chunk.contextHeader(),sub.contextHeader()),children.size(),chunk.start()+sub.start(),chunk.start()+sub.end()),parentIndex));
        }
        return new ParentChildWithDiagnostics(new TextSplitter.ParentChildResult(retained,children),split.diagnostics);
    }
    public static ConfigPair deriveParentChildConfigs(SplitterConfig base,int parentSize,int childSize) {
        if (parentSize<=0) parentSize=4096;
        if (childSize<=0) childSize=384;
        return new ConfigPair(new SplitterConfig(parentSize,base.chunkOverlap(),base.separators(),base.strategy(),0,base.languages()),
                new SplitterConfig(childSize,childSize/5,base.separators(),base.strategy(),base.tokenLimit(),base.languages()));
    }
    public static String normalizeLineEndings(String text) { return text.replace("\r\n","\n").replace("\r","\n"); }
    private static String mergeBreadcrumbs(String parent,String child) {
        if (parent.isEmpty()) return child;
        if (child.isEmpty()) return parent;
        String[] parentLines=parent.split("\n",-1),childLines=child.split("\n",-1);
        int offset=RuneText.trim(parentLines[parentLines.length-1]).equals(RuneText.trim(childLines[0]))?1:0;
        if (offset==childLines.length) return parent;
        return parent+"\n"+String.join("\n",java.util.Arrays.copyOfRange(childLines,offset,childLines.length));
    }
}
