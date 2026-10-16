package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.ModelClientFactory;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.vectorstore.VectorStoreEngine;
import com.tencent.weknora.security.*;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Public raw recall/rerank service over the native physical stores, retaining original-caller grants. */
public final class HybridSearchService {
  public record Result(List<ObjectNode> rows,ObjectNode diagnostics){}
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final KnowledgeBaseReadAuthorization authorization;private final ModelClientFactory models;private final HybridSearchStore store;private final HybridSearchHydration hydration;private final RerankStage rerank;
  public HybridSearchService(JdbcTemplate jdbc,ObjectMapper json,KnowledgeBaseReadAuthorization authorization,ModelClientFactory models,HybridSearchStore store,HybridSearchHydration hydration,RerankStage rerank){this.jdbc=jdbc;this.json=json;this.authorization=authorization;this.models=models;this.store=store;this.hydration=hydration;this.rerank=rerank;}
  public Result search(AuthContext caller,KnowledgeBaseReadAuthorization.ReadAccess primary,HybridSearchRequest request,String agent,String source,CallContext call){
    call.check();var config=tenantConfig(caller.tenantId());var options=request.rerank();int count=HybridFusion.normalizeMatchCount(request.matchCount());int top=options!=null&&options.path("top_k").asLong()>0?options.path("top_k").asInt():count;
    String model="",modelSource="";double threshold=config==null?.2:config.path("rerank_threshold").asDouble();
    if(request.rerankEnabled()){
      model=options.path("model_id").asText("");if(!model.isEmpty()){var rows=modelRows(caller.tenantId(),model);if(rows.isEmpty()||!rows.getFirst().get("status").equals("active"))throw HybridSearchRequest.bad("rerank model "+quote(model)+" not found or not active",null);String type=Objects.toString(rows.getFirst().get("type"),"");if(!type.equals("Rerank"))throw HybridSearchRequest.bad("model "+quote(model)+" is a "+type+" model, not a rerank model",null);modelSource="request";}
      else if(config!=null&&!config.path("rerank_model_id").asText("").isEmpty()){model=config.path("rerank_model_id").asText();modelSource="tenant";}
      else{var available=jdbc.queryForList("SELECT id FROM models WHERE (tenant_id=? OR is_builtin=true) AND deleted_at IS NULL AND status='active' AND type='Rerank' ORDER BY created_at DESC",caller.tenantId());if(!available.isEmpty()){model=available.getFirst().get("id").toString();modelSource="auto";}}
      if(options.hasNonNull("threshold"))threshold=options.path("threshold").asDouble();
    }
    var grants=scope(caller,primary,request.ids("knowledge_base_ids"),agent,source);sameEmbedding(grants.values());int recallCount=request.rerankEnabled()?Math.max(count,top):count;var candidates=candidates(caller,primary,request,grants,recallCount,config,call);
    if(!request.rerankEnabled()){
      var rows=hydration.hydrate(take(candidates,count),grants,request.flag("skip_context_enrichment"),call);return new Result(rows,options==null?null:diag("disabled",0,rows==null?0:rows.size()));
    }
    var diagnostics=diag("",threshold,0);if(!model.isEmpty())diagnostics.put("model_id",model);if(!modelSource.isEmpty())diagnostics.put("model_source",modelSource);
    if(candidates.isEmpty()){diagnostics.put("outcome","no_candidates");return new Result(null,diagnostics);}
    var pool=take(candidates,Math.max(top,50));var hydrated=hydration.hydrate(pool,grants,true,call);var rows=hydrated==null?List.<ObjectNode>of():hydrated;diagnostics.put("candidate_count",rows.size());var finalRows=take(rows,top);
    if(rows.isEmpty()){diagnostics.put("outcome","no_candidates");finalRows=null;}
    else if(model.isEmpty())diagnostics.put("outcome","no_model");
    else{
      RerankModel client=null;try{client=models.rerank(caller,model);}catch(RuntimeException unavailable){diagnostics.put("outcome","model_unavailable").put("error",unavailable.getMessage());}
      if(client!=null){var outcome=rerank.run(call,client,request.query(),rows,new RerankStage.Options(threshold,top,.15,0,0));String saved=model,savedSource=modelSource;diagnostics=outcome.diagnostics().deepCopy();diagnostics.put("model_id",saved).put("model_source",savedSource);if(!diagnostics.path("outcome").asText().equals("model_error"))finalRows=outcome.results();}
    }
    diagnostics.put("result_count",finalRows==null?0:finalRows.size());
    if(finalRows!=null&&!finalRows.isEmpty()&&!request.flag("skip_context_enrichment")){
      var primaryHits=new ArrayList<RetrievalHit>();var byId=new HashMap<String,RetrievalHit>();for(var h:pool)byId.put(h.chunkId(),h);var rerankedRows=new HashMap<String,ObjectNode>();for(var row:finalRows){String id=row.path("id").asText();var h=byId.get(id);if(h!=null){primaryHits.add(new RetrievalHit(h.id(),h.content(),h.sourceId(),h.sourceType(),h.chunkId(),h.knowledgeId(),h.knowledgeBaseId(),h.tagId(),row.path("score").asDouble(),h.matchType()));rerankedRows.put(id,row);}}
      var enriched=hydration.hydrate(primaryHits,grants,false,call);if(enriched!=null){var replaced=new ArrayList<ObjectNode>();for(var r:enriched)replaced.add(rerankedRows.getOrDefault(r.path("id").asText(),r));finalRows=replaced;}
    }
    return new Result(finalRows,diagnostics);
  }
  private Map<String,KnowledgeBaseReadAuthorization.ReadAccess> scope(AuthContext caller,KnowledgeBaseReadAuthorization.ReadAccess primary,List<String> requested,String agent,String source){
    var grants=new LinkedHashMap<String,KnowledgeBaseReadAuthorization.ReadAccess>();var ids=requested.isEmpty()?List.of(primary.knowledgeBaseId()):requested;
    if(!ids.contains(primary.knowledgeBaseId()))throw missing();
    for(String id:new LinkedHashSet<>(ids)){var exists=jdbc.queryForList("SELECT id FROM knowledge_bases WHERE id=? AND deleted_at IS NULL",id);if(exists.isEmpty())continue;if(!caller.allowsKnowledgeBase(id))throw new ApiException(403,"FORBIDDEN","API key scope does not allow one or more knowledge bases").withAppCode(1002);try{grants.put(id,id.equals(primary.knowledgeBaseId())?primary:authorization.authorizeRead(caller,id,agent,source));}catch(ApiException denial){if(denial.status()==403)throw missing();throw denial;}}
    return grants;
  }
  private List<RetrievalHit> candidates(AuthContext caller,KnowledgeBaseReadAuthorization.ReadAccess primary,HybridSearchRequest request,Map<String,KnowledgeBaseReadAuthorization.ReadAccess> grants,int count,JsonNode config,CallContext call){
    var vectorKBs=new ArrayList<String>();var faqKBs=new LinkedHashSet<String>();var keywords=new ArrayList<String>();KnowledgeBaseReadAuthorization.ReadAccess embeddingKB=hasEmbedding(primary)?primary:null;
    for(var access:grants.values()){var kb=access.knowledgeBase();if(hasEmbedding(access)){vectorKBs.add(access.knowledgeBaseId());if(embeddingKB==null)embeddingKB=access;}if(kb.path("type").asText().equals("faq"))faqKBs.add(access.knowledgeBaseId());else if(index(kb,"keyword_enabled"))keywords.add(access.knowledgeBaseId());}
    float[] vector=request.embedding();if(vector.length==0&&embeddingKB!=null&&!request.flag("disable_vector_match")){var model=models.embedding(execution(caller,embeddingKB.effectiveTenantId()),embeddingKB.knowledgeBase().path("embedding_model_id").asText());vector=model.embed(call,request.query(),true);if(vector.length>0&&model.dimensions()>0&&vector.length!=model.dimensions())throw new ApiException(503,"VECTOR_STORE_UNAVAILABLE","embedding vector dimension does not match the configured model").withAppCode(2201).withDetails(Map.of("model",model.modelName(),"expected_dimension",model.dimensions(),"actual_dimension",vector.length,"hint","check the embedding model configuration and rebuild the affected index ("+model.dimensions()+" dimensions)"));}
    var bindings=new HashMap<String,VectorStoreEngine>();
    try(var resolution=HybridSearchDeadline.start(call,java.time.Duration.ofSeconds(12))){for(var access:grants.values()){String id=access.knowledgeBase().path("vector_store_id").asText("");if(!id.isEmpty())bindings.put(access.knowledgeBaseId(),store.bound(access.effectiveTenantId(),id,call));}}
    var tenantEngines=jdbc.queryForList("SELECT retriever_engines FROM tenants WHERE id=? AND deleted_at IS NULL",caller.tenantId());var supported=grants.keySet().stream().anyMatch(id->!bindings.containsKey(id))?store.capabilities(tenantEngines.isEmpty()?null:decode(tenantEngines.getFirst().get("retriever_engines"))):Set.<String>of();vectorKBs.removeIf(id->!(bindings.containsKey(id)?bindings.get(id).capabilities():supported).contains("vector"));keywords.removeIf(id->!(bindings.containsKey(id)?bindings.get(id).capabilities():supported).contains("keywords"));
    int depth=HybridFusion.retrievalPool(count,request.ids("knowledge_base_ids").isEmpty()?1:request.ids("knowledge_base_ids").size());var configFusion=new HybridFusion.Config(config==null?0:config.path("rrf_k").asInt(),config==null?0:config.path("rrf_vector_weight").asDouble(),config==null?0:config.path("rrf_keyword_weight").asDouble());
    var result=recall(request,vector,vectorKBs,keywords,faqKBs,depth,grants,bindings,call);var fused=HybridFusion.fuse(result.get(0),result.get(1),configFusion);
    boolean full=result.get(0).stream().anyMatch(l->l.size()>=depth);
    if(primary.knowledgeBase().path("type").asText().equals("faq")&&fused.size()<count&&full&&depth<500){int current=Math.min(500,Math.max(count*3,depth*2));for(int attempt=0;attempt<5;attempt++){result=recall(request,vector,vectorKBs,keywords,faqKBs,current,grants,bindings,call);var vectors=result.get(0);var keys=result.get(1);if(vectors.isEmpty()&&keys.isEmpty())break;fused=hydration.negativeQuestions(HybridFusion.fuse(vectors,keys,configFusion),new HashSet<>(grants.keySet()),request.query());int limit=current;boolean exhausted=java.util.stream.Stream.concat(vectors.stream(),keys.stream()).noneMatch(l->l.size()>=limit);if(fused.size()>=count||exhausted||current==500)break;current=Math.min(500,current*2);}}
    else fused=hydration.negativeQuestions(fused,faqKBs,request.query());return fused;
  }
  private List<List<List<RetrievalHit>>> recall(HybridSearchRequest request,float[] vector,List<String> vectorKBs,List<String> keywordKBs,Set<String> faqKBs,int depth,Map<String,KnowledgeBaseReadAuthorization.ReadAccess> grants,Map<String,VectorStoreEngine> bindings,CallContext call){
    var vectors=new ArrayList<List<RetrievalHit>>();var keywords=new ArrayList<List<RetrievalHit>>();var groups=new LinkedHashMap<String,List<String>>();for(var g:grants.values())groups.computeIfAbsent(g.effectiveTenantId()+"\u0000"+g.knowledgeBase().path("vector_store_id").asText(""),k->new ArrayList<>()).add(g.knowledgeBaseId());
    if(grants.keySet().stream().anyMatch(id->!bindings.containsKey(id)))store.resolve(call);
    var recalled=HybridSearchFanout.run(new ArrayList<>(groups.values()),call,(group,child)->{
      var groupVectors=new ArrayList<List<RetrievalHit>>();var groupKeys=new ArrayList<List<RetrievalHit>>();var physical=bindings.get(group.getFirst());
      try(var groupBudget=store.groupBudget(child,groups.size()>1)){

      var documentVector=vectorKBs.stream().filter(id->group.contains(id)&&!faqKBs.contains(id)).toList();var faqVector=vectorKBs.stream().filter(id->group.contains(id)&&faqKBs.contains(id)).toList();var groupKeywords=keywordKBs.stream().filter(group::contains).toList();
      if(!request.flag("disable_vector_match")&&vector.length>0)for(var ids:List.of(documentVector,faqVector))if(!ids.isEmpty())for(var result:store.retrieve(new RetrievalQuery(request.query(),vector,ids,request.ids("knowledge_ids"),request.ids("tag_ids"),depth,request.threshold("vector_threshold"),"vector"),physical,child))if(!result.results().isEmpty())groupVectors.add(result.results());
      if(!request.flag("disable_keywords_match")&&!groupKeywords.isEmpty())for(var result:store.retrieve(new RetrievalQuery(request.query(),new float[0],groupKeywords,request.ids("knowledge_ids"),request.ids("tag_ids"),depth,request.threshold("keyword_threshold"),"keywords"),physical,child))if(!result.results().isEmpty())groupKeys.add(result.results());
      }return List.of(groupVectors,groupKeys);
    });
    for(var group:recalled){vectors.addAll(group.get(0));keywords.addAll(group.get(1));}
    return List.of(vectors,keywords);
  }

