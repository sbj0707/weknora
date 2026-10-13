package com.tencent.weknora.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.security.ApiException;
import com.tencent.weknora.security.AuthPreferences;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Mirrors Go's typed JSON binding, including null scalar zero values and pointer booleans. */
@Component
public class TenantConfigurationCodec {
    private final ObjectMapper mapper;
    public TenantConfigurationCodec(ObjectMapper mapper){this.mapper=mapper;}
    public JsonNode request(String body){try{JsonNode root=mapper.readTree(body);if(root==null||!root.isNull()&&!root.isObject())throw new IllegalArgumentException();return root;}catch(Exception ex){throw invalid("Invalid request data").withDetails("Expected a JSON object with typed configuration fields");}}
    public Map<String,Object> retrieval(JsonNode root,boolean validate){
        Map<String,Object> out=new LinkedHashMap<>();
        for(String key:new String[]{"embedding_top_k","rerank_top_k"})out.put(key,integer(root,key));
        for(String key:new String[]{"vector_threshold","keyword_threshold","rerank_threshold"})out.put(key,decimal(root,key));
        out.put("rerank_model_id",text(root,"rerank_model_id"));
        long k=integer(root,"rrf_k");if(k!=0)out.put("rrf_k",k);
        for(String key:new String[]{"rrf_vector_weight","rrf_keyword_weight"}){double value=decimal(root,key);if(value!=0)out.put(key,value);}
        if(validate){range(out,"vector_threshold",0,1);range(out,"keyword_threshold",0,1);range(out,"rerank_threshold",-10,10);range(out,"embedding_top_k",0,200);range(out,"rerank_top_k",0,200);}
        return out;
    }
    public Map<String,Object> memory(JsonNode root,boolean validate,boolean normalize){
        Map<String,Object> out=new LinkedHashMap<>();out.put("enabled",bool(root,"enabled",false));
        for(String key:new String[]{"write_mode","extract_model_id","embedding_model_id","extract_instructions"})out.put(key,text(root,key));
        for(String key:new String[]{"max_items","extract_delay_seconds","extract_min_interval_seconds","interest_threshold"})out.put(key,integer(root,key));
        for(String key:new String[]{"vector_recall","retrieval_conditioning"})out.put(key,bool(root,key,null));
        if(validate){String mode=(String)out.get("write_mode");if(!mode.isEmpty()&&!mode.equals("auto")&&!mode.equals("explicit_only"))throw bad("write_mode must be explicit_only or auto");range(out,"max_items",0,2000);range(out,"extract_delay_seconds",0,3600);range(out,"extract_min_interval_seconds",0,86400);if(((String)out.get("embedding_model_id")).getBytes(StandardCharsets.UTF_8).length>64)throw bad("embedding_model_id is too long");if(((Number)out.get("interest_threshold")).longValue()<0||((Number)out.get("interest_threshold")).longValue()>20)throw bad("interest_threshold must be between 1 and 20");String instructions=(String)out.get("extract_instructions");if(instructions.codePointCount(0,instructions.length())>1000)throw bad("extract_instructions must be at most 1000 characters");}
        if(normalize){out.put("write_mode",out.get("write_mode").equals("auto")?"auto":"explicit_only");for(String key:new String[]{"extract_model_id","embedding_model_id","extract_instructions"})out.put(key,AuthPreferences.trimGoSpace((String)out.get(key)));out.put("max_items",clamp(((Number)out.get("max_items")).longValue(),200,1,2000));out.put("extract_delay_seconds",clamp(((Number)out.get("extract_delay_seconds")).longValue(),90,5,3600));out.put("extract_min_interval_seconds",clamp(((Number)out.get("extract_min_interval_seconds")).longValue(),300,0,86400));out.put("interest_threshold",clamp(((Number)out.get("interest_threshold")).longValue(),3,1,20));String instructions=(String)out.get("extract_instructions");if(instructions.codePointCount(0,instructions.length())>1000)out.put("extract_instructions",AuthPreferences.trimGoSpace(instructions.substring(0,instructions.offsetByCodePoints(0,1000))));}
        return out;
    }
    private static long clamp(long value,int fallback,int min,int max){return Math.max(min,Math.min(max,value<=0?fallback:value));}
    static JsonNode field(JsonNode root,String key){JsonNode found=null;if(root!=null&&root.isObject()){var fields=root.fields();while(fields.hasNext()){var entry=fields.next();if(entry.getKey().equalsIgnoreCase(key))found=entry.getValue();}}return found;}
    private static boolean nil(JsonNode value){return value==null||value.isNull();}
    static String text(JsonNode root,String key){JsonNode value=field(root,key);if(nil(value))return "";if(!value.isTextual())throw invalid("Invalid request data");return value.textValue();}
    private static long integer(JsonNode root,String key){JsonNode value=field(root,key);if(nil(value))return 0;if(!value.isIntegralNumber()||!value.canConvertToLong())throw invalid("Invalid request data");return value.longValue();}
    private static double decimal(JsonNode root,String key){JsonNode value=field(root,key);if(nil(value))return 0;if(!value.isNumber()||!Double.isFinite(value.doubleValue()))throw invalid("Invalid request data");return value.doubleValue();}
    private static Boolean bool(JsonNode root,String key,Boolean fallback){JsonNode value=field(root,key);if(nil(value))return fallback;if(!value.isBoolean())throw invalid("Invalid request data");return value.booleanValue();}
    private static void range(Map<String,Object> fields,String key,int min,int max){double n=((Number)fields.get(key)).doubleValue();if(n<min||n>max)throw bad(key+" must be between "+min+" and "+max);}
    private static ApiException bad(String message){return new ApiException(400,"BAD_REQUEST",message).withAppCode(1000);}
    static ApiException invalid(String message){return new ApiException(400,"INVALID_REQUEST",message).withAppCode(1010);}
}
