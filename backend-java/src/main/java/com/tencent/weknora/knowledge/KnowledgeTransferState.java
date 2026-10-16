package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Go map[string]RawMessage outer keys and anonymous typed transfer-state decoding. */
final class KnowledgeTransferState {
  private static final String TRANSFER_TYPE="struct { Operation access.KBTransferOperation \"json:\\\"operation\\\"\"; Phase string \"json:\\\"phase\\\"\" }";
  private final ObjectMapper json;
  KnowledgeTransferState(ObjectMapper json){this.json=json;}
  boolean moving(Object stored){
    if(stored==null)return false;
    String raw=stored instanceof byte[] bytes?GoUtf8.decode(bytes):stored.toString();
    if(raw.isEmpty())return false;
    try{
      byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);int end=GoJsonFirstValue.length(bytes);
      for(int i=end;i<bytes.length;i++)if(!space(bytes[i]))throw new IOException("invalid character "+quote(bytes[i])+" after top-level value");
      String selected=null;
      try(var p=json.createParser(raw)){
        var root=p.nextToken();
        if(root!=JsonToken.VALUE_NULL){
          if(root!=JsonToken.START_OBJECT)throw wrong(root,"Go value of type map[string]json.RawMessage");
          String field;while((field=p.nextFieldName())!=null){p.nextToken();int start=Math.toIntExact(p.currentTokenLocation().getCharOffset());p.skipChildren();int stop=Math.toIntExact(p.currentLocation().getCharOffset());if(field.equals("_knowledge_transfer"))selected=raw.substring(start,stop);}
        }
      }
      if(selected==null)return false;
      String operation="",phase="",firstError=null;
      try(var p=json.createParser(selected)){
        var token=p.nextToken();if(token==JsonToken.VALUE_NULL)return false;
        if(token!=JsonToken.START_OBJECT)throw wrong(token,"Go value of type "+TRANSFER_TYPE);
        String field;while((field=p.nextFieldName())!=null){
          token=p.nextToken();String folded=fold(field);
          if(!folded.equals("operation")&&!folded.equals("phase")){p.skipChildren();continue;}
          if(token==JsonToken.VALUE_NULL)continue;
          if(token!=JsonToken.VALUE_STRING){if(firstError==null)firstError=wrong(token,"Go struct field ."+field+" of type "+(folded.equals("operation")?"access.KBTransferOperation":"string")).getMessage();p.skipChildren();continue;}
          String value=surrogates(p.getText());if(folded.equals("operation"))operation=value;else phase=value;
        }
      }
      if(firstError!=null)throw new IOException(firstError);
      return operation.equals("move")&&phase.equals("moving");
    }catch(IOException e){String message=e.getMessage();if(message.equals("EOF")||message.equals("unexpected EOF"))message="unexpected end of JSON input";throw new IllegalArgumentException(message,e);}
  }
  private static IOException wrong(JsonToken token,String target){String kind=switch(token){case START_OBJECT->"object";case START_ARRAY->"array";case VALUE_TRUE,VALUE_FALSE->"bool";case VALUE_STRING->"string";default->"number";};return new IOException("json: cannot unmarshal "+kind+" into "+target);}
  private static boolean space(byte value){return value==32||value==9||value==10||value==13;}
  private static String quote(byte value){int c=value&255;return c=='\''?"'\\\''":"'"+Character.toString(c)+"'";}
  private static String fold(String value){StringBuilder out=new StringBuilder();value.codePoints().forEach(cp->out.appendCodePoint(cp==0x17f?'s':Character.toLowerCase(cp)));return out.toString();}
  private static String surrogates(String value){StringBuilder out=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1)))out.append(c).append(value.charAt(++i));else out.append(Character.isSurrogate(c)?'\ufffd':c);}return out.toString();}
}
