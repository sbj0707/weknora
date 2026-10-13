package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Request-local endpoint; never retain it in global catalog or emit its secrets in logs. */
public final class ModelEndpoint {
    public final String id,name,wireModel,url;public final ObjectNode settings,thinking;public final boolean reasoning;private final Map<String,String> headers;private final List<String> secrets;
    public ModelEndpoint(String id,String name,String wireModel,String url,ObjectNode settings,ObjectNode thinking,boolean reasoning,Map<String,String> headers,List<String> secrets){this.id=id;this.name=name;this.wireModel=wireModel;this.url=url;this.settings=settings.deepCopy();this.thinking=thinking.deepCopy();this.reasoning=reasoning;this.headers=Map.copyOf(headers);this.secrets=List.copyOf(secrets);}
    public Map<String,String> headers(boolean stream){var out=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);out.putAll(headers);out.put("Content-Type","application/json");if(stream)out.put("Accept","text/event-stream");return out;}
    public String safe(String message){String safe=message;for(String secret:secrets)if(!secret.isEmpty())safe=safe.replace(secret,"[redacted]");return safe;}
    @Override public String toString(){return "ModelEndpoint[id="+id+"]";}
    public ObjectNode post(SafeModelHttp http,CallContext context,ObjectNode body,int retries){ModelClientException last=null;for(int attempt=0;attempt<=retries;attempt++){context.check();if(attempt>0)context.pause(java.time.Duration.ofSeconds(Math.min(10,1L<<(attempt-1))));try(var response=http.post(context,url,headers(false),body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),timeout())){if(response.status()<200||response.status()>=300)throw new ModelClientException(safe("API request failed with status "+response.status()+": "+response.text(1<<20)));JsonNode result=SafeModelHttp.JSON.readTree(response.bytes(64<<20));if(result==null||!result.isObject())throw new ModelClientException("decode response: expected JSON object");return (ObjectNode)result;}catch(ModelClientException ex){if(!ex.transport())throw ex;last=ex;}catch(java.io.IOException ex){throw new ModelClientException("decode response: invalid JSON");}}
        if(retries==0&&last!=null)throw last;throw new ModelClientException("embedding: "+(last==null?"transport failed":last.getMessage()),true);
    }
    public java.time.Duration timeout(){int seconds=settings.path("request_timeout_seconds").asInt();return java.time.Duration.ofSeconds(seconds>0?seconds:settings.has("max_input_chars")?0:60);}
}
