package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.IOException;
import java.util.*;

record ChunkEditRequest(String content,Boolean enabled,Long expected,Long revision) {
  static ChunkEditRequest decode(byte[] bytes,ObjectMapper json,boolean revert) {
    String name=revert?"RevertChunkRequest":"UpdateChunkRequest";
    var schema=json.createObjectNode().put("kind","struct").put("name",name).put("type","handler."+name);
    var fields=schema.putArray("fields");
    for(String field:revert?List.of("revision","expected_revision"):List.of("content","is_enabled","expected_revision")) {
      String type=field.equals("content")?"string":field.equals("is_enabled")?"bool":"int";
      fields.add(json.createObjectNode().put("name",field).set("schema",json.createObjectNode().put("kind","pointer").put("type","*"+type).set("elem",json.createObjectNode().put("kind",type).put("type",type))));
    }
    try {
      var value=new GoUploadJson(json).decode(GoUtf8.decode(Arrays.copyOf(bytes,GoJsonFirstValue.length(bytes))),schema);
      if(revert&&!value.hasNonNull("revision"))throw bad("Key: 'RevertChunkRequest.Revision' Error:Field validation for 'Revision' failed on the 'required' tag");
      Long revision=value.hasNonNull("revision")?value.get("revision").longValue():null;
      if(revision!=null&&revision<0)throw bad("revision must be a non-negative integer");
      return new ChunkEditRequest(value.hasNonNull("content")?value.get("content").textValue():null,value.hasNonNull("is_enabled")?value.get("is_enabled").booleanValue():null,value.hasNonNull("expected_revision")?value.get("expected_revision").longValue():null,revision);
    }catch(IOException failure){throw bad(failure.getMessage());}
  }
  private static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000);}
}
