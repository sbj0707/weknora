package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.math.BigInteger;
import java.util.*;

/** Preserves encoding/json first-value, duplicate-field, null and int64 behavior. */
final class KnowledgeTagRequestDecoder {
  record Change(String name,String color,Long sortOrder,List<Long> excluded) {}
  private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10000).maxNumberLength(Integer.MAX_VALUE).maxStringLength(Integer.MAX_VALUE).maxNameLength(Integer.MAX_VALUE).build()).build());
  static Change decode(byte[] raw,String operation) {
    boolean create=operation.equals("create"),delete=operation.equals("delete");
    String type=delete?"DeleteTagRequest":create?"createTagRequest":"updateTagRequest";
    String name=create?"":null,color=create?"":null;Long sort=create?0L:null;List<Long> excluded=List.of();
    try {
      int length;
      try{length=GoJsonFirstValue.length(raw);}catch(IOException e){if(delete&&e.getMessage().equals("EOF"))return new Change(null,null,null,List.of());throw e;}
      try(var p=JSON.createParser(GoUtf8.decode(Arrays.copyOf(raw,length)))) {
        var token=p.nextToken();
        if(token!=JsonToken.VALUE_NULL) {
          if(token!=JsonToken.START_OBJECT)throw new IOException(wrong(p,"Go value of type handler."+type,false));
          String field;
          while((field=p.nextFieldName())!=null) {
            p.nextToken();String key=fold(field);
            if(p.currentToken()==JsonToken.VALUE_NULL){if(delete&&key.equals("exclude_ids"))excluded=List.of();if(!create&&!delete){if(key.equals("name"))name=null;else if(key.equals("color"))color=null;else if(key.equals("sort_order"))sort=null;}continue;}
            if(delete&&key.equals("exclude_ids")) {
              if(p.currentToken()!=JsonToken.START_ARRAY)throw new IOException(wrong(p,"Go struct field "+type+".exclude_ids of type []int64",false));
              var ids=new ArrayList<Long>();
              while(p.nextToken()!=JsonToken.END_ARRAY) {if(p.currentToken()==JsonToken.VALUE_NULL)ids.add(0L);else ids.add(integer(p,"Go struct field "+type+".exclude_ids of type int64"));}
              excluded=ids;
            } else if(!delete&&Set.of("name","color","sort_order").contains(key)) {
              String destination="Go struct field "+type+"."+key+" of type "+(key.equals("sort_order")?"int":"string");
              if(key.equals("sort_order"))sort=integer(p,destination);
              else {if(p.currentToken()!=JsonToken.VALUE_STRING)throw new IOException(wrong(p,destination,false));String value=surrogates(p.getText());if(key.equals("name"))name=value;else color=value;}
            } else p.skipChildren();
          }
        }
      }
    }catch(IOException ex){throw bad(delete?"删除选项不合法":"请求参数不合法",delete?null:ex.getMessage());}
    if(create&&name.isEmpty())throw bad("请求参数不合法","Key: 'createTagRequest.Name' Error:Field validation for 'Name' failed on the 'required' tag");
    return new Change(name,color,sort,excluded);
  }
  private static long integer(JsonParser p,String destination)throws IOException {
    if(p.currentToken()!=JsonToken.VALUE_NUMBER_INT)throw new IOException(wrong(p,destination,true));
    BigInteger value=new BigInteger(p.getText());if(value.bitLength()>63)throw new IOException(wrong(p,destination,true));return value.longValue();
  }
  private static String wrong(JsonParser p,String destination,boolean numberLiteral)throws IOException {
    String type=switch(p.currentToken()){case START_OBJECT->"object";case START_ARRAY->"array";case VALUE_STRING->"string";case VALUE_TRUE,VALUE_FALSE->"bool";case VALUE_NUMBER_INT,VALUE_NUMBER_FLOAT->"number";default->"null";};
    return "json: cannot unmarshal "+type+(numberLiteral&&type.equals("number")?" "+p.getText():"")+" into "+destination;
  }
  static ApiException bad(String message,Object detail){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000).withDetails(detail);}
  private static String fold(String value){StringBuilder out=new StringBuilder();value.codePoints().forEach(cp->out.appendCodePoint(cp==0x17f?'s':Character.toLowerCase(cp)));return out.toString();}
  private static String surrogates(String value){StringBuilder out=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1)))out.append(c).append(value.charAt(++i));else out.append(Character.isSurrogate(c)?'\ufffd':c);}return out.toString();}
}
