package com.tencent.weknora.sourceloc;

import com.tencent.weknora.docreader.DocumentReadResult.SourceBlock;
import java.util.*;

/** Native Go sourceloc range index. Offsets count Unicode code points; empty locators are SQL NULL. */
public final class SourceLocatorIndex {
    private final int[] runes;private final List<SourceBlock> blocks;private final long[] maxEnd;
    public SourceLocatorIndex(String markdown,List<SourceBlock> blocks){
        runes=markdown.codePoints().toArray();this.blocks=new ArrayList<>(blocks);this.blocks.sort(Comparator.comparingLong(SourceBlock::start));maxEnd=new long[blocks.size()];
        for(int i=0;i<maxEnd.length;i++)maxEnd[i]=i==0?this.blocks.get(i).end():Math.max(this.blocks.get(i).end(),maxEnd[i-1]);
    }
    public List<Map<String,Object>> locators(long from,long to){
        if(to<=from||blocks.isEmpty())return null;long start=Math.max(from,0),end=Math.min(to,runes.length),covered=start;boolean partial=false;
        var out=new ArrayList<Map<String,Object>>();
        for(int i=firstEndingAfter(start);i<blocks.size()&&blocks.get(i).start()<end;i++){
            var block=blocks.get(i);long low=Math.max(block.start(),start),high=Math.min(block.end(),end);if(high<=low)continue;
            if(low>covered&&GoSourceText.visible(GoSourceText.quote(slice(covered,low))))partial=true;
            covered=Math.max(covered,high);String quote=GoSourceText.quote(slice(low,high));if(quote.isEmpty()&&!"pdf".equals(block.locator().get("type")))continue;
            var locator=copy(block.locator());if(quote.isEmpty())locator.remove("quote");else locator.put("quote",quote);append(out,locator);
        }
        if(covered<end&&GoSourceText.visible(GoSourceText.quote(slice(covered,end))))partial=true;
        if(partial)out.forEach(locator->locator.put("partial",true));return out.isEmpty()?null:out;
    }
    public List<Map<String,Object>> locatorsAt(long position){
        if(position<0||blocks.isEmpty())return null;var out=new ArrayList<Map<String,Object>>();
        for(int i=firstEndingAfter(position);i<blocks.size()&&blocks.get(i).start()<=position;i++)if(blocks.get(i).end()>position){var locator=copy(blocks.get(i).locator());locator.remove("quote");out.add(locator);}
        return out.isEmpty()?null:out;
    }
    private int firstEndingAfter(long position){int low=0,high=maxEnd.length;while(low<high){int mid=(low+high)>>>1;if(maxEnd[mid]>position)high=mid;else low=mid+1;}return low;}
    private String slice(long start,long end){return new String(runes,Math.toIntExact(start),Math.toIntExact(end-start));}
    public static List<SourceBlock> textBlocks(String content){
        var blocks=new ArrayList<SourceBlock>();int[] runes=content.codePoints().toArray();int start=-1,blankRun=0,lineStart=0;boolean lineHasText=false;
        for(int pos=0;pos<runes.length;pos++){
            int rune=runes[pos];if(rune=='\n'){
                if(!lineHasText){blankRun++;if(blankRun==1){if(start>=0&&lineStart>start)blocks.add(textBlock(start,lineStart,start,lineStart));start=-1;}}else blankRun=0;
                lineHasText=false;lineStart=pos+1;
            }else if(rune!=' '&&rune!='\t'&&rune!='\r'){if(!lineHasText&&start<0)start=lineStart;lineHasText=true;}
        }
        if(start>=0&&runes.length>start)blocks.add(textBlock(start,runes.length,start,runes.length));return List.copyOf(blocks);
    }
    /** The viewer excludes a BOM, but the Markdown block offsets include it. */
    public static List<SourceBlock> passThroughTextBlocks(String content){
        boolean bom=content.startsWith("\ufeff");var blocks=textBlocks(bom?content.substring(1):content);if(!bom)return blocks;
        return blocks.stream().map(block->new SourceBlock(block.start()+1,block.end()+1,block.locator())).toList();
    }
    private static SourceBlock textBlock(int start,int end,int sourceStart,int sourceEnd){Map<String,Object> locator=new LinkedHashMap<>();locator.put("type","text");locator.put("mapping","exact");if(sourceStart!=0)locator.put("start",sourceStart);if(sourceEnd!=0)locator.put("end",sourceEnd);return new SourceBlock(start,end,locator);}
    public static String cleanQuote(String text){String quote=GoSourceText.quote(text);int length=quote.codePointCount(0,quote.length());return length<=300?quote:quote.substring(0,quote.offsetByCodePoints(0,300));}
    public static List<Map<String,Object>> mergeLocators(List<Map<String,Object>> first,List<Map<String,Object>> second){
        if(second==null||second.isEmpty())return first;if(first==null||first.isEmpty())return second;
        var out=new ArrayList<Map<String,Object>>(first);for(var locator:second)if(out.stream().noneMatch(existing->same(existing,locator)))out.add(locator);return out;
    }
    private static final List<String> STRING_FIELDS=List.of("partial","quote","source_id","source_hash","mapping","type","sheet","bbox");
    private static final List<String> NUMBER_FIELDS=List.of("page","block","slide","row_start","row_end","start","end","start_ms","end_ms","section");
    private static boolean same(Map<String,Object> a,Map<String,Object>b){for(String key:STRING_FIELDS)if(!Objects.equals(a.get(key),b.get(key)))return false;for(String key:NUMBER_FIELDS)if(number(a,key)!=number(b,key))return false;return true;}
    private static void append(List<Map<String,Object>> out,Map<String,Object> locator){if(out.isEmpty()||!fold(out.getLast(),locator))out.add(locator);}
    private static boolean fold(Map<String,Object> last,Map<String,Object> locator){
        for(String key:List.of("type","partial","mapping","source_hash","source_id"))if(!Objects.equals(last.get(key),locator.get(key)))return false;
        switch(Objects.toString(locator.get("type"),"")){
            case "pdf":if(number(last,"page")!=number(locator,"page")||nonempty(last.get("bbox"))||nonempty(locator.get("bbox")))return false;break;
            case "slide":if(number(last,"slide")!=number(locator,"slide"))return false;break;
            case "section":if(number(last,"section")!=number(locator,"section"))return false;break;
            case "sheet":if(!Objects.equals(last.get("sheet"),locator.get("sheet"))||number(locator,"row_start")>number(last,"row_end")+1||number(locator,"row_end")<number(last,"row_start"))return false;union(last,locator,"row_start","row_end");break;
            case "text":if(number(locator,"start")>number(last,"end")+2||number(locator,"end")<number(last,"start"))return false;union(last,locator,"start","end");break;
            default:return false;
        }
        String a=Objects.toString(last.get("quote"),""),b=Objects.toString(locator.get("quote"),"");String joined=a.isEmpty()?b:b.isEmpty()?a:a+" "+b;if(!joined.isEmpty())last.put("quote",joined);return true;
    }
    private static void union(Map<String,Object> a,Map<String,Object>b,String start,String end){long lo=Math.min(number(a,start),number(b,start)),hi=Math.max(number(a,end),number(b,end));if(lo==0)a.remove(start);else a.put(start,lo);if(hi==0)a.remove(end);else a.put(end,hi);}
    private static long number(Map<String,Object> value,String key){Object number=value.get(key);return number instanceof Number n?n.longValue():0;}
    private static boolean nonempty(Object value){return value instanceof Collection<?> collection&&!collection.isEmpty();}
    private static Map<String,Object> copy(Map<String,Object> source){var copy=new LinkedHashMap<>(source);if(source.get("bbox")instanceof List<?> list)copy.put("bbox",new ArrayList<>(list));return copy;}
}
