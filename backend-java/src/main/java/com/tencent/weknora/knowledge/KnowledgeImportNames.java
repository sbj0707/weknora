package com.tencent.weknora.knowledge;
import com.google.re2j.Pattern;
import com.tencent.weknora.storage.StoragePaths;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Upload display names and folder paths are domain labels, never physical storage paths. */
public final class KnowledgeImportNames {
    private KnowledgeImportNames(){}
    public record RelativePath(String folder,String filename){}
    public record Validated(String value,boolean valid){}
    private static final List<Pattern> XSS=List.of("<script[^>]*>.*?</script>","<iframe[^>]*>.*?</iframe>","<object[^>]*>.*?</object>","<embed[^>]*>.*?</embed>","<embed[^>]*>","<form[^>]*>.*?</form>","<input[^>]*>","<button[^>]*>","javascript:","vbscript:","onload\\s*=","onerror\\s*=","onclick\\s*=","onmouseover\\s*=","onfocus\\s*=","onblur\\s*=").stream().map(s->Pattern.compile("(?i)"+s)).toList();
    public static RelativePath split(String input){String name=input.replace('\\','/');int slash=name.lastIndexOf('/');return slash<0?new RelativePath("",StoragePaths.trim(name)):new RelativePath(normalizeFolder(name.substring(0,slash)),StoragePaths.trim(name.substring(slash+1)));}
    public static String normalizeFolder(String input){if(input.isEmpty())return "";List<String> parts=new ArrayList<>();for(String value:input.replace('\\','/').split("/",-1)){String segment=StoragePaths.trim(value);int end=segment.length();while(end>0&&(segment.charAt(end-1)=='.'||segment.charAt(end-1)==' '))end--;segment=segment.substring(0,end);if(segment.isEmpty()||segment.equals(".")||segment.equals(".."))continue;if(bytes(segment)>128)segment=StoragePaths.trim(prefix(segment,128));if(segment.isEmpty())continue;parts.add(segment);if(parts.size()>=16)break;}String result=String.join("/",parts);while(bytes(result)>1024&&!parts.isEmpty()){parts.removeLast();result=String.join("/",parts);}return result;}
    public static Validated validate(String input){for(int i=0;i<input.length();){char unit=input.charAt(i);int c=input.codePointAt(i);if(c<32&&c!=9&&c!=10&&c!=13||Character.isSurrogate(unit)&&Character.charCount(c)==1)return new Validated("",false);i+=Character.charCount(c);}for(var pattern:XSS)if(pattern.matcher(input).find())return new Validated("",false);return new Validated(StoragePaths.trim(input),true);}
    public static String sanitizeForLog(String input){StringBuilder result=new StringBuilder();input.codePoints().forEach(c->{if(c==9||c==10||c==13)result.append(' ');else if(c>=32)result.appendCodePoint(c>=0xd800&&c<=0xdfff?0xfffd:c);});return result.toString();}
    private static int bytes(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
    private static String prefix(String text,int maximum){int index=0,size=0;while(index<text.length()){int c=text.codePointAt(index),count=c<=0x7f?1:c<=0x7ff?2:c<=0xffff?3:4;if(size+count>maximum)break;size+=count;index+=Character.charCount(c);}return text.substring(0,index);}
}
