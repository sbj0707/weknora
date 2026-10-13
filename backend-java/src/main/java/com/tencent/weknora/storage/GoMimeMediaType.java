package com.tencent.weknora.storage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** MIME parameter handling translated from Go 1.26 mime; see storage/GO-MIME-LICENSE.txt. */
final class GoMimeMediaType {
    private GoMimeMediaType(){}
    static String normalize(String input){int split=input.indexOf(';');String base=StoragePaths.trim(split<0?input:input.substring(0,split));String type=GoMimeTypes.lower(base);int slash=type.indexOf('/');if(!(slash<0?token(type):token(type.substring(0,slash))&&token(type.substring(slash+1))))return null;
        Map<String,String> params=new TreeMap<>();Map<String,Map<String,String>> pieces=new TreeMap<>();String rest=split<0?"":input.substring(split);
        while(!StoragePaths.trim(rest).isEmpty()){
            rest=leftTrim(rest);if(StoragePaths.trim(rest).equals(";"))break;if(!rest.startsWith(";"))return null;rest=leftTrim(rest.substring(1));int end=tokenEnd(rest);if(end==0)return null;String name=GoMimeTypes.lower(rest.substring(0,end));rest=leftTrim(rest.substring(end));if(!rest.startsWith("="))return null;rest=leftTrim(rest.substring(1));String value;
            if(rest.startsWith("\"")){StringBuilder b=new StringBuilder();int i=1;boolean closed=false;for(;i<rest.length();i++){char c=rest.charAt(i);if(c=='"'){i++;closed=true;break;}if(c=='\r'||c=='\n')return null;if(c=='\\'&&i+1<rest.length()&&special(rest.charAt(i+1)))c=rest.charAt(++i);b.append(c);}if(!closed)return null;value=b.toString();rest=rest.substring(i);
            }else{int n=tokenEnd(rest);if(n==0)return null;value=rest.substring(0,n);rest=rest.substring(n);}
            int star=name.indexOf('*');Map<String,String> target=star<0?params:pieces.computeIfAbsent(name.substring(0,star),key->new TreeMap<>());String old=target.put(name,value);if(old!=null&&!old.equals(value))return null;
        }
        for(var entry:pieces.entrySet()){String name=entry.getKey();Map<String,String> parts=entry.getValue();if(parts.containsKey(name+"*")){String decoded=decode2231(parts.get(name+"*"));if(decoded!=null)params.put(name,decoded);continue;}StringBuilder b=new StringBuilder();boolean valid=false;for(int n=0;;n++){String key=name+"*"+n;String value=parts.get(key);if(value!=null){valid=true;b.append(value);continue;}value=parts.get(key+"*");if(value==null)break;valid=true;String decoded=n==0?decode2231(value):percent(value);if(decoded!=null)b.append(decoded);}if(valid)params.put(name,b.toString());}
        if(!input.startsWith("text/")||!params.getOrDefault("charset","").isEmpty())return input;
        // Go setExtensionType passes the original parameterized input to FormatMediaType.
        // When charset is empty, an existing semicolon therefore makes that formatter return empty.
        if(split>=0)return "";
        return type+"; charset=utf-8";
    }
    private static String leftTrim(String value){int i=0;while(i<value.length()){int cp=value.codePointAt(i);if(!StoragePaths.trim(new String(Character.toChars(cp))).isEmpty())break;i+=Character.charCount(cp);}return value.substring(i);}
    private static String decode2231(String value){int first=value.indexOf('\'');if(first<0)return null;String charset=GoMimeTypes.lower(value.substring(0,first));int second=value.indexOf('\'',first+1);if(second<0||!Set.of("us-ascii","utf-8").contains(charset))return null;return percent(value.substring(second+1));}
    private static String percent(String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);var out=new ByteArrayOutputStream();for(int i=0;i<bytes.length;i++){int c=Byte.toUnsignedInt(bytes[i]);if(c=='%'){if(i+2>=bytes.length)return null;int h=hex(bytes[++i]),l=hex(bytes[++i]);if(h<0||l<0)return null;c=h*16+l;}out.write(c);}return out.toString(StandardCharsets.UTF_8);}
    private static int hex(byte value){int c=Byte.toUnsignedInt(value);return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;}
    private static int tokenEnd(String value){int i=0;while(i<value.length()&&value.charAt(i)>32&&value.charAt(i)<127&&!special(value.charAt(i)))i++;return i;}
    private static boolean token(String value){return !value.isEmpty()&&tokenEnd(value)==value.length();}
    private static boolean special(char c){return "()<>@,;:\\\"/[]?=".indexOf(c)>=0;}
}
