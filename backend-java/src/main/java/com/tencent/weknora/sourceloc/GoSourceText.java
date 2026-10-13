package com.tencent.weknora.sourceloc;

import com.fasterxml.jackson.databind.*;
import com.google.re2j.Pattern;
import java.util.*;

/** Go HTML entity and Unicode rules for stored source evidence. Generated tables retain Go's license. */
final class GoSourceText {
    private static final Map<String,String> ENTITIES=new HashMap<>();
    private static final int[][] LETTER,DIGIT;private static final Map<Integer,Integer> LOWER=new HashMap<>();
    private static final int[] WINDOWS={0x20ac,0x81,0x201a,0x192,0x201e,0x2026,0x2020,0x2021,0x2c6,0x2030,0x160,0x2039,0x152,0x8d,0x17d,0x8f,0x90,0x2018,0x2019,0x201c,0x201d,0x2022,0x2013,0x2014,0x2dc,0x2122,0x161,0x203a,0x153,0x9d,0x17e,0x178};
    private static final Pattern IMAGE=Pattern.compile("!\\[[^\\]\\n]*\\]\\([^)\\n]*\\)"),LINK=Pattern.compile("\\[([^\\]\\n]*)\\]\\([^)\\n]*\\)"),TAG=Pattern.compile("</?[a-zA-Z][^>\\n]*>|<!--.*?-->"),SPACE=Pattern.compile("\\s+");
    static {
        try(var entities=GoSourceText.class.getResourceAsStream("/go-html-entities.json");var unicode=GoSourceText.class.getResourceAsStream("/go-source-unicode.json")){
            var mapper=new ObjectMapper();mapper.readTree(entities).fields().forEachRemaining(e->ENTITIES.put(e.getKey(),e.getValue().asText()));
            var tables=mapper.readTree(unicode);LETTER=ranges(tables.get("letter"));DIGIT=ranges(tables.get("digit"));for(JsonNode pair:tables.get("lower"))LOWER.put(pair.get(0).asInt(),pair.get(1).asInt());
        }catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    private static int[][] ranges(JsonNode array){int[][] out=new int[array.size()][3];for(int i=0;i<out.length;i++)for(int j=0;j<3;j++)out[i][j]=array.get(i).get(j).asInt();return out;}
    static boolean letter(int rune){return includes(LETTER,rune);}static boolean digit(int rune){return includes(DIGIT,rune);}
    private static boolean includes(int[][] ranges,int rune){for(int[] range:ranges){if(rune<range[0])return false;if(rune<=range[1])return (rune-range[0])%range[2]==0;}return false;}
    static String trim(String value){int start=0,end=value.length();while(start<end&&space(value.codePointAt(start)))start+=Character.charCount(value.codePointAt(start));while(end>start&&space(value.codePointBefore(end)))end-=Character.charCount(value.codePointBefore(end));return value.substring(start,end);}
    private static boolean space(int cp){return cp>=9&&cp<=13||cp==32||cp==0x85||cp==0xa0||cp==0x1680||cp>=0x2000&&cp<=0x200a||cp==0x2028||cp==0x2029||cp==0x202f||cp==0x205f||cp==0x3000;}
    static String quote(String text){text=IMAGE.matcher(text).replaceAll(" ");text=LINK.matcher(text).replaceAll("$1");text=TAG.matcher(text).replaceAll(" ");return trim(SPACE.matcher(unescape(text)).replaceAll(" "));}
    static String unescape(String text){
        var out=new StringBuilder();
        for(int start=0;start<text.length();){
            if(text.charAt(start)!='&'){out.append(text.charAt(start++));continue;}
            int remaining=text.length()-start,i=1;
            if(remaining<=1){out.append('&');start++;continue;}
            if(text.charAt(start+i)=='#'){
                if(remaining<=3){out.append('&');start++;continue;}
                i++;boolean hex=text.charAt(start+i)=='x'||text.charAt(start+i)=='X';if(hex)i++;
                int rune=0;
                while(i<remaining){char c=text.charAt(start+i++);int digit=c>='0'&&c<='9'?c-'0':hex&&c>='a'&&c<='f'?c-'a'+10:hex&&c>='A'&&c<='F'?c-'A'+10:-1;
                    if(digit>=0){rune=(hex?16:10)*rune+digit;continue;}if(c!=';')i--;break;}
                if(i<=3){out.append('&');start++;continue;}
                if(rune>=0x80&&rune<=0x9f)rune=WINDOWS[rune-0x80];
                else if(rune<=0||rune>=0xd800&&rune<=0xdfff||rune>0x10ffff)rune=0xfffd;
                out.appendCodePoint(rune);start+=i;continue;
            }
            while(i<remaining){char c=text.charAt(start+i++);if(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9')continue;if(c!=';')i--;break;}
            String name=text.substring(start+1,start+i),decoded=ENTITIES.get(name);
            if(decoded!=null){out.append(decoded);start+=i;continue;}
            int length=Math.min(name.length()-1,6);boolean found=false;
            for(int n=length;n>1;n--){decoded=ENTITIES.get(name.substring(0,n));if(decoded!=null){out.append(decoded);start+=n+1;found=true;break;}}
            if(!found){out.append(text,start,start+i);start+=i;}
        }
        return out.toString();
    }
    record Normalized(String text,int[] position,int[] end){}
    static boolean visible(String markdown){return !normalize(markdown).text().isEmpty();}
    static Normalized normalize(String markdown){
        int[] runes=markdown.codePoints().toArray();StringBuilder text=new StringBuilder();List<Integer> positions=new ArrayList<>(),ends=new ArrayList<>();
        for(int i=0;i<runes.length;i++){
            int rune=runes[i];
            if(rune=='<'&&i+1<runes.length&&(asciiLetter(runes[i+1])||runes[i+1]=='/'||runes[i+1]=='!')){int end=index(runes,'>',i+1,4096);if(end>0){i=end;continue;}}
            if(rune==']'&&i+1<runes.length&&runes[i+1]=='('){int end=index(runes,')',i+2,1<<20);if(end>0){i=end;continue;}}
            if(rune=='&'){int end=index(runes,';',i+1,32);if(end>0){String raw=new String(runes,i,end-i+1),decoded=unescape(raw);if(!decoded.equals(raw)){
                int[] chars=decoded.codePoints().toArray(),context=new int[chars.length+2];context[0]=i>0?runes[i-1]:' ';context[context.length-1]=end+1<runes.length?runes[end+1]:' ';System.arraycopy(chars,0,context,1,chars.length);
                for(int j=1;j+1<context.length;j++)emit(text,positions,ends,match(context,j),i,end+1);i=end;continue;}}}
            emit(text,positions,ends,match(runes,i),i,i+1);
        }return new Normalized(text.toString(),positions.stream().mapToInt(Integer::intValue).toArray(),ends.stream().mapToInt(Integer::intValue).toArray());
    }
    static String normalizeKey(String value){int[] runes=value.codePoints().toArray();StringBuilder out=new StringBuilder();for(int i=0;i<runes.length;i++){int matched=match(runes,i);if(matched>=0)out.appendCodePoint(matched);}return out.toString();}
    private static void emit(StringBuilder text,List<Integer> positions,List<Integer> ends,int rune,int start,int end){if(rune<0)return;int before=text.length();text.appendCodePoint(rune);for(int i=before;i<text.length();i++){positions.add(start);ends.add(end);}}
    private static int match(int[] runes,int i){int rune=runes[i];if(rune>=0xff01&&rune<=0xff5e)rune-=0xfee0;
        if("<>=≤≥≠".indexOf(rune)>=0)return rune;
        if(".,:/+−-%‰".indexOf(rune)>=0){int before=i-1,after=i+1;while(before>=0&&(runes[before]==' '||runes[before]=='\t'))before--;while(after<runes.length&&(runes[after]==' '||runes[after]=='\t'))after++;
            boolean previous=before>=0&&digit(runes[before]),next=after<runes.length&&digit(runes[after]);if((".,:/".indexOf(rune)>=0&&previous&&next)||("-+−".indexOf(rune)>=0&&next)||("%‰".indexOf(rune)>=0&&previous))return rune=='−'?'-':rune;
        }return letter(rune)||digit(rune)?LOWER.getOrDefault(rune,rune):-1;
    }
    private static boolean asciiLetter(int c){return c>='a'&&c<='z'||c>='A'&&c<='Z';}
    private static int index(int[] runes,int rune,int from,int limit){for(int i=from;i<Math.min(runes.length,from+limit);i++){if(runes[i]==rune)return i;if(runes[i]=='\n'&&i+1<runes.length&&runes[i+1]=='\n')return -1;}return -1;}
}
