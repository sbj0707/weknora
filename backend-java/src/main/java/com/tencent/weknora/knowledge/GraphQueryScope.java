package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** A graph namespace and a hydrated SQL row both require the original caller's read grant. */
public final class GraphQueryScope {
  public record Target(String kb, long owner, boolean wholeBase, Set<String> documents, Set<String> tags) {
    public Target { kb = Objects.requireNonNullElse(kb, ""); documents = documents == null ? Set.of() : Set.copyOf(documents); tags = tags == null ? Set.of() : Set.copyOf(tags); }
    public boolean allows(ObjectNode chunk, ObjectNode knowledge) {
      return owner > 0 && owner == chunk.path("tenant_id").asLong()
          && owner == knowledge.path("tenant_id").asLong()
          && kb.equals(chunk.path("knowledge_base_id").asText())
          && kb.equals(knowledge.path("knowledge_base_id").asText())
          && knowledge.path("id").asText().equals(chunk.path("knowledge_id").asText())
          && (wholeBase || documents.contains(chunk.path("knowledge_id").asText()))
          && (tags.isEmpty() || tags.contains(chunk.path("tag_id").asText()));
    }
  }
  private final KnowledgeBaseReadAuthorization reads;
  private final JdbcTemplate jdbc;
  private final KnowledgeContentReadRepository documents;

  public GraphQueryScope(KnowledgeBaseReadAuthorization reads, JdbcTemplate jdbc) {
    this.reads = reads;
    this.jdbc = jdbc;
    try { this.documents = new KnowledgeContentReadRepository(jdbc,new com.fasterxml.jackson.databind.ObjectMapper()); } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
  }

  public List<Target> enabled(AuthContext caller, List<Target> targets, String agent, String source) {
    caller.requireRole("viewer");
    if(caller.isApiKey()&&!caller.fullAccess()&&!caller.capabilities().contains("chat"))return List.of();
    var out = new ArrayList<Target>();
    for (var target : targets) {
      if (target.kb().isEmpty() || target.owner() <= 0 || !target.wholeBase() && target.documents().isEmpty()) continue;
      try {
        caller.requireKnowledgeBase(target.kb());
        var grant = reads.authorizeChatRead(caller, target.kb(), agent, source);
        if (grant.effectiveTenantId() != target.owner()
            || !grant.knowledgeBase().path("extract_config").path("enabled").asBoolean()) continue;
        var docs = new LinkedHashSet<String>();
        boolean corrupt=false;
        for (var id : target.documents()) {
          var rows=jdbc.queryForList("SELECT * FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND id=? AND deleted_at IS NULL",target.owner(),target.kb(),id);
          if (!rows.isEmpty()) try {documents.projectKnowledge(rows.getFirst());docs.add(id);} catch(RuntimeException invalid){corrupt=true;break;}
        }
        if(corrupt)continue;
        if (target.wholeBase() || !docs.isEmpty()) out.add(new Target(target.kb(), target.owner(), target.wholeBase(), docs, target.tags()));
      } catch (ApiException denied) {
        if (denied.status() != 403 && denied.status() != 404) throw denied;
      }
    }
    return List.copyOf(out);
  }

  public void check(AuthContext caller, Target target, String agent, String source) {
    var grant = reads.authorizeChatRead(caller, target.kb(), agent, source);
    if (grant.effectiveTenantId() != target.owner() || !grant.knowledgeBase().path("extract_config").path("enabled").asBoolean()) throw new ApiException(403, "FORBIDDEN", "graph target owner changed");
  }
}
