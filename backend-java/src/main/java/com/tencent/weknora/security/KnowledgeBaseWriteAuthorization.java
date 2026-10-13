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
    private WriteAccess authorize(AuthContext caller,String id,String raw,String template,String method,String capability){
        // APIKeyGate runs before ownership and the KB allow-list middleware.
        if(caller.isApiKey()&&!caller.fullAccess()&&!caller.capabilities().contains(capability))throw simple(403,"Forbidden: API key scope does not allow this operation");
        requireOwnership(caller,id,raw,template,method);
        if(!caller.allowsKnowledgeBase(id))throw forbidden("API key scope does not allow one or more knowledge bases");
        if(caller.tenantId()==0)throw new ApiException(401,"UNAUTHORIZED","Unauthorized").withAppCode(1001);
        Map<String,Object> row;
        try{var rows=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",id);if(rows.isEmpty())throw notFound();row=rows.getFirst();}catch(DataAccessException ex){throw new ApiException(503,"SERVICE_UNAVAILABLE","cannot verify KB access right now").withAppCode(1008);}
        ObjectNode kb=codec.fromRow(row);long owner=AuthRepository.number(row,"tenant_id");boolean own=owner==caller.tenantId();String permission=own?"admin":sharedPermission(id,caller);
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
