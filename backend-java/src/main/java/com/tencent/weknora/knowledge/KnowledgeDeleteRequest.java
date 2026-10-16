package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.util.*;

/** Go typed admission body, preserving first-value, duplicate/null and required-slice behavior. */
record KnowledgeDeleteRequest(String knowledgeBase,List<String> ids) {
  private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10000).maxNumberLength(Integer.MAX_VALUE).maxStringLength(Integer.MAX_VALUE).maxNameLength(Integer.MAX_VALUE).build()).build());
  static KnowledgeDeleteRequest decode(byte[] raw) {
    String kb="";List<String> ids=null;
    try {
      int length=GoJsonFirstValue.length(raw);
      try(var p=JSON.createParser(GoUtf8.decode(Arrays.copyOf(raw,length)))) {
        var token=p.nextToken();
        if(token!=JsonToken.VALUE_NULL) {
          if(token!=JsonToken.START_OBJECT)throw new IOException(wrong(p,"Go value of type handler.BatchDeleteKnowledgeRequest"));
          String field;while((field=p.nextFieldName())!=null) {
            p.nextToken();String key=fold(field);
            if(key.equals("kb_id")){if(p.currentToken()==JsonToken.VALUE_NULL)continue;if(p.currentToken()!=JsonToken.VALUE_STRING)throw new IOException(wrong(p,"Go struct field BatchDeleteKnowledgeRequest.kb_id of type string"));kb=surrogates(p.getText());}
            else if(key.equals("ids")){if(p.currentToken()==JsonToken.VALUE_NULL){ids=null;continue;}if(p.currentToken()!=JsonToken.START_ARRAY)throw new IOException(wrong(p,"Go struct field BatchDeleteKnowledgeRequest.ids of type []string"));var values=new ArrayList<String>();while(p.nextToken()!=JsonToken.END_ARRAY){if(p.currentToken()==JsonToken.VALUE_NULL)values.add("");else{if(p.currentToken()!=JsonToken.VALUE_STRING)throw new IOException(wrong(p,"Go struct field BatchDeleteKnowledgeRequest.ids of type string"));values.add(surrogates(p.getText()));}}ids=values;}
            else p.skipChildren();
          }
        }
      }
    }catch(IOException e){throw invalid(e.getMessage());}
    var errors=new ArrayList<String>();if(kb.isEmpty())errors.add(required("KBID"));if(ids==null)errors.add(required("IDs"));if(!errors.isEmpty())throw invalid(String.join("\n",errors));
    var unique=new LinkedHashSet<String>();for(String id:ids){String clean=KnowledgeUploadForms.goTrim(id);if(!clean.isEmpty())unique.add(clean);}
    if(unique.isEmpty())throw bad("ids cannot be empty");if(unique.size()>200)throw bad("too many ids (max 200 per batch)");
    return new KnowledgeDeleteRequest(kb,List.copyOf(unique));
  }
  private static String required(String field){return "Key: 'BatchDeleteKnowledgeRequest."+field+"' Error:Field validation for '"+field+"' failed on the 'required' tag";}
  private static String wrong(JsonParser p,String destination){String type=switch(p.currentToken()){case START_OBJECT->"object";case START_ARRAY->"array";case VALUE_STRING->"string";case VALUE_TRUE,VALUE_FALSE->"bool";case VALUE_NUMBER_INT,VALUE_NUMBER_FLOAT->"number";default->"null";};return "json: cannot unmarshal "+type+" into "+destination;}
  private static ApiException invalid(String message){return bad("Invalid request parameters: "+message);}
  static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000);}
  private static String fold(String value){StringBuilder out=new StringBuilder();value.codePoints().forEach(cp->out.appendCodePoint(cp==0x17f?'s':Character.toLowerCase(cp)));return out.toString();}
  private static String surrogates(String value){StringBuilder out=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1)))out.append(c).append(value.charAt(++i));else out.append(Character.isSurrogate(c)?'\ufffd':c);}return out.toString();}
}
