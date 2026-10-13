package com.tencent.weknora.retrieval;

import java.util.*;
import com.google.re2j.Pattern;
public final class IndexText {
    private IndexText(){}
    private static final List<Pattern> PAYLOADS=List.of(
        Pattern.compile("<img\\b[^>]*\\bsrc=[\"']\\s*data:image/[a-z0-9.+-]+;base64,[^\"']+[\"'][^>]*>",Pattern.CASE_INSENSITIVE|Pattern.DOTALL),
        Pattern.compile("!\\[[^\\]]*\\]\\(\\s*data:image/[a-z0-9.+-]+;base64,[^)]+\\)",Pattern.CASE_INSENSITIVE|Pattern.DOTALL),
        Pattern.compile("data:image/[a-z0-9.+-]+;base64,[a-z0-9+/=]{200,}",Pattern.CASE_INSENSITIVE),
        Pattern.compile("data:[a-z0-9.+/-]+;base64,[a-z0-9+/=]{200,}",Pattern.CASE_INSENSITIVE));
    public static String embeddingInput(String content){
        String value=content;
        if(value.contains("base64,"))for(Pattern p:PAYLOADS)value=p.matcher(value).replaceAll("[image]");
        return value.codePointCount(0,value.length())>20000?value.substring(0,value.offsetByCodePoints(0,20000)):value;
    }
    /** Drop NUL and isolated UTF16 surrogates, preserving the actual replacement character. */
    public static String clean(String input){
        StringBuilder out=new StringBuilder();for(int i=0;i<input.length();){int cp=input.codePointAt(i);i+=Character.charCount(cp);if(cp!=0&&(cp<0xD800||cp>0xDFFF))out.appendCodePoint(cp);}return out.toString();
    }
    public static String bigrams(String text){
        List<String> parts=new ArrayList<>();List<Integer> han=new ArrayList<>();StringBuilder word=new StringBuilder();
        for(int cp:text.codePoints().toArray()){
            if(GoIndexUnicode.han(cp)){flushWord(parts,word);han.add(cp);}
            else if(GoIndexUnicode.space(cp)||GoIndexUnicode.punct(cp)||GoIndexUnicode.symbol(cp)){flushHan(parts,han);flushWord(parts,word);}
            else {flushHan(parts,han);word.appendCodePoint(cp);}
        }
        flushHan(parts,han);flushWord(parts,word);return String.join(" ",parts);
    }
    private static void flushWord(List<String> out,StringBuilder value){if(!value.isEmpty()){out.add(value.toString());value.setLength(0);}}
    private static void flushHan(List<String> out,List<Integer> value){
        if(value.size()==1)out.add(new String(Character.toChars(value.getFirst())));
        else for(int i=0;i+1<value.size();i++)out.add(new StringBuilder().appendCodePoint(value.get(i)).appendCodePoint(value.get(i+1)).toString());
        value.clear();
    }
    public static String ftsQuery(String query){
        String tokens=bigrams(query);if(tokens.isEmpty())return "";List<String> out=new ArrayList<>();
        for(String token:tokens.split(" ")){String quoted="\""+token.replace("\"","\"\"")+"\"";if(token.codePointCount(0,token.length())==1&&GoIndexUnicode.han(token.codePointAt(0)))quoted+="*";out.add(quoted);}
        return "content : ("+String.join(" OR ",out)+")";
    }
}
