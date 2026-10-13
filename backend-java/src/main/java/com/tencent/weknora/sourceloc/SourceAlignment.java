package com.tencent.weknora.sourceloc;

import com.tencent.weknora.docreader.DocumentReadResult.SourceBlock;
import java.util.*;

/** Verified full-text remapping; ambiguous, changed or deleted text never receives an invented location. */
public final class SourceAlignment {
    private SourceAlignment(){}
    public record Unit(String text,Map<String,Object> locator){public Unit{locator=Map.copyOf(locator);}}
    /** CSV source row numbering counts parsed records, including rows whose text is blank. */
    public static List<Unit> csvUnits(byte[] data){
        var units=new ArrayList<Unit>();int row=0;
        for(var record:com.tencent.weknora.docreader.GoCsv.readRecords(data,false)){
            row++;String text=GoSourceText.trim(String.join(" ",record));if(text.isEmpty())continue;
            units.add(new Unit(text,Map.of("type","sheet","row_start",row,"row_end",row)));if(units.size()>=200000)break;
        }return List.copyOf(units);
    }
    public static List<SourceBlock> remapBlocks(List<SourceBlock> blocks,String oldText,String newText){
        if(blocks.isEmpty()||oldText.equals(newText))return blocks;
        int[] oldRunes=oldText.codePoints().toArray();List<Unit> units=new ArrayList<>();
        for(var block:blocks){if(block.start()<0||block.end()>oldRunes.length||block.end()<=block.start())continue;
            String text=GoSourceText.normalize(new String(oldRunes,Math.toIntExact(block.start()),Math.toIntExact(block.end()-block.start()))).text();
            units.add(new Unit(text,block.locator()));}
        return align(newText,units);
    }
    public static List<SourceBlock> align(String markdown,List<Unit> units){
        var normalized=GoSourceText.normalize(markdown);int[] runes=markdown.codePoints().toArray();List<SourceBlock> blocks=new ArrayList<>();int cursor=0,floor=0;
        Map<String,Integer> counts=new HashMap<>();Map<String,List<Integer>> matches=new HashMap<>();
        for(var unit:units)counts.merge(GoSourceText.normalizeKey(unit.text()),1,Integer::sum);
        for(var unit:units){String key=GoSourceText.normalizeKey(unit.text());if(key.codePointCount(0,key.length())<2)continue;
            var positions=matches.computeIfAbsent(key,k->matches(normalized.text(),k));if(positions.size()!=counts.get(key))continue;
            int matchIndex=Collections.binarySearch(positions,cursor);if(matchIndex<0)matchIndex=-matchIndex-1;if(matchIndex==positions.size())continue;
            int at=positions.get(matchIndex),endText=at+key.length(),start=lineLeadStart(runes,normalized.position()[at],floor),end=normalized.end()[endText-1];
            if(endText==normalized.text().length())end=runes.length;
            else {int next=normalized.position()[endText],lead=next;while(lead>0&&runes[lead-1]!='\n')lead--;if(lead>=end)end=lead;}
            Map<String,Object> locator=new LinkedHashMap<>(unit.locator());locator.put("mapping","exact");blocks.add(new SourceBlock(start,end,locator));cursor=endText;floor=end;
        }return List.copyOf(blocks);
    }
    private static List<Integer> matches(String text,String key){
        var out=new ArrayList<Integer>();if(key.isEmpty())return out;int first=key.codePointAt(0),last=key.codePointBefore(key.length());
        for(int from=0;from<=text.length()-key.length();){int at=text.indexOf(key,from);if(at<0)break;int before=at>0?text.codePointBefore(at):0xfffd,after=at+key.length()<text.length()?text.codePointAt(at+key.length()):0xfffd;
            boolean left=GoSourceText.digit(first)&&(GoSourceText.digit(before)||".,:/+−-".indexOf(before)>=0),right=GoSourceText.digit(last)&&(GoSourceText.digit(after)||".,:/%‰".indexOf(after)>=0);
            if(!left&&!right)out.add(at);from=at+Character.charCount(text.codePointAt(at));
        }return out;
    }
    private static int lineLeadStart(int[] runes,int position,int floor){int start=position;while(start>floor&&runes[start-1]!='\n'){if(position-start>=16||GoSourceText.letter(runes[start-1]))return position;start--;}return start;}
}
