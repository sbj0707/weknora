package com.tencent.weknora.security;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Native invitation state machine. Each committed stage retains the original Go failure semantics. */
@Service
public final class TenantInvitationService {
    private static final Logger LOG=LoggerFactory.getLogger(TenantInvitationService.class);
    private final JdbcTemplate jdbc;
    private final AuthRepository repository;
    private final SecuritySettings settings;
    private final TransactionTemplate writes;
    private final SecureRandom random=new SecureRandom();
    public TenantInvitationService(JdbcTemplate jdbc,AuthRepository repository,SecuritySettings settings,PlatformTransactionManager transactions){this.jdbc=jdbc;this.repository=repository;this.settings=settings;writes=new TransactionTemplate(transactions);writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    Map<String,Object> create(AuthContext auth,long tenant,String user,String role,String message,boolean share){
        if(AuthContext.level(role)==0)throw invalid("invalid tenant role");
        if(auth.isApiKey()&&role.equals("owner"))throw forbidden("API keys cannot assign the owner role");
        if(!share){var member=membership(user,tenant);if(member!=null&&AuthRepository.string(member,"status").equals("active"))throw conflict("user is already an active member of the tenant");}
        byte[] entropy=new byte[32];if(share)random.nextBytes(entropy);String token=share?Base64.getUrlEncoder().withoutPadding().encodeToString(entropy):"";
        String inviter=auth.userId().isEmpty()||auth.userId().matches("system-[0-9]+")?null:auth.userId();Instant now=Instant.now(),expires=now.plus(TenantInvitationCodec.ttl(settings.environment().getProperty("WEKNORA_INVITATION_TTL")));
        Map<String,Object> row;
        try{row=writes.execute(status->{
            if(!share&&!jdbc.queryForList("SELECT id FROM tenant_invitations WHERE tenant_id=? AND invitee_user_id=? AND status='pending' AND deleted_at IS NULL",tenant,user).isEmpty())throw conflict("a pending invitation for this user already exists");
            var keys=new GeneratedKeyHolder();jdbc.update(connection->{var statement=connection.prepareStatement("INSERT INTO tenant_invitations(tenant_id,invitee_user_id,token,invited_by,role,status,message,expires_at,created_at,updated_at) VALUES(?,?,?,?,?,'pending',?,?,?,?)",new String[]{"id"});statement.setLong(1,tenant);statement.setString(2,share?"":user);statement.setString(3,token);statement.setString(4,inviter);statement.setString(5,role);statement.setString(6,message);statement.setObject(7,repository.timestampParameter(expires));statement.setObject(8,repository.timestampParameter(now));statement.setObject(9,repository.timestampParameter(now));return statement;},keys);Number id=keys.getKey();if(id==null)throw new IllegalStateException("Invitation insert did not return an id");return get(id.longValue());});}
        catch(DataAccessException ex){if(!share&&duplicate(ex))throw conflict("a pending invitation for this user already exists");throw ex;}
        audit(auth,row,"rbac.invitation_sent",share?"":user);return row;
    }
    Map<String,Object> accept(AuthContext auth,long id){
        sweep();var inv=required(id);if(!AuthRepository.string(inv,"invitee_user_id").equals(auth.userId()))throw forbidden("only the invitee can accept or decline this invitation");pending(inv,true);
        transition(id,"accepted");
        // A failed membership insert must not roll back the accepted invitation.
        var member=join(auth,inv,auth.userId()).member();audit(auth,inv,"rbac.invitation_accepted",auth.userId());return member;
    }
    void decline(AuthContext auth,long id){sweep();var inv=required(id);if(!AuthRepository.string(inv,"invitee_user_id").equals(auth.userId()))throw forbidden("only the invitee can accept or decline this invitation");pending(inv,true);transition(id,"declined");audit(auth,inv,"rbac.invitation_declined",auth.userId());}
    void revoke(AuthContext auth,long tenant,long id){var inv=required(id);if(AuthRepository.number(inv,"tenant_id")!=tenant)throw missing();sweep();inv=required(id);pending(inv,false);transition(id,"revoked");audit(auth,inv,"rbac.invitation_revoked",AuthRepository.string(inv,"invitee_user_id"));}
    Map<String,Object> lookup(String raw){String token=AuthPreferences.trimGoSpace(raw==null?"":raw);if(token.isEmpty())throw gone();sweep();var rows=jdbc.queryForList("SELECT * FROM tenant_invitations WHERE token=? AND status='pending' AND deleted_at IS NULL ORDER BY id LIMIT 1",token);if(rows.isEmpty()||expired(rows.getFirst()))throw gone();return rows.getFirst();}
    Map<String,Object> acceptToken(AuthContext auth,String token){return acceptToken(auth,token,auth.userId());}
    Map<String,Object> acceptToken(AuthContext auth,String token,String newUserId){var inv=lookup(token);var joined=join(auth,inv,newUserId);
        if(joined.inserted())bestEffort("increment invitation uses",()->writes.executeWithoutResult(status->jdbc.update("UPDATE tenant_invitations SET accepted_count=accepted_count+1 WHERE id=? AND deleted_at IS NULL",AuthRepository.number(inv,"id"))));
        reconcile(AuthRepository.number(inv,"tenant_id"),newUserId);if(joined.inserted())audit(auth,inv,"rbac.invitation_accepted",newUserId);return joined.member();
    }
    private record Joined(Map<String,Object> member,boolean inserted){}
    private Joined join(AuthContext auth,Map<String,Object> inv,String user){
        long tenant=AuthRepository.number(inv,"tenant_id");String role=AuthRepository.string(inv,"role");
        if(auth.isApiKey()&&role.equals("owner"))throw forbidden("API keys cannot assign the owner role");
        Joined result;
        try{result=writes.execute(status->{var existing=membership(user,tenant);if(existing!=null)return new Joined(existing,false);
            jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status,invited_by,joined_at) VALUES(?,?,?,'active',?,?)",user,tenant,role,inv.get("invited_by"),repository.timestampParameter(Instant.now()));return new Joined(membership(user,tenant),true);});}
        catch(DataAccessException ex){if(!duplicate(ex))throw ex;var existing=membership(user,tenant);if(existing==null)throw ex;return new Joined(existing,false);}
        if(result.inserted())memberAudit(auth,tenant,user);return result;
    }
    void reconcile(long tenant,String user){bestEffort("reconcile pending invitation",()->{sweep();var rows=jdbc.queryForList("SELECT id FROM tenant_invitations WHERE tenant_id=? AND invitee_user_id=? AND status='pending' AND deleted_at IS NULL ORDER BY id LIMIT 1",tenant,user);if(!rows.isEmpty())transition(AuthRepository.number(rows.getFirst(),"id"),"accepted");});}
    List<Map<String,Object>> inbox(String user,boolean terminal){sweep();return jdbc.queryForList("SELECT * FROM tenant_invitations WHERE invitee_user_id=? AND deleted_at IS NULL"+(terminal?"":" AND status='pending'")+" ORDER BY id DESC",user);}
    long count(String user){sweep();Long n=jdbc.queryForObject("SELECT COUNT(*) FROM tenant_invitations WHERE invitee_user_id=? AND status='pending' AND deleted_at IS NULL",Long.class,user);return n==null?0:n;}
    record Page(List<Map<String,Object>> rows,long total){}
    Page list(long tenant,boolean terminal,long page,long size){sweep();String filter=" WHERE tenant_id=? AND deleted_at IS NULL"+(terminal?"":" AND status='pending'");Long n=jdbc.queryForObject("SELECT COUNT(*) FROM tenant_invitations"+filter,Long.class,tenant);return new Page(jdbc.queryForList("SELECT * FROM tenant_invitations"+filter+" ORDER BY id DESC LIMIT ? OFFSET ?",tenant,size,Math.multiplyExact(page-1,size)),n==null?0:n);}
    Map<String,Object> get(long id){var rows=jdbc.queryForList("SELECT * FROM tenant_invitations WHERE id=? AND deleted_at IS NULL",id);return rows.isEmpty()?null:rows.getFirst();}
    private Map<String,Object> required(long id){var inv=get(id);if(inv==null)throw missing();return inv;}
    private Map<String,Object> membership(String user,long tenant){var rows=jdbc.queryForList("SELECT * FROM tenant_members WHERE user_id=? AND tenant_id=? AND deleted_at IS NULL",user,tenant);return rows.isEmpty()?null:rows.getFirst();}
    private void pending(Map<String,Object> inv,boolean checkExpiry){if(!AuthRepository.string(inv,"status").equals("pending"))throw conflict("invitation is no longer pending");if(checkExpiry&&expired(inv))throw conflict("invitation has expired");}
    private boolean expired(Map<String,Object> inv){return AuthRepository.instant(inv.get("expires_at")).isBefore(Instant.now());}
    private void transition(long id,String status){Instant now=Instant.now();try{writes.executeWithoutResult(tx->{if(jdbc.update("UPDATE tenant_invitations SET status=?,responded_at=?,updated_at=? WHERE id=? AND status='pending' AND deleted_at IS NULL",status,repository.timestampParameter(now),repository.timestampParameter(now),id)!=1)throw conflict("invitation is no longer pending");});}catch(DataAccessException ex){throw conflict("invitation is no longer pending");}}
    private void sweep(){Instant now=Instant.now();bestEffort("expire overdue invitations",()->writes.executeWithoutResult(status->jdbc.update("UPDATE tenant_invitations SET status='expired',responded_at=?,updated_at=? WHERE status='pending' AND expires_at<? AND deleted_at IS NULL",repository.timestampParameter(now),repository.timestampParameter(now),repository.timestampParameter(now))));}
    private void audit(AuthContext auth,Map<String,Object> inv,String action,String target){long id=AuthRepository.number(inv,"id");bestEffort("record invitation audit",()->writes.executeWithoutResult(status->jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_id,target_user_id,outcome,details,created_at) VALUES(?,?,?,?,'tenant_invitation',?,?,'success',"+repository.jsonParameter()+",?)",AuthRepository.number(inv,"tenant_id"),auth.userId(),auth.role(),action,Long.toString(id),target,repository.jsonString(Map.of("invitation_id",id,"role",AuthRepository.string(inv,"role"))),repository.timestampParameter(Instant.now()))));}
    private void memberAudit(AuthContext auth,long tenant,String user){bestEffort("record invitation membership audit",()->writes.executeWithoutResult(status->jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_user_id,outcome,created_at) VALUES(?,?,?,'rbac.member_added','tenant_member',?,'success',?)",tenant,auth.userId(),auth.role(),user,repository.timestampParameter(Instant.now()))));}
    private static boolean duplicate(DataAccessException ex){String text=ex.getMessage().toLowerCase(Locale.ROOT);return text.contains("unique constraint")||text.contains("duplicate key")||text.contains("constraint_unique");}
    private void bestEffort(String operation,Runnable action){try{action.run();}catch(RuntimeException ex){LOG.warn("Unable to {}",operation);}}
    private static ApiException missing(){return new ApiException(404,"NOT_FOUND","invitation not found").withAppCode(1003);}
    private static ApiException gone(){return new ApiException(410,"NOT_FOUND","invitation link is invalid or has been revoked").withAppCode(1003);}
    private static ApiException conflict(String message){return new ApiException(409,"CONFLICT",message).withAppCode(1005);}
    private static ApiException invalid(String message){return new ApiException(400,"INVALID_REQUEST",message).withAppCode(1010);}
    private static ApiException forbidden(String message){return new ApiException(403,"FORBIDDEN",message).withAppCode(1002);}
}
