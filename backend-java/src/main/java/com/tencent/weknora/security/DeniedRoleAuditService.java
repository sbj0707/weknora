package com.tencent.weknora.security;

import java.time.Instant;import java.util.*;import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.*;import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;

/** Go LogDenied: deduplicate by route template, retaining raw path in details. */
@Service
final class DeniedRoleAuditService {
 private static final Logger LOG=LoggerFactory.getLogger(DeniedRoleAuditService.class);
 private final JdbcTemplate jdbc;private final AuthRepository repository;private final TransactionTemplate writes;private final Supplier<Instant> clock;
 @Autowired DeniedRoleAuditService(JdbcTemplate jdbc,AuthRepository repository,PlatformTransactionManager transactions){this(jdbc,repository,transactions,Instant::now);}
 DeniedRoleAuditService(JdbcTemplate jdbc,AuthRepository repository,PlatformTransactionManager transactions,Supplier<Instant> clock){this.jdbc=jdbc;this.repository=repository;this.clock=clock;writes=new TransactionTemplate(transactions);writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 void record(AuthContext auth,String raw,String template,String method,String required){
  Instant now=clock.get();String path=template==null||template.isEmpty()?raw:template;
  // Go probes outside the insert transaction. A failed probe must still attempt
  // an insert, including on PostgreSQL where the failed statement aborts a tx.
  try{Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE tenant_id=? AND actor_user_id=? AND action='rbac.access_denied' AND request_path=? AND created_at>=?",Integer.class,auth.tenantId(),auth.userId(),path,repository.timestampParameter(now.minusSeconds(60)));if(count!=null&&count>0)return;}catch(RuntimeException ignored){}
  Map<String,String> details=new LinkedHashMap<>();details.put("required_role",required);if(raw!=null&&!raw.isEmpty()&&!raw.equals(path))details.put("raw_path",raw);
  try{writes.executeWithoutResult(status->jdbc.update("INSERT INTO audit_logs(tenant_id,actor_user_id,actor_role,action,request_path,request_method,outcome,details,created_at) VALUES(?,?,?,'rbac.access_denied',?,?,'denied',"+repository.jsonParameter()+",?)",auth.tenantId(),auth.userId(),AuthContext.level(auth.role())==0?"viewer":auth.role(),path,method,repository.jsonString(details),repository.timestampParameter(now)));}catch(RuntimeException ex){LOG.warn("Unable to record denied workspace role");}
 }
}
