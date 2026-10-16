package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.util.*;

record ChunkQuestionRequest(String id,String question){
  static ChunkQuestionRequest decode(byte[] bytes,ObjectMapper json,boolean delete){
    try{
      var schema=json.createObjectNode().put("kind","struct").put("name","UpsertGeneratedQuestionRequest").put("type","handler.UpsertGeneratedQuestionRequest");var fields=schema.putArray("fields");
      for(String name:delete?List.of("question_id"):List.of("question_id","question"))fields.add(json.createObjectNode().put("name",name).set("schema",json.createObjectNode().put("kind","string").put("type","string")));
      var value=new GoUploadJson(json).decode(GoUtf8.decode(Arrays.copyOf(bytes,GoJsonFirstValue.length(bytes))),schema);
      String id=value.path("question_id").asText(""),question=value.path("question").asText("");
      if(delete&&id.isEmpty())throw new IllegalArgumentException("Question ID is required");
      if(!delete&&question.isEmpty())throw new IllegalArgumentException("Key: 'UpsertGeneratedQuestionRequest.Question' Error:Field validation for 'Question' failed on the 'required' tag");
      return new ChunkQuestionRequest(id,question);
    }catch(Exception failure){throw bad(delete?"Question ID is required":failure.getMessage());}
  }
  static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000);}
}
