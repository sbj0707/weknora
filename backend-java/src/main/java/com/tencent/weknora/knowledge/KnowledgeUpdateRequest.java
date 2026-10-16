package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pointer strings and RawMessage follow encoding/json, including null and duplicate fields. */
record KnowledgeUpdateRequest(String title,String description,String customMetadata) {
  static KnowledgeUpdateRequest decode(byte[] bytes,ObjectMapper json) {
    var schema=json.createObjectNode().put("kind","struct").put("name","UpdateKnowledgeRequest").put("type","handler.UpdateKnowledgeRequest");
    var fields=schema.putArray("fields");
    for(String name:List.of("title","description"))fields.add(json.createObjectNode().put("name",name).set("schema",json.createObjectNode().put("kind","pointer").put("type","*string").set("elem",json.createObjectNode().put("kind","string").put("type","string"))));
    try {
      String raw=GoUtf8.decode(Arrays.copyOf(bytes,GoJsonFirstValue.length(bytes)));
      JsonNode value=new GoUploadJson(json).decode(raw,schema);String metadata=null;
      try(var parser=json.createParser(raw)) {
        if(parser.nextToken()==JsonToken.START_OBJECT)while(parser.nextToken()!=JsonToken.END_OBJECT) {
          String key=parser.currentName();parser.nextToken();int start=(int)parser.currentTokenLocation().getCharOffset();
          parser.skipChildren();parser.finishToken();int end=(int)parser.currentLocation().getCharOffset();
          if("custom_metadata".equalsIgnoreCase(key))metadata=raw.substring(start,end);
        }
      }
      return new KnowledgeUpdateRequest(value.hasNonNull("title")?value.get("title").textValue():null,value.hasNonNull("description")?value.get("description").textValue():null,metadata);
    }catch(IOException error){throw new ApiException(400,"BAD_REQUEST",error.getMessage()).withAppCode(1000);}
  }
  static JsonNode metadata(String raw,ObjectMapper json) throws IOException {
    try(var parser=json.createParser(raw)) {
      JsonToken token=parser.nextToken();
      if(token!=JsonToken.VALUE_NULL&&token!=JsonToken.START_OBJECT)throw new IOException("json: cannot unmarshal "+kind(token)+" into Go value of type map[string]interface {}");
      var errors=new ArrayList<String>();JsonNode result=value(parser,json,errors);if(!errors.isEmpty())throw new IOException(errors.getFirst());return result;
    }
  }
  static JsonNode existingMetadata(String raw,ObjectMapper json){
    if(raw.isEmpty())return json.createObjectNode();
    try{GoUploadJson.valid(raw.getBytes(StandardCharsets.UTF_8));try(var parser=json.createParser(raw)){JsonToken token=parser.nextToken();if(token!=JsonToken.VALUE_NULL&&token!=JsonToken.START_OBJECT)return json.createObjectNode();return value(parser,json,new ArrayList<>());}}catch(IOException error){return json.createObjectNode();}
  }
  static void validate(JsonNode metadata) {
    if(metadata.size()>20)throw new IllegalArgumentException("custom_metadata supports at most 20 fields");
    metadata.fields().forEachRemaining(entry->{String key=entry.getKey();JsonNode value=entry.getValue();
      if(KnowledgeUploadForms.goTrim(key).isEmpty()||size(key)>64||size(GraphExtractionText.goValue(value))>1000)throw new IllegalArgumentException("invalid custom_metadata field "+quote(key));
      if(!(value.isTextual()||value.isNumber()||value.isBoolean()||value.isNull()))throw new IllegalArgumentException("custom_metadata field "+quote(key)+" must be a string, number, boolean, or null");
    });
  }
  private static JsonNode value(JsonParser p,ObjectMapper json,List<String> errors)throws IOException {
    JsonToken token=p.currentToken();
    if(token==JsonToken.START_OBJECT){var out=json.createObjectNode();while(p.nextToken()!=JsonToken.END_OBJECT){String key=GoUploadJson.unicode(p.currentName());p.nextToken();out.set(key,value(p,json,errors));}return out;}
    if(token==JsonToken.START_ARRAY){var out=json.createArrayNode();while(p.nextToken()!=JsonToken.END_ARRAY)out.add(value(p,json,errors));return out;}
    if(token.isNumeric()){String text=p.getText();double d=Double.parseDouble(text);if(!Double.isFinite(d)){errors.add("json: cannot unmarshal number "+text+" into Go value of type float64");return NullNode.instance;}return DoubleNode.valueOf(d);}
    if(token==JsonToken.VALUE_STRING)return TextNode.valueOf(GoUploadJson.unicode(p.getText()));
    if(token==JsonToken.VALUE_TRUE||token==JsonToken.VALUE_FALSE)return BooleanNode.valueOf(token==JsonToken.VALUE_TRUE);
    return NullNode.instance;
  }
  private static String kind(JsonToken token){return token==JsonToken.START_ARRAY?"array":token==JsonToken.VALUE_STRING?"string":token.isNumeric()?"number":"bool";}
  private static int size(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
  static boolean equivalent(JsonNode a,JsonNode b){
    if(a.isNumber()&&b.isNumber())return a.doubleValue()==b.doubleValue();
    if(a.isObject()&&b.isObject()){if(a.size()!=b.size())return false;var fields=a.fields();while(fields.hasNext()){var field=fields.next();if(!b.has(field.getKey())||!equivalent(field.getValue(),b.get(field.getKey())))return false;}return true;}
    if(a.isArray()&&b.isArray()){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!equivalent(a.get(i),b.get(i)))return false;return true;}
    return a.equals(b);
  }
  // strconv.Quote uses short escapes for ASCII controls and Unicode escapes for non-printable runes.
  private static String quote(String value){var out=new StringBuilder("\"");value.codePoints().forEach(cp->{switch(cp){case 7->out.append("\\a");case 8->out.append("\\b");case 9->out.append("\\t");case 10->out.append("\\n");case 11->out.append("\\v");case 12->out.append("\\f");case 13->out.append("\\r");case 34->out.append("\\\"");case 92->out.append("\\\\");default->{int type=Character.getType(cp);if(cp<32||cp==127)out.append(String.format("\\x%02x",cp));else if(cp!=32&&(type==Character.CONTROL||type==Character.FORMAT||type==Character.PRIVATE_USE||type==Character.SURROGATE||type==Character.UNASSIGNED||type==Character.LINE_SEPARATOR||type==Character.PARAGRAPH_SEPARATOR||type==Character.SPACE_SEPARATOR))out.append(String.format(cp<=65535?"\\u%04x":"\\U%08x",cp));else out.appendCodePoint(cp);}}});return out.append('"').toString();}
}