  private void sameEmbedding(Collection<KnowledgeBaseReadAuthorization.ReadAccess> grants){String first="";for(var g:grants){String id=g.knowledgeBase().path("embedding_model_id").asText("");if(id.isEmpty())continue;var rows=modelRows(g.effectiveTenantId(),id);String key=id;if(!rows.isEmpty()){var row=rows.getFirst();key=row.get("name")+"|"+decode(row.get("parameters")).path("base_url").asText("");}if(first.isEmpty())first=key;else if(!first.equals(key))throw HybridSearchRequest.bad("selected knowledge bases use different embedding models; multi-KB search requires every knowledge base to share a single embedding model",null);}}
  private JsonNode tenantConfig(long caller){var rows=jdbc.queryForList("SELECT retrieval_config FROM tenants WHERE id=? AND deleted_at IS NULL",caller);return rows.isEmpty()?null:decode(rows.getFirst().get("retrieval_config"));}
  private JsonNode decode(Object raw){try{return raw==null||raw.toString().isEmpty()?null:raw instanceof byte[] bytes?json.readTree(bytes):json.readTree(raw.toString());}catch(IOException bad){throw new ApiException(500,"INTERNAL_SERVER_ERROR","Invalid persisted retrieval configuration").withAppCode(1007);}}
  private List<Map<String,Object>> modelRows(long tenant,String id){return jdbc.queryForList("SELECT * FROM models WHERE id=? AND (tenant_id=? OR is_builtin=true) AND deleted_at IS NULL",id,tenant);}
  private static boolean index(JsonNode kb,String key){var strategy=kb.path("indexing_strategy");return !strategy.isObject()||strategy.isEmpty()?true:strategy.path(key).asBoolean();}
  private static boolean hasEmbedding(KnowledgeBaseReadAuthorization.ReadAccess g){return index(g.knowledgeBase(),"vector_enabled")&&!g.knowledgeBase().path("embedding_model_id").asText("").isEmpty();}
  private ObjectNode diag(String outcome,double threshold,int count){return json.createObjectNode().put("applied",false).put("outcome",outcome).put("threshold",threshold).put("effective_threshold",threshold).put("top_score",0d).put("candidate_count",count).put("result_count",count);}
  private static AuthContext execution(AuthContext caller,long owner){return new AuthContext(caller.userId(),owner,caller.role(),caller.systemAdmin(),caller.apiKeyId(),caller.fullAccess(),caller.capabilities(),caller.knowledgeBaseIds(),caller.crossTenantSuperuser(),caller.rbacEnforced());}
  private static <T> List<T> take(List<T> rows,int count){return rows.size()<=count?rows:rows.subList(0,count);}
  private String quote(String value){try{return json.writeValueAsString(value);}catch(IOException e){throw new IllegalArgumentException(e);}}
  private static ApiException missing(){return new ApiException(404,"NOT_FOUND","knowledge base not found").withAppCode(1003);}
}
