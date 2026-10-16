package com.tencent.weknora.security;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.knowledge.KnowledgeBaseCodec;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves a KB write grant without replacing the authenticated caller with its execution tenant. */
@Service
public final class KnowledgeBaseWriteAuthorization {
    public static final String GRANT_ATTRIBUTE=KnowledgeBaseWriteAuthorization.class.getName()+".grant";
    public record WriteAccess(ObjectNode knowledgeBase,String knowledgeBaseId,long effectiveTenantId,String permission,boolean ownTenant){}
    private final JdbcTemplate jdbc;private final KnowledgeBaseCodec codec;private final DeniedRoleAuditService audit;
    public KnowledgeBaseWriteAuthorization(JdbcTemplate jdbc,KnowledgeBaseCodec codec,DeniedRoleAuditService audit){this.jdbc=jdbc;this.codec=codec;this.audit=audit;}
    public WriteAccess authorizeUpload(AuthContext caller,String id){return authorizeUpload(caller,id,"/api/v1/knowledge-bases/"+id+"/knowledge/file","POST");}
    public WriteAccess authorizeUpload(AuthContext caller,String id,String rawPath,String method){return authorize(caller,id,rawPath,"/api/v1/knowledge-bases/:id/knowledge/file",method,"ingest");}
    public WriteAccess authorizeUpdate(AuthContext caller,String id){return authorizeUpdate(caller,id,"/api/v1/knowledge-bases/"+id,"PUT");}
    public WriteAccess authorizeUpdate(AuthContext caller,String id,String rawPath,String method){return authorize(caller,id,rawPath,"/api/v1/knowledge-bases/:id",method,"manage_kbs");}
    public WriteAccess authorizeDelete(AuthContext caller,String id,String rawPath){return authorize(caller,id,rawPath,"/api/v1/knowledge-bases/:id","DELETE","manage_kbs");}
    public WriteAccess authorizeTag(AuthContext caller,String id,String rawPath,String method){return authorize(caller,id,rawPath,method.equals("POST")?"/api/v1/knowledge-bases/:id/tags":"/api/v1/knowledge-bases/:id/tags/:tag_id",method,"ingest");}
    public WriteAccess authorizeProfile(AuthContext caller,String id,String rawPath,String method){return authorize(caller,id,rawPath,"/api/v1/knowledge-bases/:id/profile/generate",method,"manage_kbs");}
    public WriteAccess authorizeCancelParse(AuthContext caller,String id,String rawPath,String method){return authorize(caller,id,rawPath,"/api/v1/knowledge/:id/cancel-parse",method,"ingest");}
    public WriteAccess authorizeKnowledgeDelete(AuthContext caller,String id,String raw,String method){return authorize(caller,id,raw,"/api/v1/knowledge/:id",method,"ingest");}
    public WriteAccess authorizeDocumentUpdate(AuthContext caller,String id,String raw,String method){return authorize(caller,id,raw,"/api/v1/knowledge/:id",method,"ingest");}
    public WriteAccess authorizeImageUpdate(AuthContext caller,String id,String raw){return authorize(caller,id,raw,"/api/v1/knowledge/image/:id/:chunk_id","PUT","ingest");}
    public WriteAccess authorizeSummaryRegeneration(AuthContext caller,String id,String raw){return authorize(caller,id,raw,"/api/v1/knowledge/:id/regenerate-summary","POST","ingest");}
    public WriteAccess authorizeReparse(AuthContext caller,String id,String raw){return authorize(caller,id,raw,"/api/v1/knowledge/:id/reparse","POST","ingest");}
    public WriteAccess authorizeMove(AuthContext caller,String id,String raw){return authorize(caller,id,raw,"/api/v1/knowledge/move","POST","ingest",false);}
    public WriteAccess authorizeBatchReparse(AuthContext caller,String id,String raw){
        var grant=authorize(caller,id,raw,"/api/v1/knowledge/batch-reparse","POST","ingest",false);
        if(!bypass(caller)&&grant.ownTenant()&&!grant.knowledgeBase().path("creator_id").asText().equals(caller.userId()))throw forbidden("No permission to operate on this knowledge base");
        return grant;
    }
    public WriteAccess resolveBatchTags(AuthContext caller,String id,String raw){return authorize(caller,id,raw,"/api/v1/knowledge/tags","PUT","ingest",false);}
    public void requireBatchTagsOwnership(AuthContext caller,String id){
        if(bypass(caller))return;
        try{var rows=jdbc.queryForList("SELECT creator_id,tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",id);if(rows.isEmpty())throw notFound();var row=rows.getFirst();if(AuthRepository.number(row,"tenant_id")!=caller.tenantId())throw notFound();if(!AuthRepository.string(row,"creator_id").equals(caller.userId()))throw forbidden("No permission to operate on this knowledge base");}
        catch(DataAccessException failure){throw new ApiException(500,"INTERNAL_SERVER_ERROR","cannot verify knowledge base ownership").withAppCode(1007);}
    }
    public WriteAccess authorizeManualCreate(AuthContext caller,String id,String raw,String method){return authorize(caller,id,raw,"/api/v1/knowledge-bases/:id/knowledge/manual",method,"ingest");}
    public WriteAccess authorizeURLCreate(AuthContext caller,String id,String raw,String method){return authorize(caller,id,raw,"/api/v1/knowledge-bases/:id/knowledge/url",method,"ingest");}
    public WriteAccess authorizeFileDownload(AuthContext caller,String id,String raw){return authorize(caller,id,raw,"/api/v1/knowledge/:id/download","GET","retrieve",false);}
    public WriteAccess authorizeBatchDownload(AuthContext caller,String id,String raw){return authorize(caller,id,raw,"/api/v1/knowledge-bases/:id/knowledge/batch-download","POST","retrieve",false);}
    public WriteAccess authorizeManualUpdate(AuthContext caller,String id,String raw,String method){return authorize(caller,id,raw,"/api/v1/knowledge/manual/:id",method,"ingest");}
    public WriteAccess authorizeChunkMutation(AuthContext caller,String id,String raw,String method,boolean single){return authorize(caller,id,raw,single?"/api/v1/chunks/:knowledge_id/:id":"/api/v1/chunks/:knowledge_id",method,"ingest");}
    public WriteAccess authorizeChunkEdit(AuthContext caller,String id,String raw,boolean revert){return authorize(caller,id,raw,revert?"/api/v1/chunks/:knowledge_id/:id/revert":"/api/v1/chunks/:knowledge_id/:id",revert?"POST":"PUT","ingest");}
    public WriteAccess authorizeChunkQuestion(AuthContext caller,String id,String raw,String method){return authorize(caller,id,raw,"/api/v1/chunks/by-id/:id/questions"+(method.equals("POST")?"/regenerate":""),method,"ingest",false);}
    public WriteAccess authorizeFolderRename(AuthContext caller,String id,String raw,String method){return authorize(caller,id,raw,"/api/v1/knowledge-bases/:id/knowledge/folders",method,"ingest");}
    public WriteAccess authorizeFolderMove(AuthContext caller,String id,String raw,String method){
        var grant=authorize(caller,id,raw,"/api/v1/knowledge/folder",method,"ingest",false);
        // This is Go's handler ownership check, which does not emit the middleware role-denial audit.
        if(!bypass(caller)&&grant.effectiveTenantId()==caller.tenantId()&&!grant.knowledgeBase().path("creator_id").asText().equals(caller.userId()))throw forbidden("No permission to operate on this knowledge base");
        return grant;
    }
    public WriteAccess authorizeKnowledgeClear(AuthContext caller,String id,String raw,String method){return authorize(caller,id,raw,"/api/v1/knowledge-bases/:id/knowledge",method,"ingest",false);}
    public WriteAccess authorizeKnowledgeBatchDelete(AuthContext caller,String id,String raw,String method){
        String clean=id;
        var grant=authorize(caller,clean,raw,"/api/v1/knowledge/batch-delete",method,"ingest",false);
        try{requireLoadedOwnership(caller,Map.of("tenant_id",grant.effectiveTenantId(),"creator_id",grant.knowledgeBase().path("creator_id").asText()),raw,"/api/v1/knowledge/batch-delete",method);}
        catch(ApiException denied){if(denied.status()==403)throw forbidden("No permission to operate on this knowledge base");throw denied;}
        return grant;
    }
    private WriteAccess authorize(AuthContext caller,String id,String raw,String template,String method,String capability){return authorize(caller,id,raw,template,method,capability,true);}
    private WriteAccess authorize(AuthContext caller,String id,String raw,String template,String method,String capability,boolean ownership){
        // APIKeyGate runs before ownership and the KB allow-list middleware.
        if(caller.isApiKey()&&!caller.fullAccess()&&!caller.capabilities().contains(capability))throw simple(403,"Forbidden: API key scope does not allow this operation");
        if(ownership)requireOwnership(caller,id,raw,template,method);
        if(!caller.allowsKnowledgeBase(id))throw forbidden("API key scope does not allow one or more knowledge bases");
        if(caller.tenantId()==0)throw new ApiException(401,"UNAUTHORIZED","Unauthorized").withAppCode(1001);
        Map<String,Object> row;
        try{var rows=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",id);if(rows.isEmpty())throw notFound();row=rows.getFirst();}catch(DataAccessException ex){
            if(template.equals("/api/v1/knowledge/batch-delete")&&id.indexOf('\0')>=0){for(Throwable cause=ex;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql&&"22021".equals(sql.getSQLState()))throw new ApiException(500,"INTERNAL_SERVER_ERROR","ERROR: invalid byte sequence for encoding \"UTF8\": 0x00 (SQLSTATE 22021)").withAppCode(1007);}
            throw new ApiException(503,"SERVICE_UNAVAILABLE","cannot verify KB access right now").withAppCode(1008);
        }
        Map<String,Object> configRow=row;
        if((method.equals("PUT")&&template.equals("/api/v1/chunks/:knowledge_id/:id"))||template.equals("/api/v1/chunks/:knowledge_id/:id/revert")||template.equals("/api/v1/chunks/by-id/:id/questions")||template.equals("/api/v1/chunks/by-id/:id/questions/regenerate")){configRow=new java.util.LinkedHashMap<>(row);configRow.remove("indexing_strategy");}
        ObjectNode kb=codec.fromRow(configRow);long owner=AuthRepository.number(row,"tenant_id");boolean own=owner==caller.tenantId();String permission=own?"admin":sharedPermission(id,caller);
        if(level(permission)<level("editor"))throw forbidden("Permission denied to access this knowledge base");
        return new WriteAccess(kb,id,owner,permission,own);
    }
    public void requireOwnership(AuthContext caller,String id,String raw,String template,String method){
        if(bypass(caller))return;
        try{var rows=jdbc.queryForList("SELECT creator_id,tenant_id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",id);if(rows.isEmpty()||AuthRepository.number(rows.getFirst(),"tenant_id")!=caller.tenantId())return;requireLoadedOwnership(caller,rows.getFirst(),raw,template,method);}
        catch(DataAccessException ex){throw simple(503,"Service Unavailable: cannot verify resource ownership");}
    }
    public void requireLoadedOwnership(AuthContext caller,Map<String,Object> kb,String raw,String template,String method){
        if(bypass(caller)||AuthRepository.number(kb,"tenant_id")!=caller.tenantId())return;
        String creator=AuthRepository.string(kb,"creator_id");if(!creator.isEmpty()&&creator.equals(caller.userId()))return;
        audit.record(caller,raw,template,method,"admin");throw simple(403,"Forbidden: must own the resource or have the required role");
    }
    private static boolean bypass(AuthContext caller){return caller.isApiKey()||caller.isAtLeast("admin");}
    private String sharedPermission(String id,AuthContext caller){
        // The production Go share service ignores a failed member lookup and
        // ResolveKB collapses failed share resolution to an access denial.
        String highest="";
        try{var shares=jdbc.queryForList("SELECT s.permission,m.role AS org_role FROM kb_shares s JOIN organizations o ON o.id=s.organization_id AND o.deleted_at IS NULL JOIN organization_tenant_members source_member ON source_member.organization_id=s.organization_id AND source_member.tenant_id=s.source_tenant_id JOIN organization_tenant_members m ON m.organization_id=s.organization_id AND m.tenant_id=? WHERE s.knowledge_base_id=? AND s.deleted_at IS NULL",caller.tenantId(),id);
            for(var share:shares){String granted=AuthRepository.string(share,"permission"),member=AuthRepository.string(share,"org_role");String effective=granted.isEmpty()?member:member.isEmpty()?granted:level(granted)>=level(member)?member:granted;if(caller.role().equals("viewer")&&level(effective)>=level("editor"))effective="viewer";if(highest.isEmpty()||level(effective)>=level(highest))highest=effective;}
        }catch(DataAccessException ignored){}
        return highest;
    }
    private static int level(String role){return switch(role){case "admin"->3;case "editor"->2;case "viewer"->1;default->0;};}
    private static ApiException forbidden(String message){return new ApiException(403,"FORBIDDEN",message).withAppCode(1002);}
    private static ApiException notFound(){return new ApiException(404,"NOT_FOUND","knowledge base not found").withAppCode(1003);}
    private static ApiException simple(int status,String message){return new ApiException(status,status==403?"FORBIDDEN":"SERVICE_UNAVAILABLE",message).withResponse(Map.of("error",message));}
}
