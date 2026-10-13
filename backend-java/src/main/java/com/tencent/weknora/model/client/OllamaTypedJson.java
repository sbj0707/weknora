package com.tencent.weknora.model.client;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.math.*;
import java.util.*;

/** Streaming typed binding of the locked Ollama SDK response structs. */
final class OllamaTypedJson {
    private static final ObjectMapper JSON=OllamaJson.JSON;
    private static final Map<Integer,Integer> LOWER=new HashMap<>();
    static{try(InputStream in=OllamaTypedJson.class.getResourceAsStream("/go-source-unicode.json")){for(JsonNode pair:JSON.readTree(in).path("lower"))LOWER.put(pair.get(0).asInt(),pair.get(1).asInt());}catch(IOException ex){throw new ExceptionInInitializerError(ex);}}
    enum Schema {CHAT("api.ChatResponse"), MESSAGE("api.Message"), TOOL("api.ToolCall"), FUNCTION("api.ToolCallFunction"), EMBED("api.EmbedResponse"), LIST("api.ListResponse"), MODEL("api.ListModelResponse"), DETAILS("api.ModelDetails"), PULL("api.ProgressResponse"), ERROR("struct { Error string \"json:\\\"error,omitempty\\\"\"; SigninURL string \"json:\\\"signin_url,omitempty\\\"\" }"), DEBUG("api.DebugInfo"), LOGPROB("api.Logprob"), TOKEN("api.TokenLogprob"),STATUS("api.StatusError"),AUTH("api.AuthorizationError");final String type;Schema(String type){this.type=type;}}
    record Field(String key,String type,Schema child,boolean pointer,String segment){Field(String key,String type){this(key,type,null,false,key);}Field(String key,Schema child){this(key,"object",child,false,key);}Field(String key,Schema child,boolean pointer){this(key,"object",child,pointer,key);}}
    private static final Map<Schema,LinkedHashMap<String,Field>> FIELDS=new EnumMap<>(Schema.class);
    static {
        fields(Schema.CHAT,"model:s remote_model:s remote_host:s created_at:time done:b done_reason:s");child(Schema.CHAT,"message",Schema.MESSAGE,false);child(Schema.CHAT,"_debug_info",Schema.DEBUG,true);array(Schema.CHAT,"logprobs",Schema.LOGPROB);
        for(String key:List.of("total_duration","load_duration","prompt_eval_count","prompt_eval_duration","eval_count","eval_duration")){String type=key.endsWith("duration")?"duration":"i";FIELDS.get(Schema.CHAT).put(key,new Field(key,type,null,false,"Metrics."+key));}
        fields(Schema.MESSAGE,"role:s content:s thinking:s images:images tool_name:s tool_call_id:s");array(Schema.MESSAGE,"tool_calls",Schema.TOOL);
        fields(Schema.TOOL,"id:s");child(Schema.TOOL,"function",Schema.FUNCTION,false);fields(Schema.FUNCTION,"index:i name:s arguments:args");
        fields(Schema.EMBED,"model:s embeddings:vectors total_duration:duration load_duration:duration prompt_eval_count:i");fields(Schema.LIST,"");array(Schema.LIST,"models",Schema.MODEL);
        fields(Schema.MODEL,"name:s model:s remote_model:s remote_host:s modified_at:time size:i64 digest:s");child(Schema.MODEL,"details",Schema.DETAILS,false);
        fields(Schema.DETAILS,"parent_model:s format:s family:s families:strings parameter_size:s quantization_level:s");fields(Schema.PULL,"status:s digest:s total:i64 completed:i64");fields(Schema.ERROR,"error:s signin_url:s");
        fields(Schema.STATUS,"StatusCode:i Status:s error:s");fields(Schema.AUTH,"StatusCode:i Status:s signin_url:s");fields(Schema.DEBUG,"rendered_template:s image_count:i");fields(Schema.LOGPROB,"token:s logprob:f64 bytes:ints");array(Schema.LOGPROB,"top_logprobs",Schema.TOKEN);for(String key:List.of("token","logprob","bytes")){Field f=FIELDS.get(Schema.LOGPROB).get(key);FIELDS.get(Schema.LOGPROB).put(key,new Field(key,f.type,null,false,"TokenLogprob."+key));}fields(Schema.TOKEN,"token:s logprob:f64 bytes:ints");
    }
    private static void fields(Schema s,String spec){LinkedHashMap<String,Field> m=new LinkedHashMap<>();if(!spec.isEmpty())for(String entry:spec.split(" ")){String[] p=entry.split(":");m.put(p[0],new Field(p[0],p[1]));}FIELDS.put(s,m);}
    private static void child(Schema s,String name,Schema child,boolean pointer){FIELDS.get(s).put(name,new Field(name,child,pointer));}
    private static void array(Schema s,String name,Schema child){FIELDS.get(s).put(name,new Field(name,"array",child,false,name));}
    private String firstError;private String customOwner;private final String source;
    private OllamaTypedJson(String source){this.source=source;}
    record Bound(JsonNode value,String error){}
    static Bound partial(String source,Schema schema){OllamaTypedJson binder=new OllamaTypedJson(source);JsonNode result=binder.zero(schema);try{OllamaJson.document(source);try(JsonParser p=JSON.createParser(source)){p.nextToken();result=binder.object(p,schema,null,"",schema.type,true);}}catch(IOException ex){return new Bound(result,ex.getMessage());}return new Bound(result,binder.firstError);}
    static JsonNode bind(String source,Schema schema)throws IOException{Bound result=partial(source,schema);if(result.error!=null)throw new IOException(result.error);return result.value;}
    static JsonNode toolArguments(String source){ObjectNode out=JSON.createObjectNode();OllamaTypedJson binder=new OllamaTypedJson(source);try{OllamaJson.document(source);try(JsonParser p=JSON.createParser(source)){p.nextToken();if(p.currentToken()==JsonToken.VALUE_NULL)return NullNode.instance;if(p.currentToken()!=JsonToken.START_OBJECT)return out;while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.currentName();if(!unicode(key).equals(key))return out;p.nextToken();out.set(key,binder.any(p,"",""));}}}catch(IOException ex){/* Go callers deliberately ignore this decoder error and keep prior keys. */}return out;}
    private ObjectNode zero(Schema schema){ObjectNode n=JSON.createObjectNode();for(Field f:FIELDS.get(schema).values())n.set(f.key,switch(f.type){case "s"->TextNode.valueOf("");case "b"->BooleanNode.FALSE;case "i","i64","duration"->LongNode.valueOf(0);case "f64"->DoubleNode.valueOf(0);case "time"->TextNode.valueOf("0001-01-01T00:00:00Z");case "args"->JSON.createObjectNode();case "object"->f.pointer?NullNode.instance:zero(f.child);default->NullNode.instance;});return n;}
    private JsonNode object(JsonParser p,Schema schema,JsonNode old,String path,String owner,boolean root)throws IOException {
        boolean custom=schema==Schema.MESSAGE;String saved=firstError, savedOwner=customOwner;if(custom){firstError=null;customOwner=owner;}
        if(p.currentToken()==JsonToken.VALUE_NULL){if(custom){firstError=saved;customOwner=savedOwner;return zero(schema);}return old==null?zero(schema):old;}
        if(p.currentToken()!=JsonToken.START_OBJECT){error(p,owner,path,custom?"api.Alias":schema.type,root);p.skipChildren();if(custom){String failure=firstError;firstError=saved;customOwner=savedOwner;throw new IOException(failure);}return old==null?zero(schema):old;}
        ObjectNode out=custom||old==null||!old.isObject()?zero(schema):(ObjectNode)old;
        while(p.nextToken()!=JsonToken.END_OBJECT){String original=p.currentName();p.nextToken();Field f=field(schema,original);if(f==null){p.skipChildren();continue;}String at=path.isEmpty()?f.segment:path+"."+f.segment;JsonNode previous=out.get(f.key),value;
            if(f.type.equals("object")){if(f.pointer&&p.currentToken()==JsonToken.VALUE_NULL)value=NullNode.instance;else value=object(p,f.child,previous,at,(customOwner==null?shortType(schema):customOwner),false);}
            else if(f.type.equals("array"))value=array(p,f.child,at,(customOwner==null?shortType(schema):customOwner),"[]"+f.child.type);
            else value=primitive(p,f.type,previous,at,(customOwner==null?shortType(schema):customOwner));
            out.set(f.key,value);
        }
        if(custom){String failure=firstError;firstError=saved;customOwner=savedOwner;if(failure!=null)throw new IOException(failure);out.put("role",goLower(out.path("role").asText()));}
        return out;
    }
    private static String shortType(Schema schema){return schema.type.startsWith("api.")?schema.type.substring(4):"";}
    private static Field field(Schema schema,String name){Field exact=FIELDS.get(schema).get(name);if(exact!=null)return exact;String folded=fold(name);for(Field f:FIELDS.get(schema).values())if(fold(f.key).equals(folded))return f;return null;}
    private static String fold(String text){StringBuilder b=new StringBuilder();text.codePoints().forEach(cp->{if(cp==0x17f)cp='S';else if(cp==0x212a)cp='K';else if(cp>='a'&&cp<='z')cp-=32;b.appendCodePoint(cp);});return b.toString();}
    private static String goLower(String text){StringBuilder b=new StringBuilder();text.codePoints().forEach(cp->b.appendCodePoint(LOWER.getOrDefault(cp,cp)));return b.toString();}
    private JsonNode array(JsonParser p,Schema child,String path,String owner,String type)throws IOException{
        if(p.currentToken()==JsonToken.VALUE_NULL)return NullNode.instance;if(p.currentToken()!=JsonToken.START_ARRAY){error(p,owner,path,type,false);p.skipChildren();return NullNode.instance;}
        ArrayNode out=JSON.createArrayNode();while(p.nextToken()!=JsonToken.END_ARRAY)out.add(object(p,child,null,path,owner,false));return out;
    }
    private JsonNode primitive(JsonParser p,String kind,JsonNode old,String path,String owner)throws IOException {
        if(p.currentToken()==JsonToken.VALUE_NULL){if(kind.equals("args"))return NullNode.instance;if(Set.of("strings","ints","images","vectors").contains(kind))return NullNode.instance;return old;}
        switch(kind){
            case "s":if(p.currentToken()==JsonToken.VALUE_STRING)return TextNode.valueOf(unicode(p.getText()));break;
            case "b":if(p.currentToken()==JsonToken.VALUE_TRUE||p.currentToken()==JsonToken.VALUE_FALSE)return BooleanNode.valueOf(p.getBooleanValue());break;
            case "i","i64","duration":if(p.currentToken()==JsonToken.VALUE_NUMBER_INT){BigInteger i=p.getBigIntegerValue();if(i.bitLength()<64)return LongNode.valueOf(i.longValue());}break;
            case "f64":if(p.currentToken().isNumeric()){double value=Double.parseDouble(p.getText());if(Double.isFinite(value))return DoubleNode.valueOf(value);}break;
            case "time":if(p.currentToken()!=JsonToken.VALUE_STRING)throw new IOException("Time.UnmarshalJSON: input is not a JSON string");p.getText();int begin=(int)p.currentTokenLocation().getCharOffset()+1,end=(int)p.currentLocation().getCharOffset()-1;OllamaTime.validate(source.substring(begin,end));return TextNode.valueOf("0001-01-01T00:00:00Z");
            case "args":return arguments(p,path,owner);
            case "strings","ints","images","vectors":return primitiveArray(p,kind,path,owner,old);
        }
        error(p,owner,path,expected(kind),false);p.skipChildren();return old;
    }
    private JsonNode primitiveArray(JsonParser p,String kind,String path,String owner,JsonNode old)throws IOException{
        if(p.currentToken()!=JsonToken.START_ARRAY){error(p,owner,path,expected(kind),false);p.skipChildren();return old;}
        ArrayNode out=JSON.createArrayNode();while(p.nextToken()!=JsonToken.END_ARRAY){if(kind.equals("vectors")){if(p.currentToken()==JsonToken.VALUE_NULL){out.addNull();continue;}if(p.currentToken()!=JsonToken.START_ARRAY){error(p,owner,path,"[]float32",false);p.skipChildren();out.addNull();continue;}ArrayNode row=JSON.createArrayNode();while(p.nextToken()!=JsonToken.END_ARRAY){if(p.currentToken()==JsonToken.VALUE_NULL)row.add(0f);else if(p.currentToken().isNumeric()){float v=Float.parseFloat(p.getText());if(!Float.isFinite(v)){error(p,owner,path,"float32",false);row.add(0f);}else row.add(v);}else{error(p,owner,path,"float32",false);p.skipChildren();row.add(0f);}}out.add(row);}
            else if(kind.equals("images")){if(p.currentToken()==JsonToken.VALUE_NULL)out.addNull();else if(p.currentToken()==JsonToken.VALUE_STRING){String raw=p.getText();try{GoBase64.validate(raw);out.add(Base64.getEncoder().encodeToString(Base64.getDecoder().decode(raw.replace("\r","").replace("\n",""))));}catch(IOException ex){if(firstError==null)firstError=ex.getMessage();out.addNull();}}else if(p.currentToken()==JsonToken.START_ARRAY){ArrayNode bytes=JSON.createArrayNode();while(p.nextToken()!=JsonToken.END_ARRAY){if(p.currentToken()==JsonToken.VALUE_NULL)bytes.add(0);else if(p.currentToken()==JsonToken.VALUE_NUMBER_INT&&p.getBigIntegerValue().signum()>=0&&p.getBigIntegerValue().compareTo(BigInteger.valueOf(255))<=0)bytes.add(p.getIntValue());else{error(p,owner,path,"uint8",false);p.skipChildren();bytes.add(0);}}byte[] raw=new byte[bytes.size()];for(int i=0;i<raw.length;i++)raw[i]=(byte)bytes.get(i).asInt();out.add(Base64.getEncoder().encodeToString(raw));}else{error(p,owner,path,"api.ImageData",false);p.skipChildren();out.addNull();}}
            else {String element=kind.equals("strings")?"s":"i";out.add(primitive(p,element,kind.equals("strings")?TextNode.valueOf(""):LongNode.valueOf(0),path,owner));}}
        return out;
    }
    private JsonNode arguments(JsonParser p,String path,String owner)throws IOException{
        if(p.currentToken()!=JsonToken.START_OBJECT){p.skipChildren();throw new IOException("Value looks like object, but can't find closing '}' symbol");}
        ObjectNode out=JSON.createObjectNode();while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.currentName();if(!unicode(key).equals(key))throw new IOException("Encountered an invalid escape sequence in a string");p.nextToken();out.set(key,any(p,path,owner));}return out;
    }
    private JsonNode any(JsonParser p,String path,String owner)throws IOException{
        if(p.currentToken()==JsonToken.START_OBJECT){ObjectNode object=JSON.createObjectNode();while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.currentName();p.nextToken();object.set(key,any(p,path,owner));}return object;}
        if(p.currentToken()==JsonToken.START_ARRAY){ArrayNode array=JSON.createArrayNode();while(p.nextToken()!=JsonToken.END_ARRAY)array.add(any(p,path,owner));return array;}
        if(p.currentToken().isNumeric()){double value=Double.parseDouble(p.getText());if(!Double.isFinite(value))throw new IOException(typeError(p,owner,path,"float64",false));return DoubleNode.valueOf(value);}
        if(p.currentToken()==JsonToken.VALUE_STRING)return TextNode.valueOf(unicode(p.getText()));return JSON.readTree(p);
    }
    private static String unicode(String text){StringBuilder out=new StringBuilder();for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(i+1<text.length()&&Character.isLowSurrogate(text.charAt(i+1)))out.append(c).append(text.charAt(++i));else out.append('\uFFFD');}else if(Character.isLowSurrogate(c))out.append('\uFFFD');else out.append(c);}return out.toString();}
    private static String expected(String kind){return switch(kind){case "s"->"string";case "b"->"bool";case "i"->"int";case "i64"->"int64";case "duration"->"time.Duration";case "f64"->"float64";case "strings"->"[]string";case "ints"->"[]int";case "images"->"[]api.ImageData";case "vectors"->"[][]float32";default->kind;};}
    private void error(JsonParser p,String owner,String path,String expected,boolean root)throws IOException{if(firstError==null)firstError=typeError(p,owner,path,expected,root);}
    private static String typeError(JsonParser p,String owner,String path,String expected,boolean root)throws IOException{
        String value=switch(p.currentToken()){case START_OBJECT->"object";case START_ARRAY->"array";case VALUE_TRUE,VALUE_FALSE->"bool";case VALUE_STRING->"string";case VALUE_NUMBER_INT,VALUE_NUMBER_FLOAT->Set.of("int","int64","time.Duration","float32","float64","uint8").contains(expected)?"number "+p.getText():"number";default->"null";};
        return "json: cannot unmarshal "+value+" into "+(root?"Go value of type ":"Go struct field "+owner+"."+path+" of type ")+expected;
    }
}
