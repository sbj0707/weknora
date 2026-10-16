package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.number;
import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.text;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Entirely read-only: no destination deletion, file/index mutation or tag creation before preflight. */
final class KnowledgeClonePlanner {
  record Plan(List<Map<String,Object>> add,List<String> remove) {}
  record Faq(Map<String,Map<String,Object>> source,Map<String,Map<String,Object>> target) {}

  private final JdbcTemplate jdbc;
  private final KnowledgeTransferRepository repository;
  private final KnowledgeTransferMetadata metadata;

  KnowledgeClonePlanner(JdbcTemplate jdbc,KnowledgeTransferRepository repository,ObjectMapper json) {
    this.jdbc=jdbc;this.repository=repository;this.metadata=new KnowledgeTransferMetadata(json);
  }

  Plan plan(KnowledgeTransferRepository.Scope scope,JsonNode source,JsonNode target)throws Exception {
    KnowledgeTransferRepository.requirePair(scope,source,target);
    try {
      var src=documents(scope.tenant(),scope.source());
      var dst=documents(scope.tenant(),scope.target());
      preflight(scope.tenant(),scope.source(),src);
      preflight(scope.tenant(),scope.target(),dst);
      Comparator<Map<String,Object>> order=(a,b)->Arrays.compareUnsigned(
          text(a,"id").getBytes(java.nio.charset.StandardCharsets.UTF_8),
          text(b,"id").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      src.sort(order);dst.sort(order);
      var add=new ArrayList<Map<String,Object>>();var remove=new ArrayList<String>();
      var used=new HashSet<String>();
      for(var row:src) {
        String id=text(row,"id");
        if(!text(row,"parse_status").equals("completed"))
          throw new IllegalArgumentException("source knowledge "+id+" is not completed");
        boolean matched=false;
        for(var other:dst) {
          // Go decodes even an already-used candidate before checking used, including errors.
          var state=metadata.read(other.get("metadata"));
          String targetId=text(other,"id");
          if(!used.contains(targetId)&&KnowledgeTransferMetadata.matches(
              state,scope.task(),scope.source(),scope.target(),id,"clone","")) {
            used.add(targetId);
            if(state.path("phase").asText().equals("done")&&text(other,"parse_status").equals("completed"))matched=true;
            else remove.add(targetId);
            break;
          }
        }
        String hash=text(row,"file_hash");
        if(!matched&&!hash.isEmpty())for(var other:dst) {
          String targetId=text(other,"id");
          if(!used.contains(targetId)&&text(other,"file_hash").equals(hash)&&text(other,"parse_status").equals("completed")) {
            used.add(targetId);matched=true;break;
          }
        }
        if(!matched)add.add(new LinkedHashMap<>(row));
      }
      for(var row:dst) {
        String id=text(row,"id");
        if(used.contains(id))continue;
        if(Set.of("processing","pending","deleting").contains(text(row,"parse_status")))
          throw new IllegalArgumentException("target knowledge "+id+" is busy");
        remove.add(id);
      }
      return new Plan(List.copyOf(add),List.copyOf(remove));
    }catch(org.springframework.dao.DataAccessException failure){throw new IllegalStateException(ChunkEditService.diagnostic(failure),failure);}
  }

  Faq preflightFaq(KnowledgeTransferRepository.Scope scope,JsonNode source,JsonNode target) {
    KnowledgeTransferRepository.requirePair(scope,source,target);
    try {
      return new Faq(faq(scope.tenant(),scope.source()),faq(scope.tenant(),scope.target()));
    }catch(org.springframework.dao.DataAccessException failure){throw new IllegalStateException(ChunkEditService.diagnostic(failure),failure);}
  }

  private Map<String,Map<String,Object>> faq(long tenant,String kb) {
    var rows=documents(tenant,kb);
    if(rows.size()>1)throw new IllegalArgumentException("FAQ knowledge base has multiple document containers");
    var out=new LinkedHashMap<String,Map<String,Object>>();
    for(var row:rows) {
      binding(tenant,kb,row);
      if(!text(row,"type").equals("faq"))throw new IllegalArgumentException("invalid FAQ container");
      for(var chunk:repository.chunks(tenant,text(row,"id"),Set.of(kb))) {
        if(!text(chunk,"chunk_type").equals("faq"))throw new IllegalArgumentException("invalid FAQ chunk type");
        out.put(text(chunk,"id"),new LinkedHashMap<>(chunk));
      }
    }
    return Collections.unmodifiableMap(out);
  }

  private List<Map<String,Object>> documents(long tenant,String kb) {
    return new ArrayList<>(KnowledgeTransferRows.query(jdbc,"SELECT * FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL ORDER BY created_at DESC",tenant,kb));
  }
  private void preflight(long tenant,String kb,List<Map<String,Object>> rows) {
    for(var row:rows) {
      binding(tenant,kb,row);
      repository.chunks(tenant,text(row,"id"),Set.of(kb));
    }
  }
  private static void binding(long tenant,String kb,Map<String,Object> row) {
    if(row==null||text(row,"id").isEmpty()||number(row,"tenant_id")!=tenant||!text(row,"knowledge_base_id").equals(kb))
      throw new IllegalArgumentException("knowledge binding does not match the transfer scope");
  }
}
