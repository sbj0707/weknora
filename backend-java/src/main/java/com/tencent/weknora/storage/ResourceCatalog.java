package com.tencent.weknora.storage;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.*;

/** Existing Go registry tables. All domain mutations require the authoritative resource-owner tenant. */
public final class ResourceCatalog {
    private final JdbcTemplate jdbc;private final FileUrlSigner signer;private final Clock clock;private final SecureRandom random=new SecureRandom();private Boolean postgres;
    public ResourceCatalog(JdbcTemplate jdbc,FileUrlSigner signer){this(jdbc,signer,Clock.systemUTC());}
    public ResourceCatalog(JdbcTemplate jdbc,FileUrlSigner signer,Clock clock){this.jdbc=Objects.requireNonNull(jdbc);this.signer=signer;this.clock=Objects.requireNonNull(clock);}
    public record Registration(String kind,String mimeType,String originalName,long size,String contentHash,boolean temporary){public Registration{kind=Objects.toString(kind,"");mimeType=Objects.toString(mimeType,"");originalName=Objects.toString(originalName,"");contentHash=Objects.toString(contentHash,"");}public static Registration file(String name,long size){return new Registration("file","",name,size,"",false);}}
    public String register(long tenant,String physical,Registration meta){
        physical=StoragePaths.trim(physical);if(tenant<=0||physical.isEmpty()||meta==null)throw new IllegalArgumentException("resource registration requires tenant and physical path");
        if(StoragePaths.resourceHandle(physical)!=null){resolve(tenant,physical);return physical;}
        String location=hash(physical);var existing=byLocation(tenant,location);if(existing.isPresent())return existing.get().reference();
        String provider=StoragePaths.provider(physical);if(provider.isEmpty())throw new IllegalArgumentException("resource physical path has unsupported provider scheme");String backend=StoragePaths.scope(physical).map(StoragePaths.Scope::backendId).orElse("");
        for(int i=0;i<4;i++){String handle=token(),id=UUID.randomUUID().toString();Object now=time(clock.instant());
            int inserted=jdbc.update("INSERT INTO resources(id,handle,tenant_id,storage_backend_id,provider,physical_path,location_hash,kind,mime_type,original_name,size,content_hash,lifecycle,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'active',?,?) ON CONFLICT DO NOTHING",id,handle,tenant,backend,provider,physical,location,meta.kind().isEmpty()?"file":meta.kind(),meta.mimeType(),meta.originalName(),meta.size(),meta.contentHash(),meta.temporary()?"temporary":"persistent",now,now);
            if(inserted==1)return "resource://"+handle;existing=byLocation(tenant,location);if(existing.isPresent())return existing.get().reference();
        }throw new IllegalStateException("failed to allocate unique resource handle");
    }
    public StoredResource resolve(long ownerTenant,String reference){String handle=StoragePaths.resourceHandle(reference);if(handle==null)throw new IllegalArgumentException("invalid resource reference");return active("handle=? AND tenant_id=?",handle,ownerTenant).orElseThrow(()->new NoSuchElementException("resource not found"));}
    public String physical(long ownerTenant,String value){return StoragePaths.resourceHandle(value)==null?value:resolve(ownerTenant,value).physicalPath();}
    private Optional<StoredResource> byLocation(long tenant,String hash){return active("tenant_id=? AND location_hash=?",tenant,hash);}
    private Optional<StoredResource> active(String filter,Object... args){var rows=jdbc.queryForList("SELECT * FROM resources WHERE state='active' AND deleted_at IS NULL AND "+filter,args);return rows.isEmpty()?Optional.empty():Optional.of(map(rows.getFirst()));}
    public void bind(long ownerTenant,String reference,String ownerType,String ownerId,String relation){StoredResource resource=resolve(ownerTenant,reference);if(StoragePaths.trim(ownerType).isEmpty()||StoragePaths.trim(ownerId).isEmpty())throw new IllegalArgumentException("resource binding requires owner type and id");if(relation==null||relation.isEmpty())relation="attachment";
        jdbc.update("INSERT INTO resource_bindings(id,resource_id,tenant_id,owner_type,owner_id,relation,created_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",UUID.randomUUID().toString(),resource.id(),resource.tenantId(),ownerType,ownerId,relation,time(clock.instant()));}
    /** Raw legacy paths return -1. Deleting a claim and counting stay separate, matching the Go catalog. */
    public long release(long tenant,String reference,String ownerType,String ownerId){if(StoragePaths.trim(ownerType).isEmpty()||StoragePaths.trim(ownerId).isEmpty())throw new IllegalArgumentException("resource release requires owner type and id");if(StoragePaths.resourceHandle(reference)==null)return -1;StoredResource resource=resolve(tenant,reference);jdbc.update("DELETE FROM resource_bindings WHERE resource_id=? AND owner_type=? AND owner_id=?",resource.id(),ownerType,ownerId);return jdbc.queryForObject("SELECT COUNT(*) FROM resource_bindings WHERE resource_id=?",Long.class,resource.id());}
    public void markDeleted(long tenant,String reference){StoredResource resource=resolve(tenant,reference);jdbc.update("UPDATE resources SET state='deleted',deleted_at=? WHERE id=? AND tenant_id=?",time(clock.instant()),resource.id(),tenant);}
    public boolean referencedByKnowledgeBase(long tenant,String kb,String value){var resource=StoragePaths.resourceHandle(value)==null?byLocation(tenant,hash(value)):Optional.of(resolve(tenant,value));if(resource.isEmpty()||kb==null||kb.isEmpty())return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM resource_bindings b JOIN knowledges k ON k.id=b.owner_id AND k.tenant_id=b.tenant_id JOIN knowledge_bases kb ON kb.id=k.knowledge_base_id AND kb.tenant_id=k.tenant_id AND kb.deleted_at IS NULL WHERE b.resource_id=? AND b.owner_type='knowledge' AND b.tenant_id=? AND k.knowledge_base_id=? AND k.deleted_at IS NULL",Long.class,resource.get().id(),tenant,kb)>0;}
    public record MessageBindings(List<String> knowledgeBaseIds,boolean messageArtifact){}
    public MessageBindings messageBindings(long tenant,String value,String messageId){var resource=StoragePaths.resourceHandle(value)==null?byLocation(tenant,hash(value)):Optional.of(resolve(tenant,value));if(resource.isEmpty())return new MessageBindings(List.of(),false);String id=resource.get().id();
        var kb=jdbc.queryForList("SELECT DISTINCT k.knowledge_base_id FROM resource_bindings b JOIN knowledges k ON k.id=b.owner_id AND k.tenant_id=b.tenant_id JOIN knowledge_bases kb ON kb.id=k.knowledge_base_id AND kb.tenant_id=k.tenant_id AND kb.deleted_at IS NULL WHERE b.resource_id=? AND b.owner_type='knowledge' AND b.tenant_id=? AND k.deleted_at IS NULL",String.class,id,tenant);
        boolean artifact=messageId!=null&&!messageId.isEmpty()&&jdbc.queryForObject("SELECT COUNT(*) FROM resource_bindings WHERE resource_id=? AND tenant_id=? AND owner_type='message' AND owner_id=? AND relation='artifact'",Long.class,id,tenant,messageId)>0;return new MessageBindings(kb,artifact);}
    public String createGrant(long ownerTenant,String reference,Duration ttl){StoredResource resource=resolve(ownerTenant,reference);if(ttl==null||ttl.isZero()||ttl.isNegative())ttl=Duration.ofHours(2);Instant now=clock.instant();
        // Cleanup is non-fatal, but a revoked tombstone stays until its expiry and prevents deterministic revival.
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())try{jdbc.update("DELETE FROM resource_access_grants WHERE expires_at<=?",time(now));}catch(org.springframework.dao.DataAccessException ignored){}
        if(signer!=null){var derived=signer.derivedGrant(resource.id(),ttl);if(derived.isPresent()){var grant=derived.get();String digest=hash(grant.token());if(validGrant(digest,now,resource.id()))return grant.token();int inserted=insertGrant(resource.id(),digest,grant.expiresAt(),now);if(inserted==1||validGrant(digest,now,resource.id()))return grant.token();}}
        for(int i=0;i<4;i++){String token=token();if(insertGrant(resource.id(),hash(token),now.plus(ttl),now)==1)return token;}throw new IllegalStateException("failed to allocate unique resource access token");
    }
    private int insertGrant(String resource,String digest,Instant expiry,Instant now){return jdbc.update("INSERT INTO resource_access_grants(id,token_hash,resource_id,access_scope,expires_at,created_at) VALUES(?,?,?,'read',?,?) ON CONFLICT(token_hash) DO NOTHING",UUID.randomUUID().toString(),digest,resource,time(expiry),time(now));}
    private boolean validGrant(String hash,Instant now,String resource){var rows=jdbc.queryForList("SELECT resource_id FROM resource_access_grants WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?",hash,time(now));return !rows.isEmpty()&&Objects.equals(rows.getFirst().get("resource_id"),resource);}
    /** Possession of a live token grants read to its resource owner; no caller-tenant override is applied. */
    public StoredResource resolveGrant(String token){var rows=jdbc.queryForList("SELECT resource_id FROM resource_access_grants WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?",hash(StoragePaths.trim(token)),time(clock.instant()));if(rows.isEmpty())throw new NoSuchElementException("resource access grant is invalid or expired");return active("id=?",rows.getFirst().get("resource_id")).orElseThrow(()->new NoSuchElementException("resource not found"));}
    public boolean revokeGrant(String token){return jdbc.update("UPDATE resource_access_grants SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",time(clock.instant()),hash(StoragePaths.trim(token)))==1;}
    private String token(){byte[] bytes=new byte[16];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public static String hash(String text){return hashBytes(text.getBytes(StandardCharsets.UTF_8));}
    public static String hashBytes(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private Object time(Instant value){return postgres()?Timestamp.from(value):DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSSxxx").withZone(ZoneOffset.UTC).format(value);}
    private boolean postgres(){if(postgres==null)postgres=jdbc.execute((ConnectionCallback<Boolean>)connection->connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL"));return Boolean.TRUE.equals(postgres);}
    private static Instant instant(Object value){if(value==null)return null;if(value instanceof Timestamp timestamp)return timestamp.toInstant();String text=value.toString().replace(' ','T');if(text.length()==19)text+="Z";else if(text.matches(".*[+-][0-9]{2}:[0-9]{2}$")){}else if(!text.endsWith("Z"))text+="Z";return Instant.parse(text);}
    private static StoredResource map(Map<String,Object> row){return new StoredResource(row.get("id").toString(),row.get("handle").toString(),((Number)row.get("tenant_id")).longValue(),Objects.toString(row.get("storage_backend_id"),""),row.get("provider").toString(),row.get("physical_path").toString(),row.get("location_hash").toString(),row.get("kind").toString(),Objects.toString(row.get("mime_type"),""),Objects.toString(row.get("original_name"),""),((Number)row.get("size")).longValue(),Objects.toString(row.get("content_hash"),""),row.get("lifecycle").toString(),instant(row.get("expires_at")),row.get("state").toString(),instant(row.get("created_at")),instant(row.get("updated_at")),instant(row.get("deleted_at")));}
}
