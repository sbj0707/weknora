package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.storage.FileStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Go clone-owned exports objects. Never use upload-layout FileStore.copy for extracted images. */
final class KnowledgeCloneObjects {
  private KnowledgeCloneObjects() {}

  static String copy(FileStore source,FileStore target,String path,long tenant)throws IOException {
    InputStream in;
    try {in=source.open(path);}catch(Exception e){throw wrap("read source image "+quote(path)+": ",e);}
    byte[] bytes;
    try {
      try {bytes=in.readAllBytes();}catch(Exception e){throw wrap("buffer source image "+quote(path)+": ",e);}
    }finally{try{in.close();}catch(IOException ignored){/* Go defers Close without replacing the copy result. */}}
    try {return target.saveBytes(bytes,tenant,UUID.randomUUID()+extension(path,bytes),false);}
    catch(Exception e){throw wrap("save copied image for "+quote(path)+": ",e);}
  }

  static String images(String raw,FileStore store,long tenant,Map<String,String> cache,
      List<String> copied,ObjectMapper json)throws IOException {
    if(raw.isEmpty())return "";
    JsonNode images;
    try {images=KnowledgeImageRequest.images(raw,json);}
    catch(IOException e){throw wrap("failed to parse chunk image_info JSON: ",e);}
    if(images.isNull())return "null";
    var out=json.createArrayNode();
    for(var image:images) {
      if(image.isNull()){out.addNull();continue;}
      var value=canonical(image,json);
      String old=value.path("url").asText();
      if(!old.isEmpty()) {
        String replacement=cache.get(old);
        if(replacement==null) {
          try {replacement=copy(store,store,old,tenant);}
          catch(IOException e){throw wrap("failed to copy chunk image "+quote(old)+": ",e);}
          cache.put(old,replacement);copied.add(replacement);
        }
        if(value.path("original_url").asText().equals(old))value.put("original_url",replacement);
        value.put("url",replacement);
      }
      out.add(value);
    }
    return json.writeValueAsString(out).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026").replace("\u2028","\\u2028").replace("\u2029","\\u2029");
  }

  private static ObjectNode canonical(JsonNode image,ObjectMapper json) {
    var value=json.createObjectNode();
    if(!image.path("sha256").asText("").isEmpty())value.put("sha256",image.path("sha256").asText());
    for(String key:List.of("url","original_url"))value.put(key,image.path(key).asText(""));
    for(String key:List.of("start_pos","end_pos"))value.put(key,image.path(key).asLong());
    for(String key:List.of("caption","ocr_text"))value.put(key,image.path(key).asText(""));
    var attrs=value.putObject("attrs");var original=image.path("attrs");
    attrs.put("schema",original.path("schema").asText(""));
    var source=attrs.putObject("source");
    for(String key:List.of("schema","prompt"))source.put(key,original.path("source").path(key).asText(""));
    if(original.path("source").path("custom").asBoolean())source.put("custom",true);
    if(original.path("attrs").isObject()&&!original.path("attrs").isEmpty())
      attrs.putPOJO("attrs",new com.fasterxml.jackson.databind.util.RawValue(dynamic(original.get("attrs"),json)));
    return value;
  }

  /** interface{} image attributes are float64 values and recursively sorted Go maps. */
  private static String dynamic(JsonNode value,ObjectMapper json) {
    if(value.isObject()) {
      var keys=new ArrayList<String>();value.fieldNames().forEachRemaining(keys::add);
      keys.sort((a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)));
      var parts=new ArrayList<String>();
      for(String key:keys)parts.add(quoted(key,json)+":"+dynamic(value.get(key),json));
      return "{"+String.join(",",parts)+"}";
    }
    if(value.isArray()){var parts=new ArrayList<String>();for(var item:value)parts.add(dynamic(item,json));return "["+String.join(",",parts)+"]";}
    if(value.isTextual())return quoted(value.asText(),json);
    if(value.isNumber()) {
      double d=value.asDouble();
      if(d==0)return Double.doubleToRawLongBits(d)<0?"-0":"0";
      var exact=new java.math.BigDecimal(d);
      java.math.BigDecimal shortest=null;
      for(int digits=1;digits<=17;digits++) {
        var candidate=exact.round(new java.math.MathContext(digits,java.math.RoundingMode.HALF_EVEN));
        if(Double.doubleToRawLongBits(Double.parseDouble(candidate.toString()))==Double.doubleToRawLongBits(d)){shortest=candidate.stripTrailingZeros();break;}
      }
      if(shortest==null)throw new IllegalArgumentException("invalid image attribute number");
      if(Math.abs(d)>=1e-6&&Math.abs(d)<1e21)return shortest.toPlainString();
      int exp=shortest.precision()-shortest.scale()-1;
      String digits=shortest.unscaledValue().abs().toString();
      return (d<0?"-":"")+digits.charAt(0)+(digits.length()>1?"."+digits.substring(1):"")+"e"+(exp<0?"-":"+")+Math.abs(exp);
    }
    return value.toString();
  }
  private static String quoted(String value,ObjectMapper json){try{return json.writeValueAsString(value);}catch(IOException e){throw new UncheckedIOException(e);}}

  static String rewrite(String content,Map<String,String> cache) {
    var keys=new ArrayList<String>();
    for(var e:cache.entrySet())if(!e.getKey().isEmpty()&&!e.getKey().equals(e.getValue()))keys.add(e.getKey());
    keys.sort(Comparator.comparingInt((String s)->s.getBytes(StandardCharsets.UTF_8).length).reversed());
    for(String key:keys)content=content.replace(key,cache.get(key));
    return content;
  }

  static void cleanup(FileStore store,List<String> paths) {
    for(String path:paths)if(!path.isEmpty())try{store.delete(path);}catch(Exception e){
      org.slf4j.LoggerFactory.getLogger(KnowledgeCloneObjects.class).warn("Failed to clean copied clone object {}",path,e);
    }
  }

  static String extension(String path,byte[] data) {
    int slash=path.lastIndexOf('/'),dot=path.lastIndexOf('.');
    String ext=dot>slash?path.substring(dot).toLowerCase(Locale.ROOT):"";
    if(Set.of(".png",".jpg",".jpeg",".gif",".webp",".bmp",".svg").contains(ext))return ext;
    if(starts(data,new byte[]{(byte)137,80,78,71,13,10,26,10}))return ".png";
    if(starts(data,new byte[]{(byte)255,(byte)216,(byte)255}))return ".jpg";
    if(starts(data,"GIF87a".getBytes(StandardCharsets.US_ASCII))||starts(data,"GIF89a".getBytes(StandardCharsets.US_ASCII)))return ".gif";
    if(data.length>=14&&data[0]=='R'&&data[1]=='I'&&data[2]=='F'&&data[3]=='F'&&data[8]=='W'&&data[9]=='E'&&data[10]=='B'&&data[11]=='P'&&data[12]=='V'&&data[13]=='P')return ".webp";
    if(starts(data,new byte[]{'B','M'}))return ".bmp";
    return ".png";
  }
  private static boolean starts(byte[] data,byte[] prefix){return data.length>=prefix.length&&Arrays.equals(data,0,prefix.length,prefix,0,prefix.length);}
  private static IOException wrap(String prefix,Exception e){return new IOException(prefix+Objects.toString(e.getMessage(),""),e);}
  private static String quote(String value){try{return new ObjectMapper().writeValueAsString(value);}catch(IOException e){throw new IllegalArgumentException(e);}}
}
