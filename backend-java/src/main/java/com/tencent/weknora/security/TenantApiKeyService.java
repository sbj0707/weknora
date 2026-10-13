package com.tencent.weknora.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant key management mirrors Go handler/service/repository validation and replacement semantics. */
@Service
public class TenantApiKeyService {
    private static final Set<String> CAPABILITIES=Set.of("retrieve","chat","read_agents","ingest","manage_kbs","manage_agents","message_history","manage_models","manage_mcp_services","manage_datasources","manage_channels","manage_vector_stores","manage_storage_backends","manage_web_search","run_evaluations","manage_members","manage_spaces","manage_tenant_settings","system_tenants_read","system_tenants_manage","system_settings_read","system_settings_manage","system_runtime_read","system_runtime_manage","system_audit_read");
    private final JdbcTemplate jdbc;
    private final AuthRepository authRepository;
    private final ObjectMapper json;
    private final GoSecretCodec secrets;
    public TenantApiKeyService(JdbcTemplate jdbc,AuthRepository authRepository,ObjectMapper json,GoSecretCodec secrets){this.jdbc=jdbc;this.authRepository=authRepository;this.json=json;this.secrets=secrets;}
    public Map<String,Object> list(AuthContext auth,String pathTenant){
        long tenant=authorize(auth,pathTenant);
        try {
        List<Map<String,Object>> data=new ArrayList<>();
        for(var row:jdbc.queryForList("SELECT * FROM tenant_api_keys WHERE tenant_id=? AND scope_type='tenant' AND revoked_at IS NULL ORDER BY created_at DESC",tenant))data.add(project(row,true));
        return Map.of("success",true,"data",data);
        }catch(ApiException|org.springframework.dao.DataAccessException ex){throw new ApiException(500,"INTERNAL_ERROR","Failed to list API keys").withAppCode(1007).withDetails("API key records are unavailable");}
    }
    @Transactional
    public Map<String,Object> create(AuthContext auth,String pathTenant,Map<String,Object> body){
        long tenant=authorize(auth,pathTenant);Request request=request(tenant,body,true);
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token="sk-"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String stored=secrets.encryptLegacyWhenConfigured(token);
        try {
        Long id=jdbc.queryForObject("INSERT INTO tenant_api_keys(tenant_id,scope_type,name,key_hash,api_key,full_access,knowledge_base_ids,capabilities,expires_at) VALUES(?,'tenant',?,?,?,?,"+authRepository.jsonParameter()+","+authRepository.jsonParameter()+",?) RETURNING id",Long.class,tenant,request.name(),AuthFilter.hashApiKey(token),stored,request.fullAccess(),encode(request.ids()),encode(request.capabilities()),timestamp(request.expiresAt()));
        if(id==null)throw new ApiException(500,"INTERNAL_ERROR","Failed to create API key").withAppCode(1007);
        Map<String,Object> data=project(find(tenant,id),false);
        data.put("token",token);
        return Map.of("success",true,"data",data);
        }catch(org.springframework.dao.DataAccessException ex){throw new ApiException(500,"INTERNAL_ERROR","Failed to create API key").withAppCode(1007).withDetails("API key could not be persisted");}
    }
    @Transactional
    public Map<String,Object> update(AuthContext auth,String pathTenant,String pathKey,Map<String,Object> body){
        long tenant=authorize(auth,pathTenant),id=keyId(pathKey);Request request=request(tenant,body,false);
        try {
        if(jdbc.update("UPDATE tenant_api_keys SET name=?,full_access=?,knowledge_base_ids="+authRepository.jsonParameter()+",capabilities="+authRepository.jsonParameter()+",expires_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND scope_type='tenant' AND revoked_at IS NULL",request.name(),request.fullAccess(),encode(request.ids()),encode(request.capabilities()),timestamp(request.expiresAt()),id,tenant)!=1)throw missing();
        return Map.of("success",true,"data",project(find(tenant,id),true));
        }catch(ApiException|org.springframework.dao.DataAccessException ex){throw missing();}
    }
    @Transactional
    public Map<String,Object> revoke(AuthContext auth,String pathTenant,String pathKey){
        long tenant=authorize(auth,pathTenant),id=keyId(pathKey);
        try {
        if(jdbc.update("UPDATE tenant_api_keys SET revoked_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=? AND scope_type='tenant' AND revoked_at IS NULL",authRepository.timestampParameter(Instant.now()),id,tenant)!=1)throw missing();
        return Map.of("success",true);
        }catch(org.springframework.dao.DataAccessException ex){throw missing();}
    }
    private long authorize(AuthContext auth,String pathTenant){
        if(auth.isApiKey())throw new ApiException(403,"FORBIDDEN","API keys cannot manage machine credentials").withAppCode(1002);
        long tenant=positiveId(pathTenant,"workspace id must be a positive integer",1010);
        if(auth.tenantId()==0)throw new ApiException(403,"FORBIDDEN","No active workspace in context").withAppCode(1002);
        if(tenant!=auth.tenantId()&&!auth.crossTenantSuperuser())throw new ApiException(403,"FORBIDDEN","Access denied: URL workspace does not match the active workspace").withAppCode(1002);
        auth.requireRole("owner");
        if(authRepository.tenant(tenant)==null)throw new ApiException(404,"NOT_FOUND","Workspace not found").withAppCode(1003);
        return tenant;
    }
    private Request request(long tenant,Map<String,Object> body,boolean creating){
        if(body==null)throw invalid("Invalid request data");
        Object rawName=body.get("name"),rawFull=body.get("full_access");
        if(rawName!=null&&!(rawName instanceof String)||rawFull!=null&&!(rawFull instanceof Boolean))throw invalid("Invalid request data");
        String name=rawName==null?"":AuthPreferences.trimGoSpace((String)rawName);
        boolean full=Boolean.TRUE.equals(rawFull);
        List<String> rawCaps=array(body.get("capabilities")),rawIds=array(body.get("knowledge_base_ids"));
        Instant expires=null;Object rawExpires=body.get("expires_at_unix");
        if(rawExpires!=null){
            if(!(rawExpires instanceof Byte||rawExpires instanceof Short||rawExpires instanceof Integer||rawExpires instanceof Long||rawExpires instanceof java.math.BigInteger))throw invalid("Invalid request data");
            try{long seconds=new java.math.BigInteger(rawExpires.toString()).longValueExact();expires=Instant.ofEpochSecond(seconds);}
            catch(ArithmeticException|java.time.DateTimeException ex){throw invalid("Invalid request data");}
        }
        if(name.isEmpty())throw invalid("name is required");
        List<String> caps=normalizeCapabilities(rawCaps),ids=normalizeIds(rawIds);
        if(!full){
            if(caps.isEmpty())throw invalid("capabilities are required for scoped API keys");
            for(String raw:rawCaps)if(!AuthPreferences.trimGoSpace(raw).isEmpty()&&!CAPABILITIES.contains(AuthPreferences.trimGoSpace(raw).toLowerCase(Locale.ROOT)))throw invalid("capabilities contains an unknown capability");
            for(String id:ids){
                var matches=jdbc.queryForList("SELECT tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",id);
                if(matches.isEmpty())throw invalid("knowledge_base_ids contains an unknown knowledge base");
                if(AuthRepository.number(matches.getFirst(),"tenant_id")!=tenant)throw new ApiException(403,"FORBIDDEN","knowledge_base_ids contains a knowledge base outside this workspace").withAppCode(1002);
            }
        }else{caps=null;ids=null;}
        if(creating&&expires!=null&&!expires.isAfter(Instant.now()))throw invalid("expires_at_unix must be in the future");
        return new Request(name,full,ids,caps,expires);
    }
    private Map<String,Object> find(long tenant,long id){var rows=jdbc.queryForList("SELECT * FROM tenant_api_keys WHERE id=? AND tenant_id=? AND scope_type='tenant' AND revoked_at IS NULL",id,tenant);if(rows.isEmpty())throw missing();return rows.getFirst();}
    Map<String,Object> project(Map<String,Object> row,boolean decrypt){
        Map<String,Object> out=new LinkedHashMap<>();out.put("id",AuthRepository.number(row,"id"));out.put("scope_type","tenant");out.put("name",AuthRepository.string(row,"name"));
        String stored=AuthRepository.string(row,"api_key");out.put("api_key",decrypt?secrets.decryptStrict(stored):stored);
        out.put("full_access",AuthRepository.bool(row,"full_access"));Object storedIds=authRepository.jsonValue(row.get("knowledge_base_ids"));out.put("knowledge_base_ids",storedIds==null?null:array(storedIds));out.put("capabilities",normalizeCapabilities(arrayJson(row.get("capabilities"))));
        for(String field:List.of("last_used_at","expires_at","created_at")){Object value=row.get(field);if(value!=null)out.put(field,AuthRepository.instant(value).toString());}
        return out;
    }
    static List<String> normalizeCapabilities(List<String> raw){LinkedHashSet<String> result=new LinkedHashSet<>();for(String value:raw){String norm=AuthPreferences.trimGoSpace(value).toLowerCase(Locale.ROOT);if(CAPABILITIES.contains(norm))result.add(norm);}return new ArrayList<>(result);}
    static List<String> normalizeIds(List<String> raw){LinkedHashSet<String> result=new LinkedHashSet<>();for(String value:raw){String norm=AuthPreferences.trimGoSpace(value);if(!norm.isEmpty())result.add(norm);}return new ArrayList<>(result);}
    private List<String> array(Object raw){if(raw==null)return List.of();if(!(raw instanceof List<?> list))throw invalid("Invalid request data");List<String> out=new ArrayList<>();for(Object value:list){if(value==null)out.add("");else if(value instanceof String text)out.add(text);else throw invalid("Invalid request data");}return out;}
    private List<String> arrayJson(Object raw){if(raw==null)return List.of();Object decoded=authRepository.jsonValue(raw);return array(decoded);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private Object timestamp(Instant value){return value==null?null:authRepository.timestampParameter(value);}
    private static long keyId(String value){return positiveId(value,"Invalid API key ID",1000);}
    private static long positiveId(String value,String message,int code){try{String trimmed=AuthPreferences.trimGoSpace(value);if(!trimmed.matches("[0-9]+"))throw new NumberFormatException();long id=Long.parseLong(trimmed);if(id<=0)throw new NumberFormatException();return id;}catch(NumberFormatException ex){throw new ApiException(400,"INVALID_REQUEST",message).withAppCode(code);}}
    private static ApiException invalid(String message){return new ApiException(400,"INVALID_REQUEST",message).withAppCode(1010);}
    private static ApiException missing(){return new ApiException(404,"NOT_FOUND","API key not found").withAppCode(1003);}
    private record Request(String name,boolean fullAccess,List<String> ids,List<String> capabilities,Instant expiresAt){}
}
