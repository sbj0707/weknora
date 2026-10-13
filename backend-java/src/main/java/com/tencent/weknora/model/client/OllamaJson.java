package com.tencent.weknora.model.client;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native Ollama SDK JSON normalization and exact supported typed field errors. */
final class OllamaJson {
    static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10000).maxStringLength(Integer.MAX_VALUE).maxNumberLength(Integer.MAX_VALUE).build()).build());
    private static final ThreadLocal<IdentityHashMap<JsonNode,String>> NUMBER_LITERALS=ThreadLocal.withInitial(IdentityHashMap::new);
    static String numberLiteral(JsonNode value){return NUMBER_LITERALS.get().getOrDefault(value,value.asText());}
    static JsonNode document(String text)throws IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);int end;
        try{end=GoJsonFirstValue.length(bytes);}catch(IOException ex){if(Set.of("EOF","unexpected EOF").contains(ex.getMessage()))throw new IOException("unexpected end of JSON input");throw ex;}
        for(int i=end;i<bytes.length;i++)if(!Set.of(9,10,13,32).contains(bytes[i]&255))throw new IOException("invalid character '"+(char)bytes[i]+"' after top-level value");
        JsonNode root=JSON.readTree(text);IdentityHashMap<JsonNode,String> numbers=new IdentityHashMap<>();
        try(JsonParser parser=JSON.createParser(text)){while(parser.nextToken()!=null)if(parser.currentToken().isNumeric()){JsonNode value=root.at(parser.getParsingContext().pathAsPointer());numbers.put(value,parser.getText());}}
        NUMBER_LITERALS.set(numbers);return root;
    }
    static void validateError(JsonNode n){object(n,"struct { Error string \"json:\\\"error,omitempty\\\"\"; SigninURL string \"json:\\\"signin_url,omitempty\\\"\" }","");string(n,"error",".error");string(n,"signin_url",".signin_url");}
    static void validateList(JsonNode n){object(n,"api.ListResponse","");JsonNode models=n.path("models");if(!models.isNull()&&!models.isMissingNode()&&!models.isArray())throw type(models,"ListResponse.models","[]api.ListModelResponse");for(JsonNode model:models){object(model,"api.ListModelResponse","ListResponse.models");string(model,"name","ListModelResponse.models.name");}}
    static void validatePull(JsonNode n){object(n,"api.ProgressResponse","");string(n,"status","ProgressResponse.status");for(String k:List.of("total","completed"))integer(n,k,"ProgressResponse."+k);}
    static void validateChat(JsonNode n){
        object(n,"api.ChatResponse","");for(String k:List.of("model","remote_model","remote_host","done_reason"))string(n,k,"ChatResponse."+k);JsonNode done=n.path("done");if(!done.isMissingNode()&&!done.isNull()&&!done.isBoolean())throw type(done,"ChatResponse.done","bool");
        for(String k:List.of("total_duration","load_duration","prompt_eval_count","prompt_eval_duration","eval_count","eval_duration"))integer(n,k,"ChatResponse.Metrics."+k);
        JsonNode message=n.path("message");object(message,"api.Message","ChatResponse.message");for(String k:List.of("role","content","thinking","tool_name","tool_call_id"))string(message,k,"ChatResponse.message."+k);
        JsonNode calls=message.path("tool_calls");if(!calls.isMissingNode()&&!calls.isNull()&&!calls.isArray())throw type(calls,"ChatResponse.message.tool_calls","[]api.ToolCall");
        for(JsonNode call:calls){object(call,"api.ToolCall","ChatResponse.message.tool_calls");string(call,"id","ChatResponse.message.tool_calls.id");JsonNode function=call.path("function");object(function,"api.ToolCallFunction","ChatResponse.message.tool_calls.function");string(function,"name","ChatResponse.message.tool_calls.function.name");integer(function,"index","ChatResponse.message.tool_calls.function.index");JsonNode args=function.path("arguments");if(!args.isMissingNode()&&!args.isNull()&&!args.isObject())throw type(args,"ChatResponse.message.tool_calls.function.arguments","orderedmap.OrderedMap[string,interface {}]");}
    }
    static String string(JsonNode n,String key,String field){JsonNode v=n.path(key);if(v.isMissingNode()||v.isNull())return "";if(!v.isTextual())throw type(v,field,"string");return v.asText();}
    static long integer(JsonNode n,String key,String field){JsonNode v=n.path(key);if(v.isMissingNode()||v.isNull())return 0;if(!v.isIntegralNumber()||!v.canConvertToLong())throw type(v,field,"int");return v.asLong();}
    private static void object(JsonNode n,String expected,String field){if(!n.isMissingNode()&&!n.isNull()&&!n.isObject())throw type(n,field,expected);}
    private static ModelClientException type(JsonNode value,String field,String expected){String kind=value.isObject()?"object":value.isArray()?"array":value.isBoolean()?"bool":value.isTextual()?"string":value.isNumber()?"number"+(expected.equals("int")?" "+numberLiteral(value):""):"null";return new ModelClientException("json: cannot unmarshal "+kind+" into "+(field.isEmpty()?"Go value of type ":"Go struct field "+field+" of type ")+expected);}
    static String arguments(JsonNode value){if(value.isMissingNode())return "{}";return encode(value,true);}
    private static String encode(JsonNode value,boolean ordered){
        if(value.isObject()){List<String> keys=new ArrayList<>();value.fieldNames().forEachRemaining(keys::add);if(!ordered)keys.sort(OllamaJson::compareKeys);List<String> fields=new ArrayList<>();for(String key:keys)fields.add(quote(key)+":"+encode(value.get(key),false));return "{"+String.join(",",fields)+"}";}
        if(value.isArray()){List<String> parts=new ArrayList<>();for(JsonNode v:value)parts.add(encode(v,false));return "["+String.join(",",parts)+"]";}
        if(value.isTextual())return quote(value.asText());if(value.isNumber()){double d=value.asDouble();if(d==0)return Double.doubleToRawLongBits(d)<0?"-0":"0";BigDecimal b=shortest(d);if(Math.abs(d)>=1e-6&&Math.abs(d)<1e21)return b.toPlainString();String s=b.toString().replace("E","e");int at=s.indexOf('e');if(at>=0){String exponent=s.substring(at+1);int e=Integer.parseInt(exponent);return s.substring(0,at)+"e"+(e>=0?"+":"")+e;}return s;}return value.toString();
    }
    private static int compareKeys(String left,String right){var a=left.codePoints().iterator();var b=right.codePoints().iterator();while(a.hasNext()&&b.hasNext()){int c=Integer.compare(a.nextInt(),b.nextInt());if(c!=0)return c;}return a.hasNext()?1:b.hasNext()?-1:0;}
    private static BigDecimal shortest(double value){BigDecimal exact=new BigDecimal(value);long bits=Double.doubleToRawLongBits(value);for(int digits=1;digits<=17;digits++){BigDecimal candidate=exact.round(new MathContext(digits,RoundingMode.HALF_EVEN));if(Double.doubleToRawLongBits(Double.parseDouble(candidate.toString()))==bits)return candidate.stripTrailingZeros();}return BigDecimal.valueOf(value).stripTrailingZeros();}
    private static String quote(String text){try{return JSON.writeValueAsString(text).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026").replace("\u2028","\\u2028").replace("\u2029","\\u2029");}catch(IOException ex){throw new ModelClientException("invalid tool arguments");}}
}
