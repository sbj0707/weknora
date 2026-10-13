package com.tencent.weknora.model.client;

import com.tencent.weknora.docreader.GoUtf8;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Go's incremental byte-oriented extractor, including incomplete and nonstandard escapes. */
final class JSONFieldExtractor {
    private final String field;private final StringBuilder buffer=new StringBuilder();private int start=-1,emitted;private boolean done;
    JSONFieldExtractor(String field){this.field=new String(field.getBytes(StandardCharsets.UTF_8),StandardCharsets.ISO_8859_1);}
    boolean isDone(){return done;}
    String feed(String delta){if(done)return "";buffer.append(new String(delta.getBytes(StandardCharsets.UTF_8),StandardCharsets.ISO_8859_1));if(start<0){int key=buffer.indexOf("\""+field+"\"");if(key<0)return "";int pos=key+field.length()+2;while(pos<buffer.length()){char c=buffer.charAt(pos);if(c==':'||c==' '||c=='\t'||c=='\n'||c=='\r'){pos++;continue;}if(c!='\"')return "";start=pos+1;break;}if(start<0)return "";}
        int end=start+emitted;while(end<buffer.length()){char c=buffer.charAt(end);if(c=='\\'){if(end+1>=buffer.length())break;int size=buffer.charAt(end+1)=='u'?6:2;if(end+size>buffer.length())break;end+=size;}else if(c=='\"'){done=true;break;}else end++;}int previous=start+emitted;if(end<=previous)return "";String chunk=buffer.substring(previous,end);emitted=end-start;return unescape(chunk);
    }
    private static String unescape(String text){ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<text.length();){char c=text.charAt(i);if(c!='\\'||i+1>=text.length()){out.write(c);i++;continue;}char next=text.charAt(i+1);switch(next){case '"':case '\\':case '/':out.write(next);i+=2;break;case 'n':out.write('\n');i+=2;break;case 'r':out.write('\r');i+=2;break;case 't':out.write('\t');i+=2;break;case 'b':out.write('\b');i+=2;break;case 'f':out.write('\f');i+=2;break;case 'u':if(i+6<=text.length()){int cp=0;String raw=text.substring(i+2,i+6);String decoded=GoUtf8.decode(raw.getBytes(StandardCharsets.ISO_8859_1));for(int h:decoded.codePoints().toArray()){cp<<=4;int digit=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;if(digit>=0)cp+=digit;}String value=cp<0||cp>0x10ffff||cp>=0xd800&&cp<=0xdfff?"\ufffd":new String(Character.toChars(cp));out.writeBytes(value.getBytes(StandardCharsets.UTF_8));i+=6;}else{out.write(c);i++;}break;default:out.write(c);i++;}}
        return GoUtf8.decode(out.toByteArray());
    }
}
