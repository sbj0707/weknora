package com.tencent.weknora.security;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** JWT-only recovery of a registrant's unowned home workspace. Never an ownership transfer. */
@Service
public class TenantMembershipRecovery {
    private static final Logger LOG=LoggerFactory.getLogger(TenantMembershipRecovery.class);
    private final JdbcTemplate jdbc;
    private final AuthRepository repository;
    private final TransactionTemplate writes;
    private record Recovered(String role,boolean inserted){}
    public TenantMembershipRecovery(JdbcTemplate jdbc,AuthRepository repository,PlatformTransactionManager transactions){this.jdbc=jdbc;this.repository=repository;this.writes=new TransactionTemplate(transactions);this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    String repairHomeMembership(String userId,long target){
        Recovered result;
        try{result=writes.execute(status->{
            // Same lock as native membership mutations; two registrants cannot both claim an orphan.
            if(jdbc.update("UPDATE tenants SET updated_at=updated_at WHERE id=? AND status='active' AND deleted_at IS NULL",target)!=1)throw new ApiException(401,"UNAUTHORIZED","Unauthorized: invalid workspace");
            var users=jdbc.queryForList("SELECT tenant_id FROM users WHERE id=? AND is_active=true AND deleted_at IS NULL",userId);
            if(users.isEmpty())throw new ApiException(401,"UNAUTHORIZED","Unauthorized: inactive user");
            if(AuthRepository.number(users.getFirst(),"tenant_id")!=target)return new Recovered(null,false);
            var existing=jdbc.queryForList("SELECT role,status FROM tenant_members WHERE user_id=? AND tenant_id=? AND deleted_at IS NULL",userId,target);
            if(!existing.isEmpty()){var member=existing.getFirst();String role=AuthRepository.string(member,"role");return new Recovered(AuthRepository.string(member,"status").equals("active")&&AuthContext.level(role)>0?role:null,false);}
            if(!jdbc.queryForList("SELECT id FROM tenant_members WHERE tenant_id=? AND status='active' AND deleted_at IS NULL LIMIT 1",target).isEmpty())return new Recovered(null,false);
            jdbc.update("INSERT INTO tenant_members(user_id,tenant_id,role,status,invited_by,joined_at) VALUES(?,?,'owner','active',NULL,?)",userId,target,repository.timestampParameter(Instant.now()));
            return new Recovered("owner",true);
        });}catch(DataAccessException ex){LOG.warn("Unable to repair orphan workspace membership");return null;}
        if(result==null)return null;
        if(result.inserted()){
            // Auth session context is attached after role resolution in Go, so the actor user is empty and TenantRoleFromContext defaults to Viewer.
            try{writes.executeWithoutResult(status->jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,target_type,target_user_id,outcome,created_at) VALUES(?,'','viewer','rbac.member_added','tenant_member',?,'success',?)",target,userId,repository.timestampParameter(Instant.now())));}catch(DataAccessException ex){LOG.warn("Unable to record orphan workspace recovery audit");}
        }
        return result.role();
    }
}
