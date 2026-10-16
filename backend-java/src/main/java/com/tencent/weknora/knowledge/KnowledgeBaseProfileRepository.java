package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.task.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Existing Go KB/document profile columns; scoped writes join the native lease fence. */
public final class KnowledgeBaseProfileRepository {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeRepository knowledge;private final TransactionTemplate tx;private final Clock clock;private final boolean postgres;
  public KnowledgeBaseProfileRepository(JdbcTemplate jdbc,ObjectMapper json,KnowledgeRepository knowledge,PlatformTransactionManager manager,Clock clock){this.jdbc=jdbc;this.json=json;this.knowledge=knowledge;tx=new TransactionTemplate(manager);this.clock=clock;postgres=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));}
  public Optional<ObjectNode> base(long owner,String id){var rows=jdbc.queryForList("SELECT id FROM knowledge_bases WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,id);return rows.isEmpty()?Optional.empty():Optional.of(knowledge.knowledgeBase(owner,id));}
  public List<ObjectNode> rows(long owner,String id){var rows=jdbc.queryForList("SELECT id,title,file_name,file_type,folder_path,created_at,profile FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL AND parse_status IN ('completed','finalizing') AND enable_status='enabled' ORDER BY created_at ASC",owner,id);var result=new ArrayList<ObjectNode>();for(var r:rows){var out=json.createObjectNode();for(String k:List.of("id","title","file_name","file_type","folder_path"))out.put(k,Objects.toString(r.get(k),""));out.put("created_at",r.get("created_at")==null?"0001-01-01T00:00:00Z":KnowledgeBaseRepository.timestamp(r.get("created_at")));Object profile=r.get("profile");if(profile!=null)try{JsonNode p=profile instanceof byte[] b?json.readTree(b):json.readTree(profile.toString());if(p!=null&&!p.isNull())out.set("profile",p);}catch(java.io.IOException e){throw new IllegalStateException("invalid document profile",e);}result.add(out);}
    for(int start=0;start<result.size();start+=500){var batch=result.subList(start,Math.min(start+500,result.size()));var ids=batch.stream().map(r->r.path("id").asText()).toList();var params=new ArrayList<Object>();params.add(owner);params.add(id);params.addAll(ids);var tags=jdbc.queryForList("SELECT rel.knowledge_id,t.name FROM knowledge_tag_relations rel JOIN knowledge_tags t ON t.id=rel.tag_id WHERE t.tenant_id=? AND t.knowledge_base_id=? AND rel.knowledge_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")",params.toArray());var byId=new HashMap<String,ArrayNode>();for(var tag:tags){String name=SummaryGenerationText.trim(Objects.toString(tag.get("name"),""));if(!name.isEmpty())byId.computeIfAbsent(tag.get("knowledge_id").toString(),k->json.createArrayNode()).add(name);}for(var r:batch)if(byId.containsKey(r.path("id").asText()))r.set("tags",byId.get(r.path("id").asText()));}return result;
  }
  public void save(TaskRecord job,ObjectNode profile){save(job.tenantId(),job.payload().path("knowledge_base_id").asText(),profile,()->fence(job));}
  public void saveSynchronous(long owner,String id,ObjectNode profile,Runnable guard){save(owner,id,profile,guard);}
  private void save(long owner,String id,ObjectNode profile,Runnable guard){tx.executeWithoutResult(s->{guard.run();Object value;try{value=postgres?json.writeValueAsString(profile):json.writeValueAsBytes(profile);}catch(java.io.IOException e){throw new IllegalStateException(e);}int changed=jdbc.update("UPDATE knowledge_bases SET generated_profile="+(postgres?"?::jsonb":"?")+",updated_at=? WHERE tenant_id=? AND id=? AND deleted_at IS NULL",value,postgres?Timestamp.from(clock.instant()):clock.instant().toString(),owner,id);if(changed!=1)throw new IllegalStateException("save knowledge base profile: knowledge base not found");guard.run();});}
  private void fence(TaskRecord job){var rows=jdbc.queryForList("SELECT status,attempts,lease_owner,lease_until,cancel_requested FROM java_task_jobs WHERE tenant_id=? AND id=?"+(postgres?" FOR UPDATE":""),job.tenantId(),job.id());if(rows.isEmpty())throw new CancellationException("knowledge base profile lease unavailable");var r=rows.getFirst();if(!Objects.equals(r.get("status"),"running")||number(r.get("attempts"))!=job.attempts()||!Objects.equals(r.get("lease_owner"),job.leaseOwner())||number(r.get("lease_until"))<=clock.millis()||Boolean.TRUE.equals(r.get("cancel_requested"))||number(r.get("cancel_requested"))!=0)throw new CancellationException("knowledge base profile lease lost or canceled");}
  private static long number(Object v){return v instanceof Number n?n.longValue():0;}
}
