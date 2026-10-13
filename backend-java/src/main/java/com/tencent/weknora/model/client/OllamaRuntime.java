package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.docreader.GoUtf8;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Production OllamaService availability/pull sequence over the shared checked native transport. */
public final class OllamaRuntime {
    static final String FALLBACK="something went wrong, please see the ollama server logs for details";
    private static final ObjectMapper JSON=SafeModelHttp.JSON;
    final SafeModelHttp http;private final String base;private final boolean optional,signed;private volatile boolean available;
    public OllamaRuntime(SafeModelHttp http,String base,boolean optional,boolean signed){this.http=http;this.base=base==null||base.isEmpty()?"http://localhost:11434":base;this.optional=optional;this.signed=signed;}
    public static boolean authRequested(String raw){String value=ModelText.trim(raw==null?"":raw);int start=0,end=value.length();while(start<end&&(value.charAt(start)=='\"'||value.charAt(start)=='\''))start++;while(end>start&&(value.charAt(end-1)=='\"'||value.charAt(end-1)=='\''))end--;value=value.substring(start,end);return !value.isEmpty()&&!Set.of("0","f","F","false","FALSE","False").contains(value);}
    private String url(String path){
        URI target;try{target=URI.create(base);}catch(IllegalArgumentException ex){throw new ModelClientException("Invalid Ollama service URL");}
        if(signed||"ollama.com".equals(target.getHost()))throw new ModelClientException("Native Ollama signed authentication is not migrated");
        String root=target.getRawPath()==null?"":target.getRawPath();String joined=(root+"/"+path).replaceAll("/+","/");joined=URI.create(joined).normalize().toString();if(path.equals("/")&&!joined.endsWith("/"))joined+="/";
        return target.getScheme()+"://"+target.getRawAuthority()+joined+(target.getRawQuery()==null?"":"?"+target.getRawQuery());
    }
    private static Map<String,String> headers(boolean stream){return Map.of("Content-Type","application/json","Accept",stream?"application/x-ndjson":"application/json","User-Agent","weknora-java/ollama");}
    private SafeModelHttp.Response send(CallContext call,String method,String path,JsonNode body,boolean stream){String target=url(path);return switch(method){case "HEAD"->http.head(call,target,headers(stream),Duration.ZERO);case "GET"->http.get(call,target,headers(stream),Duration.ZERO);default->http.post(call,target,headers(stream),body.toString().getBytes(StandardCharsets.UTF_8),Duration.ZERO);};}
    private JsonNode document(CallContext call,String method,String path,JsonNode body){try(var response=send(call,method,path,body,false)){String text=GoUtf8.decode(response.bytes(64<<20));if(response.status()>=400)throw new ModelClientException(error(response.status(),text,false));if(method.equals("HEAD")||text.isEmpty())return JSON.createObjectNode();try{return OllamaTypedJson.bind(text,path.equals("/api/tags")?OllamaTypedJson.Schema.LIST:OllamaTypedJson.Schema.EMBED);}catch(IOException ex){throw new ModelClientException(ex.getMessage());}}}
    private synchronized void heartbeat(CallContext call){try{document(call,"HEAD","/",null);available=true;}catch(ModelClientException ex){available=false;if(!optional)throw new ModelClientException("ollama service unavailable: "+ex.getMessage());}}
    private boolean contains(CallContext call,String model){heartbeat(call);if(!available&&optional)return false;JsonNode list;try{list=document(call,"GET","/api/tags",null);OllamaJson.validateList(list);}catch(ModelClientException ex){throw new ModelClientException("failed to get model list: "+ex.getMessage());}String name=model.contains(":")?model:model+":latest";for(JsonNode row:list.path("models"))if(row.path("name").asText().equals(name))return true;return false;}
    void ensure(CallContext call,String model){if(!available&&optional)return;boolean found;try{found=contains(call,model);}catch(ModelClientException ex){if(optional)return;throw ex;}if(!found){heartbeat(call);if(!available&&optional)return;if(contains(call,model))return;try{stream(call,"/api/pull",JSON.createObjectNode().put("model","").put("name",model).put("username","").put("password",""),n->{OllamaJson.validatePull(n);});}catch(ModelClientException ex){throw new ModelClientException("failed to pull model: "+ex.getMessage());}}}
    JsonNode embed(CallContext call,JsonNode request){heartbeat(call);return document(call,"POST","/api/embed",request);}
    void chat(CallContext call,JsonNode request,Consumer<JsonNode> consumer){heartbeat(call);stream(call,"/api/chat",request,consumer);}
    private void stream(CallContext call,String path,JsonNode request,Consumer<JsonNode> consumer){try(var response=send(call,"POST",path,request,true)){while(true){call.check();String text=line(response.body());if(text==null)return;JsonNode n;try{n=OllamaTypedJson.bind(text,OllamaTypedJson.Schema.ERROR);}catch(IOException|ModelClientException ex){throw new ModelClientException(response.status()>=400?response.status()+" "+reason(response.status())+": "+text:text);}if(response.status()>=400)throw new ModelClientException(error(response.status(),text,true));String error=n.path("error").asText();if(!error.isEmpty())throw new ModelClientException(error);try{consumer.accept(OllamaTypedJson.bind(text,path.equals("/api/pull")?OllamaTypedJson.Schema.PULL:OllamaTypedJson.Schema.CHAT));}catch(IOException ex){throw new ModelClientException(ex.getMessage());}call.check();}}}
    static String line(InputStream input){try{ByteArrayOutputStream data=new ByteArrayOutputStream();while(true){int b=input.read();if(b<0){if(data.size()==0)return null;break;}if(b=='\n')break;if(data.size()>=8*1024*1024-1)throw new ModelClientException("bufio.Scanner: token too long");data.write(b);}byte[] raw=data.toByteArray();int end=raw.length;if(end>0&&raw[end-1]=='\r')end--;return GoUtf8.decode(Arrays.copyOf(raw,end));}catch(IOException ex){throw new ModelClientException("Model response read failed",true);}}
    static String error(int status,String text,boolean stream){if(stream){OllamaTypedJson.Bound bound=OllamaTypedJson.partial(text,OllamaTypedJson.Schema.ERROR);String message=bound.error()==null?bound.value().path("error").asText():text;if(status==401)return status+" "+reason(status);return status+" "+reason(status)+(message.isEmpty()?"":": "+message);}OllamaTypedJson.Bound bound=OllamaTypedJson.partial(text,status==401?OllamaTypedJson.Schema.AUTH:OllamaTypedJson.Schema.STATUS);String message=bound.error()==null?bound.value().path("error").asText():text;String given=bound.value().path("Status").asText();if(status==401)return given.isEmpty()?FALLBACK:given;if(!given.isEmpty())return given+(message.isEmpty()?"":": "+message);return message.isEmpty()?FALLBACK:message;}
    private static String reason(int code){return io.grpc.netty.shaded.io.netty.handler.codec.http.HttpResponseStatus.valueOf(code).reasonPhrase();}
}
