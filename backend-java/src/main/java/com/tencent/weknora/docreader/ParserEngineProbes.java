package com.tencent.weknora.docreader;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.GoStoredStructDecoder;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.storage.StoragePaths;
import java.io.*;import java.nio.charset.StandardCharsets;import java.time.Duration;import java.util.*;import java.util.function.Consumer;
/** Availability probes only. Parsing/conversion has separate reader implementations. */
public final class ParserEngineProbes implements ParserEngineRegistry.Probe, AutoCloseable {
    private final SafeModelHttp http;private final Consumer<String> validate;private final String cloudBase;
    private final GoStoredStructDecoder health,error;
    public ParserEngineProbes(SafeModelHttp http,Consumer<String> validate){this(http,validate,"https://mineru.net/api/v4");}
    public ParserEngineProbes(SafeModelHttp http,Consumer<String> validate,String cloudBase){
        this.http=http;this.validate=validate;this.cloudBase=cloudBase;
        try{var json=ParserEngineSettings.goMapper();health=new GoStoredStructDecoder(json,json.readTree("{\"kind\":\"struct\",\"fields\":{\"status\":{\"kind\":\"string\"}}}"));error=new GoStoredStructDecoder(json,json.readTree("{\"kind\":\"struct\",\"fields\":{\"error\":{\"kind\":\"struct\",\"fields\":{\"code\":{\"kind\":\"string\"},\"message\":{\"kind\":\"string\"},\"type\":{\"kind\":\"string\"}}}}}"));}catch(IOException ex){throw new ExceptionInInitializerError(ex);}
    }
    @Override public void close(){http.close();}
    public ParserEngineRegistry.Availability check(String engine,Map<String,String> overrides){
        return switch(engine){case "mineru"->mineru(overrides.getOrDefault("mineru_endpoint",""),overrides.getOrDefault("mineru_server_api_key",""));case "mineru_cloud"->cloud(overrides.getOrDefault("mineru_api_key",""));case "paddleocr_vl"->paddle(overrides.getOrDefault("paddleocr_vl_endpoint",""));default->throw new IllegalArgumentException(engine);};
    }
    private static String endpoint(String value){int end=value.length();while(end>0&&value.charAt(end-1)=='/')end--;return value.substring(0,end);}
    private static ParserEngineRegistry.Availability yes(){return new ParserEngineRegistry.Availability(true,"");}
    private static ParserEngineRegistry.Availability no(String reason){return new ParserEngineRegistry.Availability(false,reason);}
    private ParserEngineRegistry.Availability mineru(String value,String apiKey){
        String target=endpoint(value);if(target.isEmpty())return no("未配置 MinerU 端点");
        try{validate.accept(StoragePaths.trim(target));}catch(RuntimeException ex){return no("MinerU URL blocked by SSRF check: "+ex.getMessage());}
        boolean legacy;
        try(var context=new CallContext();var response=http.get(context,target+"/v1/health",Map.of(),Duration.ofSeconds(5))){
            byte[] body=response.body().readNBytes(64<<10);int status=response.status();
            if(status==200){String state;try{state=health.decode(GoUtf8.decode(body)).path("status").asText("");}catch(RuntimeException ex){state="";}
                if(!state.equals("ok"))return no("MinerU 服务不可用: unexpected /v1/health response: "+truncate(body));legacy=false;
            }else if(status==404||status==405)legacy=true;
            else{String message=errorMessage(body);return no("MinerU 服务不可用: "+(!message.isEmpty()?"MinerU service not ready (status "+status+"): "+message:"MinerU /v1/health status "+status+": "+truncate(body)));}
        }catch(IOException|RuntimeException ex){return no("MinerU 服务不可用: MinerU service unreachable: "+ex.getMessage());}
        apiKey=StoragePaths.trim(apiKey);
        try(var context=new CallContext();var response=http.get(context,target+(legacy?"/docs":"/v1/parse/jobs?limit=1"),!legacy&&!apiKey.isEmpty()?Map.of("Authorization","Bearer "+apiKey):Map.of(),Duration.ofSeconds(5))){
            int status=response.status();if(legacy&&status>=400)return no("MinerU 服务返回状态 "+status);
            if(!legacy&&status==401)return no(apiKey.isEmpty()?"MinerU 服务已启用鉴权，请配置 API Key":"MinerU API Key 无效");return yes();
        }catch(RuntimeException ex){return no("MinerU 服务不可达: "+ex.getMessage());}
    }
    private String errorMessage(byte[] body){try{var parsed=error.decode(GoUtf8.decode(body)).path("error");String code=parsed.path("code").asText(""),message=parsed.path("message").asText("");return !code.isEmpty()&&!message.isEmpty()?code+": "+message:!message.isEmpty()?message:code;}catch(RuntimeException ex){return "";}}
    private static String truncate(byte[] body){return GoUtf8.decode(body.length>300?Arrays.copyOf(body,300):body)+(body.length>300?"...":"");}
    private ParserEngineRegistry.Availability paddle(String value){
        String target=endpoint(value);if(target.isEmpty())return no("未配置 PaddleOCR-VL 端点");
        try{validate.accept(target);}catch(RuntimeException ex){return no("PaddleOCR-VL 端点未通过 SSRF 校验: "+ex.getMessage());}
        try(var context=new CallContext();var response=http.get(context,target+"/layout-parsing",Map.of(),Duration.ofSeconds(5))){return response.status()>=500?no("PaddleOCR-VL 服务返回状态 "+response.status()):yes();}catch(RuntimeException ex){return no("PaddleOCR-VL 服务不可达: "+ex.getMessage());}
    }
    private ParserEngineRegistry.Availability cloud(String value){String key=StoragePaths.trim(value);if(key.isEmpty())return no("未配置 MinerU Cloud API Key");
        try(var context=new CallContext();var response=http.post(context,cloudBase+"/file-urls/batch",Map.of("Authorization","Bearer "+key,"Content-Type","application/json"),"{\"files\":[],\"model_version\":\"pipeline\"}".getBytes(StandardCharsets.UTF_8),Duration.ofSeconds(10))){return response.status()==401||response.status()==403?no("MinerU Cloud API Key 无效"):yes();}catch(RuntimeException ex){return no("MinerU Cloud 不可达: "+ex.getMessage());}
    }
}
