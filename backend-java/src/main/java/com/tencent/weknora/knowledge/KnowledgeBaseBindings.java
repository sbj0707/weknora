package com.tencent.weknora.knowledge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeBaseBindings {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final Environment env;
    private static final List<String> PROVIDERS=List.of("local","minio","cos","tos","s3","oss","ks3","obs");
    public KnowledgeBaseBindings(JdbcTemplate jdbc,ObjectMapper json,Environment env){this.jdbc=jdbc;this.json=json;this.env=env;}
    public void resolveStorage(AuthContext auth,ObjectNode kb) {
        String provider=provider(kb);
        if(!provider.isEmpty()&&!allowed().contains(provider))throw new ApiException(400,"BAD_REQUEST","Storage provider is not allowed by STORAGE_ALLOW_LIST");
        var tenants=jdbc.queryForList("SELECT default_storage_backend_id,storage_engine_config FROM tenants WHERE id=? AND deleted_at IS NULL",auth.tenantId());
        if(tenants.isEmpty())throw new ApiException(400,"BAD_REQUEST","workspace context missing");var tenant=tenants.getFirst();
        if(provider.isEmpty()) {
            String fallback=object(tenant.get("storage_engine_config")).path("default_provider").asText().trim().toLowerCase(Locale.ROOT);
            provider=allowed().contains(fallback)?fallback:PROVIDERS.stream().filter(allowed()::contains).findFirst().orElse("");
            if(!provider.isEmpty())kb.set("storage_provider_config",json.createObjectNode().put("provider",provider));
        }
        String id=kb.path("storage_backend_id").asText().trim(),defaultId=Objects.toString(tenant.get("default_storage_backend_id"),"").trim();
        if(id.isEmpty()&&!defaultId.isEmpty())provider="";
        List<Map<String,Object>> selected=List.of();
        if(id.isEmpty()&&!provider.isEmpty())selected=jdbc.queryForList("SELECT id,provider,status FROM storage_backends WHERE tenant_id=? AND provider=? AND legacy_alias=? AND deleted_at IS NULL ORDER BY id LIMIT 1",auth.tenantId(),provider,true);
        boolean alias=!selected.isEmpty();
        if(!alias) {
            if(id.isEmpty())id=defaultId;
            if(!id.isEmpty()) {
                selected=jdbc.queryForList("SELECT id,provider,status FROM storage_backends WHERE tenant_id=? AND id=? AND deleted_at IS NULL",auth.tenantId(),id);
                if(selected.isEmpty())throw unavailableStorage("storage backend not found");
                if(!Objects.equals(selected.getFirst().get("status"),"active"))throw unavailableStorage("storage backend is not active");
            }
        }
        if(!selected.isEmpty()) {var backend=selected.getFirst();kb.put("storage_backend_id",backend.get("id").toString());kb.set("storage_provider_config",json.createObjectNode().put("provider",backend.get("provider").toString()));}
    }
    public void verifyVector(AuthContext auth,ObjectNode kb) {
        String id=kb.path("vector_store_id").asText();if(id.isEmpty()){kb.remove("vector_store_id");return;}
        if(!uuid(id))throw invalidVector();
        var stores=jdbc.queryForList("SELECT engine_type,connection_config FROM vector_stores WHERE id=? AND tenant_id=? AND deleted_at IS NULL",id,auth.tenantId());
        if(stores.isEmpty())throw invalidVector();
        var store=stores.getFirst();String engine=Objects.toString(store.get("engine_type"),"");
        if(engine.equals("postgres")&&object(store.get("connection_config")).path("use_default_connection").asBoolean())return;
        // SQLite construction repairs FTS/vector tables and image indexes in Go. Until that
        // native adapter exists, reject it just as external engines rather than skip side effects.
        throw new ApiException(400,"VECTOR_STORE_UNAVAILABLE","vector store is currently unavailable; check its connection configuration").withAppCode(2201);
    }
    private static boolean uuid(String id) {
        String normalized=id;
        if(normalized.startsWith("urn:uuid:"))normalized=normalized.substring(9);
        if(normalized.startsWith("{")&&normalized.endsWith("}"))normalized=normalized.substring(1,normalized.length()-1);
        return normalized.matches("[0-9a-fA-F]{32}")||normalized.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
    private Set<String> allowed(){String raw=env.getProperty("STORAGE_ALLOW_LIST","").trim();if(raw.isEmpty())return Set.copyOf(PROVIDERS);var result=new HashSet<String>();for(String provider:raw.toLowerCase(Locale.ROOT).split("[,;|\\s]+"))if(PROVIDERS.contains(provider))result.add(provider);return result;}
    private String provider(ObjectNode kb) {String modern=kb.path("storage_provider_config").path("provider").asText().trim().toLowerCase(Locale.ROOT);return !modern.isEmpty()&&!modern.equals("__pending_env__")?modern:kb.path("storage_config").path("provider").asText().trim().toLowerCase(Locale.ROOT);}
    private JsonNode object(Object value){if(value==null)return json.createObjectNode();try{return json.readTree(value.toString());}catch(Exception ex){throw new ApiException(503,"INVALID_KB_CONFIGURATION","Invalid storage/vector binding configuration");}}
    private static ApiException unavailableStorage(String detail){return new ApiException(400,"BAD_REQUEST","storage backend is unavailable").withDetails(detail);}
    private static ApiException invalidVector(){return new ApiException(400,"VECTOR_STORE_INVALID","vector store not found").withAppCode(2200);}
}
