package com.tencent.weknora.storage;

import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Go concrete/default/legacy backend precedence. Local is native; cloud providers fail before any external write. */
@Component
public final class StorageResolver {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final Environment env;private final FileUrlSigner signer;private final ResourceCatalog catalog;
    public StorageResolver(JdbcTemplate jdbc,ObjectMapper json,Environment env){this.jdbc=jdbc;this.json=json;this.env=env;signer=new FileUrlSigner(env.getProperty("SYSTEM_SIGNING_KEY",""),env.getProperty("SYSTEM_AES_KEY",""));catalog=new ResourceCatalog(jdbc,signer);}
    public record Resolved(FileStore files,String provider,String backendId){}
    public ResourceCatalog catalog(){return catalog;}
    /** Caller supplies a tenant authorized by the domain service, not a request-body tenant. */
    public Resolved resolve(long ownerTenant,String backendId,String requestedProvider)throws IOException{
        var tenants=jdbc.queryForList("SELECT storage_engine_config,default_storage_backend_id FROM tenants WHERE id=? AND deleted_at IS NULL",ownerTenant);if(tenants.isEmpty())throw new IOException("workspace context missing");var tenant=tenants.getFirst();String id=StoragePaths.trim(backendId),provider=StoragePaths.trim(requestedProvider).toLowerCase(Locale.ROOT);JsonNode legacy=decode(tenant.get("storage_engine_config"));Map<String,Object> backend=null;
        if(id.isEmpty()&&!provider.isEmpty()){var aliases=jdbc.queryForList("SELECT * FROM storage_backends WHERE tenant_id=? AND provider=? AND legacy_alias=? AND deleted_at IS NULL ORDER BY id LIMIT 1",ownerTenant,provider,true);if(!aliases.isEmpty())backend=aliases.getFirst();}
        if(backend==null){if(id.isEmpty())id=StoragePaths.trim(Objects.toString(tenant.get("default_storage_backend_id"),""));if(!id.isEmpty()){var rows=jdbc.queryForList("SELECT * FROM storage_backends WHERE id=? AND tenant_id=? AND deleted_at IS NULL",id,ownerTenant);if(rows.isEmpty())throw new IOException("storage backend not found");backend=rows.getFirst();if(!Objects.equals(backend.get("status"),"active"))throw new IOException("storage backend is not active");}}
        String prefix="";
        if(backend!=null){id=backend.get("id").toString();provider=backend.get("provider").toString();prefix=decode(backend.get("config")).path("path_prefix").asText();}
        else{if(provider.isEmpty())provider=StoragePaths.trim(legacy.path("default_provider").asText()).toLowerCase(Locale.ROOT);if(provider.isEmpty())provider=StoragePaths.trim(env.getProperty("STORAGE_TYPE","local")).toLowerCase(Locale.ROOT);if(provider.isEmpty())provider="local";prefix=legacy.path("local").path("path_prefix").asText();}
        if(!provider.equals("local"))throw new IOException("Java storage provider is not migrated: "+provider);
        String configured=StoragePaths.trim(env.getProperty("LOCAL_STORAGE_BASE_DIR",""));Path base=Path.of(configured.isEmpty()?"/data/files":configured).toAbsolutePath().normalize();String relative=StoragePaths.trim(prefix).replaceAll("^[/\\\\]+|[/\\\\]+$","");if(!relative.isEmpty()){Path candidate=base.resolve(relative).normalize();if(candidate.startsWith(base))base=candidate;}
        long maximum=50L*1024*1024;try{long mb=Long.parseLong(env.getProperty("MAX_FILE_SIZE_MB",""));if(mb>0)maximum=Math.multiplyExact(mb,1024*1024);}catch(NumberFormatException|ArithmeticException ignored){}
        String external=StoragePaths.trim(env.getProperty("APP_EXTERNAL_URL",""));FileStore physical=new LocalFileStore(base,external,signer,maximum);if(!id.isEmpty())physical=new BackendScopedFileStore(id,physical,signer,external);return new Resolved(new ResourceFileStore(physical,catalog,ownerTenant,external),provider,id);
    }
    /** Existing KB must belong to the already-authorized owner tenant; it supplies immutable storage binding. */
    public Resolved forKnowledgeBase(long ownerTenant,String kbId)throws IOException{var rows=jdbc.queryForList("SELECT storage_backend_id,storage_provider_config,cos_config FROM knowledge_bases WHERE tenant_id=? AND id=? AND deleted_at IS NULL",ownerTenant,kbId);if(rows.isEmpty())throw new IOException("knowledge base not found");var kb=rows.getFirst();String provider=decode(kb.get("storage_provider_config")).path("provider").asText();if(provider.isEmpty()&&!decode(kb.get("cos_config")).path("bucket_name").asText().isEmpty())provider="cos";return resolve(ownerTenant,Objects.toString(kb.get("storage_backend_id"),""),provider);}
    private JsonNode decode(Object value)throws IOException{if(value==null||value.toString().isEmpty())return json.createObjectNode();try{return json.readTree(value.toString());}catch(Exception ex){throw new IOException("invalid storage configuration",ex);}}
}
