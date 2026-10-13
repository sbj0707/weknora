package com.tencent.weknora.security;

import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TenantMemberService {
    private static final Logger LOG=LoggerFactory.getLogger(TenantMemberService.class);
    private final JdbcTemplate jdbc;
    private final AuthRepository repository;
    private final TransactionTemplate writes;
    public TenantMemberService(JdbcTemplate jdbc,AuthRepository repository,PlatformTransactionManager transactions){this.jdbc=jdbc;this.repository=repository;this.writes=new TransactionTemplate(transactions);this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    public Map<String,Object> list(AuthContext auth,String pathTenant,String query,String pageText,String sizeText){
        long tenant=authorize(auth,pathTenant,"viewer");long page=positiveQuery(pageText,1,"page must be a positive integer");long size=positiveQuery(sizeText,20,"page_size must be between 1 and 100");if(size>100)throw invalid("page_size must be between 1 and 100");
        long offset;try{offset=Math.multiplyExact(page-1,size);}catch(ArithmeticException ex){throw invalid("page must be a positive integer");}
        String search=query==null?"":AuthPreferences.trimGoSpace(query);List<Object> args=new ArrayList<>();args.add(tenant);
        String base=" FROM tenant_members m";
        if(!search.isEmpty())base+=" INNER JOIN users u ON u.id=m.user_id AND u.deleted_at IS NULL";
        base+=" WHERE m.tenant_id=? AND m.deleted_at IS NULL";
        if(!search.isEmpty()){String like="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";base+=" AND (LOWER(u.email) LIKE LOWER(?) ESCAPE ? OR LOWER(u.username) LIKE LOWER(?) ESCAPE ?)";args.addAll(List.of(like,"\\",like,"\\"));}
        try {
            Long total=jdbc.queryForObject("SELECT COUNT(*)"+base,Long.class,args.toArray());
            args.add(size);args.add(offset);List<Map<String,Object>> rows=jdbc.queryForList("SELECT m.*"+base+" ORDER BY m.joined_at,m.id LIMIT ? OFFSET ?",args.toArray());
            Map<String,Map<String,Object>> users=users(rows);List<Map<String,Object>> members=new ArrayList<>();for(var row:rows)members.add(project(row,users.get(AuthRepository.string(row,"user_id"))));
            return Map.of("success",true,"data",Map.of("members",members,"total",total==null?0:total,"page",page,"page_size",size));
        }catch(DataAccessException ex){throw internal("failed to list members");}
    }
    public Map<String,Object> add(AuthContext auth,String pathTenant,Map<String,Object> body){
        long tenant=authorize(auth,pathTenant,"owner");String role=role(body),email=field(body,"email");
        if(!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw invalid("invalid request body");
        rejectMachineOwner(auth,role);
        Map<String,Object> user;try{user=repository.anyUserByEmail(AuthPreferences.trimGoSpace(email));}catch(DataAccessException ex){throw internal("failed to look up user");}
        if(user==null)throw new ApiException(404,"NOT_FOUND","user with this email is not registered; ask them to sign up first").withAppCode(1003);
        String target=AuthRepository.string(user,"id");Map<String,Object> member;
        try{member=writes.execute(status->{lockTenant(tenant);recheckActor(auth,tenant);if(find(target,tenant)!=null)throw duplicate();
            String invitedBy=auth.userId().isEmpty()||auth.userId().matches("system-[0-9]+")?null:auth.userId();
            jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status,invited_by,joined_at) VALUES(?,?,?,'active',?,?)",target,tenant,role,invitedBy,repository.timestampParameter(Instant.now()));return find(target,tenant);});}
        catch(DataAccessException ex){if(duplicateConstraint(ex))throw duplicate();throw internal("failed to add member");}
        audit(auth,tenant,"rbac.member_added",target,Map.of());
        return Map.of("success",true,"data",project(member,user));
    }
    public Map<String,Object> update(AuthContext auth,String pathTenant,String pathUser,Map<String,Object> body){
        long tenant=authorize(auth,pathTenant,"owner");String target=userId(pathUser),next=role(body);rejectMachineOwner(auth,next);
        String old;
        try{old=writes.execute(status->{lockTenant(tenant);recheckActor(auth,tenant);Map<String,Object> current=find(target,tenant);if(current==null)throw missing();String previous=AuthRepository.string(current,"role");if(previous.equals(next))return null;
            if(previous.equals("owner"))ensureOtherOwner(tenant,target);
            if(jdbc.update("UPDATE tenant_members SET role=?,updated_at=? WHERE user_id=? AND tenant_id=? AND deleted_at IS NULL",next,repository.timestampParameter(Instant.now()),target,tenant)!=1)throw missing();return previous;});}
        catch(DataAccessException ex){throw internal("failed to update member role");}
        if(old!=null)audit(auth,tenant,"rbac.member_role_changed",target,Map.of("old_role",old,"new_role",next));
        return Map.of("success",true);
    }
    public Map<String,Object> remove(AuthContext auth,String pathTenant,String pathUser){
        return removeMembership(auth,authorize(auth,pathTenant,"owner"),userId(pathUser),false);
    }
    public Map<String,Object> leave(AuthContext auth,String pathTenant){
        // This endpoint is JWT-only in the Go route gate, even for full-access machine keys.
        if(auth.isApiKey())throw new ApiException(403,"FORBIDDEN","Forbidden: API key route access denied");
        long tenant=authorize(auth,pathTenant,"viewer");
        if(auth.userId()==null||auth.userId().isEmpty())throw new ApiException(401,"UNAUTHORIZED","caller user id missing from context").withAppCode(1001);
        return removeMembership(auth,tenant,auth.userId(),true);
    }
    private Map<String,Object> removeMembership(AuthContext auth,long tenant,String target,boolean leaving){
        try{writes.executeWithoutResult(status->{lockTenant(tenant);recheckActor(auth,tenant,leaving?"viewer":"owner");Map<String,Object> current=find(target,tenant);if(current==null)throw leaving?new ApiException(404,"NOT_FOUND","you are not a member of this workspace").withAppCode(1003):missing();if(AuthRepository.string(current,"role").equals("owner"))ensureOtherOwner(tenant,target);
            if(jdbc.update("UPDATE tenant_members SET deleted_at=? WHERE user_id=? AND tenant_id=? AND deleted_at IS NULL",repository.timestampParameter(Instant.now()),target,tenant)!=1)throw leaving?new ApiException(404,"NOT_FOUND","you are not a member of this workspace").withAppCode(1003):missing();});}
        catch(DataAccessException ex){throw internal(leaving?"failed to leave workspace":"failed to remove member");}
        audit(auth,tenant,auth.userId().equals(target)?"rbac.member_left":"rbac.member_removed",target,Map.of());
        cleanup(target,tenant);
        return Map.of("success",true);
    }
    private void cleanup(String userId,long tenant){
        bestEffort("clear removed member tenant pointers",()->writes.executeWithoutResult(status->{
            var rows=jdbc.queryForList("SELECT * FROM users WHERE id=? AND deleted_at IS NULL",userId);if(rows.isEmpty())return;var user=rows.getFirst();var prefs=repository.preferences(user);
            if(AuthRepository.number(user,"tenant_id")==tenant)repository.saveHomeTenant(userId,0);
            if(AuthRepository.number(prefs,"last_active_tenant_id")==tenant){prefs.remove("last_active_tenant_id");repository.savePreferences(userId,prefs);}
        }));
        bestEffort("revoke removed member sessions",()->writes.executeWithoutResult(status->repository.revokeUserTokens(userId)));
    }
    private void audit(AuthContext auth,long tenant,String action,String target,Map<String,Object> details){
        bestEffort("record membership audit",()->writes.executeWithoutResult(status->jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_user_id,outcome,details,created_at) VALUES(?,?,?,?,'tenant_member',?,'success',"+repository.jsonParameter()+",?)",tenant,auth.userId(),auth.role(),action,target,repository.jsonString(details),repository.timestampParameter(Instant.now()))));
    }
    public void auditDenied(AuthContext auth,String rawPath,String method,String requiredRole,boolean item){
        String template=item?"/api/v1/tenants/:id/members/:user_id":"/api/v1/tenants/:id/members";
        try{Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE tenant_id=? AND actor_user_id=? AND action='rbac.access_denied' AND request_path=? AND created_at>=?",Integer.class,auth.tenantId(),auth.userId(),template,repository.timestampParameter(Instant.now().minusSeconds(60)));if(count!=null&&count>0)return;}catch(RuntimeException ignored){}
        Map<String,String> details=new LinkedHashMap<>();details.put("required_role",requiredRole);if(!rawPath.equals(template))details.put("raw_path",rawPath);
        bestEffort("record denied membership access",()->writes.executeWithoutResult(status->jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details,created_at) VALUES(?,?,?,'rbac.access_denied',?,?,'denied',"+repository.jsonParameter()+",?)",auth.tenantId(),auth.userId(),auth.role(),template,method,repository.jsonString(details),repository.timestampParameter(Instant.now()))));
    }
    private void bestEffort(String operation,Runnable action){try{action.run();}catch(RuntimeException ex){LOG.warn("Unable to {}",operation);}}
    private void lockTenant(long tenant){if(jdbc.update("UPDATE tenants SET updated_at=updated_at WHERE id=? AND deleted_at IS NULL AND status='active'",tenant)!=1)throw new ApiException(404,"NOT_FOUND","Workspace not found").withAppCode(1003);}
    private void recheckActor(AuthContext auth,long tenant){recheckActor(auth,tenant,"owner");}
    private void recheckActor(AuthContext auth,long tenant,String required){if(auth.isApiKey()||auth.crossTenantSuperuser()||!auth.rbacEnforced())return;String current=repository.role(auth.userId(),tenant);if(AuthContext.level(current)<AuthContext.level(required))throw new ApiException(403,"FORBIDDEN","Forbidden: insufficient workspace role").withAppCode(1002);}
    private void ensureOtherOwner(long tenant,String userId){Integer others=jdbc.queryForObject("SELECT COUNT(*) FROM tenant_members WHERE tenant_id=? AND user_id<>? AND role='owner' AND status='active' AND deleted_at IS NULL",Integer.class,tenant,userId);if(others==null||others==0)throw new ApiException(409,"CONFLICT","cannot demote or remove the last active owner of the tenant").withAppCode(1005);}
    private long authorize(AuthContext auth,String rawTenant,String required){long tenant=tenantId(rawTenant);if(auth.tenantId()==0||tenant!=auth.tenantId()&&!auth.crossTenantSuperuser())throw new ApiException(403,"FORBIDDEN","Access denied: URL workspace does not match the active workspace").withAppCode(1002);
        if(auth.isApiKey())auth.requireCapability("manage_members");else auth.requireRole(required);return tenant;}
    private Map<String,Object> find(String userId,long tenant){var rows=jdbc.queryForList("SELECT * FROM tenant_members WHERE user_id=? AND tenant_id=? AND deleted_at IS NULL",userId,tenant);return rows.isEmpty()?null:rows.getFirst();}
    private Map<String,Map<String,Object>> users(List<Map<String,Object>> members){Map<String,Map<String,Object>> out=new HashMap<>();if(members.isEmpty())return out;List<String> ids=members.stream().map(row->AuthRepository.string(row,"user_id")).toList();try{for(var row:jdbc.queryForList("SELECT id,email,username,avatar FROM users WHERE deleted_at IS NULL AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",ids.toArray()))out.put(AuthRepository.string(row,"id"),row);}catch(DataAccessException ex){LOG.warn("Member profile lookup unavailable");}return out;}
    Map<String,Object> project(Map<String,Object> member,Map<String,Object> user){Map<String,Object> out=new LinkedHashMap<>();out.put("user_id",AuthRepository.string(member,"user_id"));out.put("email",user==null?"":AuthRepository.string(user,"email"));out.put("username",user==null?"":AuthRepository.string(user,"username"));if(user!=null&&!AuthRepository.string(user,"avatar").isEmpty())out.put("avatar",AuthRepository.string(user,"avatar"));out.put("role",AuthRepository.string(member,"role"));out.put("status",AuthRepository.string(member,"status"));if(member.get("invited_by")!=null)out.put("invited_by",member.get("invited_by"));out.put("joined_at",AuthRepository.instant(member.get("joined_at")).toString());return out;}
    private static String role(Map<String,Object> body){String value=field(body,"role");if(AuthContext.level(value)==0)throw invalid("role must be one of owner/admin/contributor/viewer");return value;}
    private static String field(Map<String,Object> body,String key){if(body!=null&&body.get(key) instanceof String value&&!value.isEmpty())return value;throw invalid("invalid request body");}
    private static void rejectMachineOwner(AuthContext auth,String role){if(auth.isApiKey()&&role.equals("owner"))throw new ApiException(403,"FORBIDDEN","API keys cannot assign the owner role").withAppCode(1002);}
    private static long tenantId(String value){try{String text=AuthPreferences.trimGoSpace(value);if(!text.matches("[0-9]+"))throw new NumberFormatException();long id=Long.parseLong(text);if(id<1)throw new NumberFormatException();return id;}catch(RuntimeException ex){throw invalid("workspace id must be a positive integer");}}
    private static String userId(String value){String text=AuthPreferences.trimGoSpace(value);if(text.isEmpty())throw invalid("user_id is required");return text;}
    private static long positiveQuery(String text,long fallback,String error){if(text==null||AuthPreferences.trimGoSpace(text).isEmpty())return fallback;try{long value=Long.parseLong(AuthPreferences.trimGoSpace(text));if(value<1)throw new NumberFormatException();return value;}catch(NumberFormatException ex){throw invalid(error);}}
    private static boolean duplicateConstraint(Exception ex){String text=ex.getMessage().toLowerCase(Locale.ROOT);return text.contains("duplicate")||text.contains("unique constraint")||text.contains("constraint_unique");}
    private static ApiException invalid(String message){return new ApiException(400,"INVALID_REQUEST",message).withAppCode(1010);}
    private static ApiException missing(){return new ApiException(404,"NOT_FOUND","membership not found").withAppCode(1003);}
    private static ApiException duplicate(){return new ApiException(409,"CONFLICT","tenant membership already exists").withAppCode(1005);}
    private static ApiException internal(String message){return new ApiException(500,"INTERNAL_ERROR",message).withAppCode(1007).withDetails("Membership storage operation failed");}
}
