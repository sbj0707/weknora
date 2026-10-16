package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.chunker.GoJsonFirstValue;
import com.tencent.weknora.docreader.GoUtf8;
import com.tencent.weknora.security.ApiException;
import java.io.*;
import java.math.*;
import java.util.*;

/** encoding/json SearchParams, including first-value and duplicate/null field behavior. */
public record HybridSearchRequest(ObjectNode values) {
  private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10000).maxNumberLength(Integer.MAX_VALUE).maxStringLength(Integer.MAX_VALUE).maxNameLength(Integer.MAX_VALUE).build()).build());
  private static final Set<String> STRINGS=Set.of("query_text");
  private static final Set<String> BOOLS=Set.of("disable_keywords_match","disable_vector_match","only_recommended","skip_context_enrichment");
  private static final Set<String> SLICES=Set.of("knowledge_ids","tag_ids","scope_tag_ids","knowledge_base_ids","query_embedding");
  private static final Set<String> FLOATS=Set.of("vector_threshold","keyword_threshold");
  public static HybridSearchRequest decode(byte[] raw) {
    var out=JSON.createObjectNode();
    try {
      int length=GoJsonFirstValue.length(raw);
      try(var p=JSON.createParser(GoUtf8.decode(Arrays.copyOf(raw,length)))) {
        var token=p.nextToken();
        if(token!=JsonToken.VALUE_NULL) {
          if(token!=JsonToken.START_OBJECT)throw new IOException(wrong(p,"Go value of type types.SearchParams",false));
          String field;
          while((field=p.nextFieldName())!=null) {
            p.nextToken();String key=fold(field);
            if(p.currentToken()==JsonToken.VALUE_NULL){if(SLICES.contains(key)||key.equals("rerank"))out.putNull(key);continue;}
            String type=STRINGS.contains(key)?"string":BOOLS.contains(key)?"bool":FLOATS.contains(key)?"float64":key.equals("match_count")?"int":"";
            if(!type.isEmpty())out.set(key,scalar(p,type,"SearchParams."+key));
            else if(SLICES.contains(key)) {
              String element=key.equals("query_embedding")?"float32":"string";
              if(p.currentToken()!=JsonToken.START_ARRAY)throw new IOException(wrong(p,"Go struct field SearchParams."+key+" of type []"+element,false));
              var array=JSON.createArrayNode();while(p.nextToken()!=JsonToken.END_ARRAY)array.add(p.currentToken()==JsonToken.VALUE_NULL?(element.equals("string")?TextNode.valueOf(""):IntNode.valueOf(0)):scalar(p,element,"SearchParams."+key));out.set(key,array);
            } else if(key.equals("rerank")) {
              if(p.currentToken()!=JsonToken.START_OBJECT)throw new IOException(wrong(p,"Go struct field SearchParams.rerank of type types.RerankOptions",false));
              var options=out.path("rerank").isObject()?((ObjectNode)out.path("rerank")).deepCopy():JSON.createObjectNode();String nested;
              while((nested=p.nextFieldName())!=null){p.nextToken();String name=fold(nested);String nestedType=switch(name){case "enabled"->"bool";case "threshold"->"float64";case "top_k"->"int";case "model_id"->"string";default->"";};if(nestedType.isEmpty()){p.skipChildren();continue;}if(p.currentToken()==JsonToken.VALUE_NULL){if(Set.of("enabled","threshold").contains(name))options.putNull(name);continue;}options.set(name,scalar(p,nestedType,"SearchParams.rerank."+name));}out.set(key,options);
            } else p.skipChildren();
          }
        }
      }
    }catch(IOException invalid){throw bad("Invalid request parameters",invalid.getMessage());}
    var request=new HybridSearchRequest(out);request.validate();return request;
  }
  private static JsonNode scalar(JsonParser p,String kind,String field)throws IOException {
    String destination="Go struct field "+field+" of type "+kind;
    if(kind.equals("string")){if(p.currentToken()!=JsonToken.VALUE_STRING)throw new IOException(wrong(p,destination,false));return TextNode.valueOf(surrogates(p.getText()));}
    if(kind.equals("bool")){if(p.currentToken()!=JsonToken.VALUE_TRUE&&p.currentToken()!=JsonToken.VALUE_FALSE)throw new IOException(wrong(p,destination,false));return BooleanNode.valueOf(p.getBooleanValue());}
    if(kind.equals("int")){if(p.currentToken()!=JsonToken.VALUE_NUMBER_INT)throw new IOException(wrong(p,destination,true));var integer=new BigInteger(p.getText());if(integer.bitLength()>63)throw new IOException(wrong(p,destination,true));return LongNode.valueOf(integer.longValue());}
    if(!p.currentToken().isNumeric())throw new IOException(wrong(p,destination,true));
    double number=kind.equals("float32")?Float.parseFloat(p.getText()):Double.parseDouble(p.getText());if(!Double.isFinite(number))throw new IOException(wrong(p,destination,true));return DoubleNode.valueOf(number);
  }
  private void validate(){
    boolean precomputed=embedding().length>0&&flag("disable_keywords_match")&&!flag("disable_vector_match");
    if(SummaryGenerationText.trim(query()).isEmpty()&&!precomputed)throw bad("query_text is required",null);
    var options=rerank();if(options!=null){long top=options.path("top_k").asLong();if(top<0)throw bad("rerank.top_k must not be negative",null);if(top>200)throw bad("rerank.top_k must not exceed 200",null);}
    if(rerankEnabled()&&SummaryGenerationText.trim(query()).isEmpty())throw bad("query_text is required when rerank is enabled",null);
  }
  public String query(){return values.path("query_text").asText("");}
  public float[] embedding(){var array=values.path("query_embedding");var out=new float[array.size()];for(int i=0;i<out.length;i++)out[i]=(float)array.get(i).asDouble();return out;}
  public boolean flag(String name){return values.path(name).asBoolean();}
  public double threshold(String name){return values.path(name).asDouble();}
  public long matchCount(){return values.path("match_count").asLong();}
  public List<String> ids(String name){var out=new ArrayList<String>();values.path(name).forEach(v->out.add(v.asText()));return out;}
  public ObjectNode rerank(){return values.path("rerank").isObject()?(ObjectNode)values.path("rerank"):null;}
  public boolean rerankEnabled(){var r=rerank();return r!=null&&(!r.hasNonNull("enabled")||r.path("enabled").asBoolean());}
  public static ApiException bad(String message,Object details){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000).withDetails(details);}
  private static String wrong(JsonParser p,String destination,boolean number)throws IOException{String type=switch(p.currentToken()){case START_OBJECT->"object";case START_ARRAY->"array";case VALUE_STRING->"string";case VALUE_TRUE,VALUE_FALSE->"bool";case VALUE_NUMBER_INT,VALUE_NUMBER_FLOAT->"number";default->"null";};return "json: cannot unmarshal "+type+(number&&type.equals("number")?" "+p.getText():"")+" into "+destination;}
  private static String fold(String value){StringBuilder out=new StringBuilder();value.codePoints().forEach(cp->out.appendCodePoint(cp==0x17f?'s':Character.toLowerCase(cp)));return out.toString();}
  private static String surrogates(String value){StringBuilder out=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1)))out.append(c).append(value.charAt(++i));else out.append(Character.isSurrogate(c)?'\ufffd':c);}return out.toString();}
}
