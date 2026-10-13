package com.tencent.weknora.chunker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class HeadingSplitter {
    private record Boundary(int start, String line) { }
    private record Breadcrumb(int start, String text) { }
    private static final class Hierarchy {
        String[] stack=new String[6];
        Hierarchy() { Arrays.fill(stack,""); }
        Hierarchy(Hierarchy other) { stack=other.stack.clone(); }
        void observe(String line) {
            var matcher=ChunkPatterns.MarkdownHeadingPattern.matcher(line);
            if (!matcher.find()) return;
            int level=matcher.group(1).length();
            stack[level-1]=RuneText.trim(matcher.group(2));
            for (int i=level;i<6;i++) stack[i]="";
        }
        String breadcrumb() {
            List<String> lines=new ArrayList<>();
            for (int i=0;i<6;i++) if (!stack[i].isEmpty()) lines.add("#".repeat(i+1)+" "+stack[i]);
            return String.join("\n",lines);
        }
    }
    static List<Chunk> split(String text, SplitterConfig config, DocumentProfile profile) {
        if (text.isEmpty()) return List.of();
        if (profile==null) profile=DocumentProfile.of(text);
        int level=profile.dominantHeadingLevel();
        if (level==0) return TextSplitter.splitText(text,config);
        List<Boundary> boundaries=boundaries(text,level);
        if (boundaries.size()<=1) return TextSplitter.splitText(text,config);
        int length=RuneText.length(text);
        Hierarchy hierarchy=new Hierarchy();
        List<Chunk> output=new ArrayList<>();
        for (int i=0;i<boundaries.size();i++) {
            Boundary boundary=boundaries.get(i);
            int end=i+1<boundaries.size()?boundaries.get(i+1).start:length;
            if (!boundary.line.isEmpty()) hierarchy.observe(boundary.line);
            String breadcrumb=hierarchy.breadcrumb();
            Hierarchy seed=new Hierarchy(hierarchy);
            String section=RuneText.slice(text,boundary.start,end);
            subContexts(section,level,hierarchy);
            // Rebuild per-subchunk contexts from the section-start stack.
            List<Breadcrumb> subContexts=subContexts(section,level,seed);
            if (section.isEmpty()) continue;
            if (RuneText.length(breadcrumb)+2+end-boundary.start<=config.chunkSize())
                output.add(new Chunk(section,breadcrumb,output.size(),boundary.start,end));
            else for (Chunk sub:TextSplitter.splitText(section,config)) {
                String active=breadcrumb;
                for (Breadcrumb context:subContexts) {if (context.start>sub.start()) break;active=context.text;}
                output.add(new Chunk(sub.content(),active,output.size(),boundary.start+sub.start(),boundary.start+sub.end()));
            }
        }
        return coalesce(output,config.chunkSize());
    }
    private static List<Boundary> boundaries(String text,int primaryLevel) {
        List<Boundary> bounds=new ArrayList<>();bounds.add(new Boundary(0,""));
        boolean fence=false;int position=0;
        String[] lines=text.split("\n",-1);
        for (int i=0;i<lines.length;i++) {
            String line=lines[i];
            if (RuneText.trim(line).startsWith("```")) fence=!fence;
            else if (!fence) {
                var matcher=ChunkPatterns.MarkdownHeadingPattern.matcher(line);
                if (matcher.find() && matcher.group(1).length()<=primaryLevel) {
                    if (position>0) bounds.add(new Boundary(position,line));
                    else bounds.set(0,new Boundary(0,line));
                }
            }
            position+=RuneText.length(line)+(i<lines.length-1?1:0);
        }
        return bounds;
    }
    private static List<Breadcrumb> subContexts(String section,int level,Hierarchy hierarchy) {
        List<Breadcrumb> contexts=new ArrayList<>();contexts.add(new Breadcrumb(0,hierarchy.breadcrumb()));
        boolean fence=false;int position=0;
        String[] lines=section.split("\n",-1);
        for (int i=0;i<lines.length;i++) {
            String line=lines[i];
            if (RuneText.trim(line).startsWith("```")) fence=!fence;
            else if (!fence) {
                var matcher=ChunkPatterns.MarkdownHeadingPattern.matcher(line);
                if (matcher.find() && matcher.group(1).length()>level) {
                    hierarchy.observe(line);contexts.add(new Breadcrumb(position,hierarchy.breadcrumb()));
                }
            }
            position+=RuneText.length(line)+(i<lines.length-1?1:0);
        }
        return contexts;
    }
    private static List<Chunk> coalesce(List<Chunk> chunks,int size) {
        if (chunks.size()<=1 || size<=0) return chunks;
        int target=Math.max(200,size/2);
        List<Chunk> output=new ArrayList<>();Chunk current=chunks.getFirst();
        for (int i=1;i<chunks.size();i++) {
            Chunk next=chunks.get(i);
            String shared=commonPrefix(current.contextHeader(),next.contextHeader());
            int length=RuneText.length(current.content()),nextLength=RuneText.length(next.content());
            if (!shared.isEmpty() && current.end()==next.start() && length<target && length+nextLength<=size)
                current=new Chunk(current.content()+next.content(),shared,current.seq(),current.start(),next.end());
            else {output.add(current);current=next;}
        }
        output.add(current);
        List<Chunk> sequenced=new ArrayList<>();
        for (Chunk chunk:output) sequenced.add(new Chunk(chunk.content(),chunk.contextHeader(),sequenced.size(),chunk.start(),chunk.end()));
        return sequenced;
    }
    private static String commonPrefix(String left,String right) {
        if (left.equals(right)) return left;
        String[] a=left.split("\n",-1),b=right.split("\n",-1);
        int count=0;
        while (count<Math.min(a.length,b.length) && a[count].equals(b[count])) count++;
        return String.join("\n",Arrays.copyOf(a,count));
    }
}
