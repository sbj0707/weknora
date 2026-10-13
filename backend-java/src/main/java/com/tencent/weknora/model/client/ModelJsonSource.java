package com.tencent.weknora.model.client;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Go Anthropic typed binding: aliases, duplicates, nulls, first type errors and RawMessage literals. */
final class ModelJsonSource {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10000).maxNumberLength(Integer.MAX_VALUE).maxStringLength(Integer.MAX_VALUE).maxNameLength(Integer.MAX_VALUE).build()).build());
    final JsonNode root;private final Map<String,String> raw=new HashMap<>();private String firstError;private final String source;
    private enum Schema { RESPONSE("response"),BLOCK("responseBlock"),USAGE("usageBlock"),STREAM("streamEvent"),MESSAGE(""),DELTA(""),ERROR("");final String name;Schema(String name){this.name=name;}}
    ModelJsonSource(String source,boolean stream)throws IOException{this.source=source;syntax(source);try(JsonParser p=JSON.createParser(source)){p.nextToken();root=readObject(p,stream?Schema.STREAM:Schema.RESPONSE,"","",null,stream?"streamEvent":"response");}if(firstError!=null)throw new IOException(firstError);}
    String raw(String path){return raw.getOrDefault(path,"");}
    private static void syntax(String source)throws IOException{byte[] bytes=source.getBytes(StandardCharsets.UTF_8);int end;try{end=GoJsonFirstValue.length(bytes);}catch(IOException ex){if(ex.getMessage().equals("EOF")||ex.getMessage().equals("unexpected EOF"))throw new IOException("unexpected end of JSON input");throw ex;}for(int i=end;i<bytes.length;i++){int c=bytes[i]&255;if(c!=32&&c!=9&&c!=10&&c!=13)throw new IOException("invalid character "+quote(c)+" after top-level value");}}
    private static String quote(int c){return switch(c){case '\n'->"'\\n'";case '\r'->"'\\r'";case '\t'->"'\\t'";case '\\'->"'\\\\'";case '\''->"'\\''";case '"'->"'\"'";default->c>=32&&c<127?"'"+(char)c+"'":String.format("'\\x%02x'",c);};}
    private JsonNode readObject(JsonParser p,Schema schema,String path,String rawPath,JsonNode previous,String assignmentOwner)throws IOException{
        if(p.currentToken()==JsonToken.VALUE_NULL)return previous!=null?previous:NullNode.instance;
        if(p.currentToken()!=JsonToken.START_OBJECT){typeError(p,assignmentOwner,path,type(schema),path.isEmpty());p.skipChildren();return previous!=null?previous:JSON.createObjectNode();}
        ObjectNode out=previous!=null&&previous.isObject()?(ObjectNode)previous:JSON.createObjectNode();String original;
        while((original=p.nextFieldName())!=null){p.nextToken();String key=original.toLowerCase(Locale.ROOT).replace("\u017f","s"),at=path.isEmpty()?key:path+"."+key,rp=rawPath+"/"+key;Schema child=child(schema,key);if(child!=null){boolean pointer=pointer(schema,key);if(pointer&&p.currentToken()==JsonToken.VALUE_NULL)out.set(key,NullNode.instance);else out.set(key,readObject(p,child,at,rp,out.get(key),schema.name));}
            else if(schema==Schema.RESPONSE&&key.equals("content")){if(p.currentToken()==JsonToken.VALUE_NULL)out.set(key,NullNode.instance);else if(p.currentToken()!=JsonToken.START_ARRAY){typeError(p,schema.name,at,"[]anthropicmessages.responseBlock",false);p.skipChildren();}else{ArrayNode array=JSON.createArrayNode();int index=0;while(p.nextToken()!=JsonToken.END_ARRAY)array.add(readObject(p,Schema.BLOCK,at,rp+"/"+index++,null,schema.name));out.set(key,array);}}
            else if(schema==Schema.BLOCK&&key.equals("input")){int start=(int)p.currentTokenLocation().getCharOffset();JsonNode input=JSON.readTree(p);int end=(int)p.currentLocation().getCharOffset();raw.put(rp,source.substring(start,end));out.set(key,input);}
            else if(isString(schema,key)){if(p.currentToken()==JsonToken.VALUE_NULL)continue;if(p.currentToken()!=JsonToken.VALUE_STRING){typeError(p,schema.name,at,"string",false);p.skipChildren();}else out.put(key,p.getText());}
            else if(isInt(schema,key)){boolean ptr=schema==Schema.USAGE&&(key.equals("cache_read_input_tokens")||key.equals("cache_creation_input_tokens"));if(p.currentToken()==JsonToken.VALUE_NULL){if(ptr)out.set(key,NullNode.instance);continue;}if(p.currentToken()!=JsonToken.VALUE_NUMBER_INT){typeError(p,schema.name,at,"int",false);p.skipChildren();}else{BigInteger value=p.getBigIntegerValue();if(value.compareTo(BigInteger.valueOf(Long.MIN_VALUE))<0||value.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)typeError(p,schema.name,at,"int",false);else out.put(key,value.longValue());}}
            else p.skipChildren();
        }return out;
    }
    private void typeError(JsonParser p,String owner,String path,String expected,boolean root)throws IOException{if(firstError!=null)return;String value=switch(p.currentToken()){case START_OBJECT->"object";case START_ARRAY->"array";case VALUE_TRUE,VALUE_FALSE->"bool";case VALUE_STRING->"string";case VALUE_NUMBER_INT,VALUE_NUMBER_FLOAT->expected.equals("int")?"number "+p.getText():"number";default->"null";};firstError="json: cannot unmarshal "+value+" into "+(root?"Go value of type ":"Go struct field "+owner+"."+path+" of type ")+expected;}
    private static Schema child(Schema schema,String key){return switch(schema){case RESPONSE->switch(key){case "usage"->Schema.USAGE;case "error"->Schema.ERROR;default->null;};case STREAM->switch(key){case "content_block"->Schema.BLOCK;case "message"->Schema.MESSAGE;case "delta"->Schema.DELTA;case "usage"->Schema.USAGE;case "error"->Schema.ERROR;default->null;};case MESSAGE->key.equals("usage")?Schema.USAGE:null;default->null;};}
    private static boolean pointer(Schema schema,String key){return schema==Schema.STREAM||schema==Schema.RESPONSE&&key.equals("error");}
    private static boolean isString(Schema schema,String key){return switch(schema){case RESPONSE->Set.of("type","stop_reason").contains(key);case STREAM->key.equals("type");case BLOCK->Set.of("type","text","id","name","thinking","signature","data").contains(key);case DELTA->Set.of("type","text","thinking","signature","stop_reason","partial_json").contains(key);case ERROR->Set.of("type","message").contains(key);default->false;};}
    private static boolean isInt(Schema schema,String key){return schema==Schema.STREAM&&key.equals("index")||schema==Schema.USAGE&&Set.of("input_tokens","output_tokens","cache_creation_input_tokens","cache_read_input_tokens").contains(key);}
    private static String type(Schema schema){return switch(schema){case MESSAGE->"struct { Usage anthropicmessages.usageBlock \"json:\\\"usage\\\"\" }";case ERROR->"struct { Type string \"json:\\\"type\\\"\"; Message string \"json:\\\"message\\\"\" }";case DELTA->"struct { Type string \"json:\\\"type\\\"\"; Text string \"json:\\\"text\\\"\"; Thinking string \"json:\\\"thinking\\\"\"; Signature string \"json:\\\"signature\\\"\"; StopReason string \"json:\\\"stop_reason\\\"\"; PartialJSON string \"json:\\\"partial_json\\\"\" }";default->"anthropicmessages."+schema.name;};}
}
