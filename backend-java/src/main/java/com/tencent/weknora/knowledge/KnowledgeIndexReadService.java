package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.retrieval.RerankMetadata;
import com.tencent.weknora.security.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;

/** Native read models for document build evidence and complete chunk inspection. */
@Service
public class KnowledgeIndexReadService {
  private final KnowledgeIndexReadRepository repository;
  private final ObjectMapper json;
  private final KnowledgeProcessConfig process;
  private final GraphExtractionStore graph;
  public KnowledgeIndexReadService(KnowledgeIndexReadRepository repository, ObjectMapper json) {
    this(repository,json,(GraphExtractionStore)null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public KnowledgeIndexReadService(KnowledgeIndexReadRepository repository,ObjectMapper json,org.springframework.beans.factory.ObjectProvider<Neo4jGraphRepository> graph){this(repository,json,graph.getIfAvailable());}
  public KnowledgeIndexReadService(KnowledgeIndexReadRepository repository,ObjectMapper json,GraphExtractionStore graph){this.repository=repository;this.json=json;this.graph=graph;process=new KnowledgeProcessConfig(json);}

  public ObjectNode status(ObjectNode knowledge, KnowledgeBaseReadAuthorization.ReadAccess grant) {
    scope(knowledge,grant);
    List<ObjectNode> spans=attempt(()->repository.spans(knowledge.path("id").asText()));if(spans==null)spans=List.of();
    var fresh=repository.knowledgeBase(grant.effectiveTenantId(),grant.knowledgeBaseId());
    var rows=indexes(knowledge,fresh,false);
    String doc=knowledge.path("id").asText(),kb=grant.knowledgeBaseId();long owner=grant.effectiveTenantId();
    Map<String,List<String>> stores=attempt(()->repository.stores(owner,fresh));
    Map<String,KnowledgeIndexReadRepository.Counts> counts=new HashMap<>();
    for(int i=0;i<2;i++) {
      String kind=rows.get(i).path("kind").asText();
      var count=attempt(()->count(stores,kind,kb,doc));
      if(count!=null) {counts.put(kind,count);rows.get(i).put("count",count.chunks());}
    }
    if(graph!=null&&graph.enabled()){var actual=attempt(()->graph.counts(kb,doc));if(actual!=null)rows.get(2).put("count",actual.path("Entities").asLong()).put("relation_count",actual.path("Relations").asLong());}
    var pages=attempt(()->repository.wikiPreview(owner,kb,doc));
    if(pages!=null) {
      rows.get(3).put("count",pages.size());
      pages=new ArrayList<>(pages);pages.sort(Comparator.comparing(p->p.path("slug").asText()));
      var preview=json.createArrayNode();
      for(var page:pages.subList(0,Math.min(50,pages.size())))preview.add(json.createObjectNode().put("slug",page.path("slug").asText()).put("title",page.path("title").asText()));
      if(!preview.isEmpty())rows.get(3).set("pages",preview);
    }
    boolean stale=false;
    var chunks=attempt(()->repository.questionChunks(owner,doc));
    if(chunks!=null) {
      long total=0;boolean valid=true;
      for(var chunk:chunks) {
        if(chunk.path("tenant_id").asLong()!=owner || !chunk.path("knowledge_id").asText().equals(doc) || !chunk.path("knowledge_base_id").asText().equals(kb)) {valid=false;break;}
        try {
          var metadata=metadata(chunk);var questions=metadata.path("generated_questions");total+=questions.size();
          for(var question:questions)if(!current(metadata,question,chunk.path("content_revision").asLong()))stale=true;
        } catch(Exception ex) {valid=false;break;}
      }
      if(valid)rows.get(4).put("count",total);
    }
    Long indexed=null;
    for(int i=0;i<2;i++)if(rows.get(i).path("enabled").asBoolean()) {
      var count=counts.get(rows.get(i).path("kind").asText());
      if(count==null) {indexed=null;break;}
      indexed=indexed==null?count.questions():Math.min(indexed,count.questions());
    }
    if(indexed!=null)rows.get(4).put("indexed_count",indexed);
    String model=knowledge.path("embedding_model_id").asText();
    for(var span:spans)if(span.path("name").asText().equals("embedding") && span.path("input").path("model_id").isTextual() && !span.path("input").path("model_id").asText().isEmpty())model=span.path("input").path("model_id").asText();
    model(rows.getFirst(),owner,model);
    ObjectNode out=json.createObjectNode().put("knowledge_id",doc).put("attempt",0).put("refreshing",KnowledgeIndexStates.inFlight(knowledge.path("parse_status").asText()));
    for(var row:rows) {
      KnowledgeIndexStates.document(row,knowledge.path("parse_status").asText(),spans);
      if(row.path("kind").asText().equals("questions") && stale && row.path("status").asText().equals("completed"))row.put("status","stale").put("reason","content_changed");
      if(row.hasNonNull("last_built_at") && (!out.has("last_built_at") || KnowledgeIndexStates.time(row.get("last_built_at")).isAfter(KnowledgeIndexStates.time(out.get("last_built_at")))))out.set("last_built_at",row.get("last_built_at"));
    }
    for(var span:spans)out.put("attempt",Math.max(out.path("attempt").asInt(),span.path("attempt").asInt()));
    out.set("indexes",json.valueToTree(rows));return out;
  }

  public ObjectNode detail(ObjectNode knowledge, KnowledgeBaseReadAuthorization.ReadAccess grant, String chunkId) {
    scope(knowledge,grant);
    long owner=grant.effectiveTenantId();String kb=grant.knowledgeBaseId(),doc=knowledge.path("id").asText();
    ObjectNode chunk=repository.chunk(owner,kb,doc,chunkId);
    if(chunk==null)throw new ApiException(404,"NOT_FOUND","Chunk not found").withAppCode(1003);
    var fresh=repository.knowledgeBase(owner,kb);
    ObjectNode out=json.createObjectNode();
    out.set("source_content",chunk.remove("source_content"));out.set("context_header",chunk.remove("context_header"));out.set("chunk",chunk);
    out.put("knowledge_title",knowledge.path("title").asText());model(out,owner,knowledge.path("embedding_model_id").asText());
    boolean refreshing=chunk.path("index_status").asText().equals("processing")||KnowledgeIndexStates.inFlight(knowledge.path("parse_status").asText());out.put("refreshing",refreshing);
    var children=attempt(()->repository.children(owner,kb,doc,chunkId));
    if(children!=null)for(var child:children) {
      if(child.path("tenant_id").asLong()!=owner || !child.path("knowledge_id").asText().equals(doc) || !child.path("knowledge_base_id").asText().equals(kb) || !child.path("parent_chunk_id").asText().equals(chunkId))throw new IllegalArgumentException("Invalid child chunk scope");
      child.remove("__index_raw_metadata");
    }
    out.put("children_available",children!=null);out.set("children",json.valueToTree(children));
    var rows=indexes(knowledge,fresh,true);
    var stores=attempt(()->repository.stores(owner,fresh));
    if(stores!=null)for(int i=0;i<2;i++) {
      var row=rows.get(i);String kind=row.path("kind").asText();
      if(!stores.getOrDefault(kind,List.of()).isEmpty())row.set("stores",json.valueToTree(stores.get(kind)));
      var records=attempt(()->records(stores,kind,kb,doc,chunkId));
      if(records!=null) {row.put("available",true);if(!records.isEmpty())row.set("records",json.valueToTree(records));}
    }
    if(graph!=null&&graph.enabled()){var actual=attempt(()->graph.detail(kb,doc,chunkId));if(actual!=null){rows.get(2).put("available",true);rows.get(2).set("graph",actual);}}
    var pages=attempt(()->repository.wiki(owner,kb,doc,chunkId));
    if(pages!=null) {
      rows.get(3).put("available",true);ArrayNode wiki=json.createArrayNode();
      for(var page:pages) {boolean cited=false;for(var ref:page.path("chunk_refs"))if(ref.asText().equals(chunkId))cited=true;var item=json.createObjectNode().put("scope",cited?"chunk":"document");item.set("page",page);wiki.add(item);}
      if(!wiki.isEmpty())rows.get(3).set("pages",wiki);
    }
    try {
      var metadata=metadata(chunk);rows.get(4).put("available",true);var questions=json.createArrayNode();
      for(var question:metadata.path("generated_questions")) {
        String source=sourceId(chunkId,question.path("id").asText());
        var item=json.createObjectNode().put("id",question.path("id").asText()).put("question",question.path("question").asText()).put("source_id",source).put("current",current(metadata,question,chunk.path("content_revision").asLong()));
        for(int i=0;i<2;i++) {String key=i==0?"vector_indexed":"keyword_indexed";Boolean indexed=rows.get(i).path("enabled").asBoolean()?KnowledgeIndexStates.sourceIndexed(rows.get(i),source):null;if(indexed==null)item.putNull(key);else item.put(key,indexed);}
        questions.add(item);
      }
      if(!questions.isEmpty())rows.get(4).set("questions",questions);
    } catch(Exception ignored) {}
    for(var row:rows)KnowledgeIndexStates.chunk(row,chunk,refreshing);
    if(rows.get(4).path("status").asText().equals("completed") && (rows.get(0).path("enabled").asBoolean() && !rows.get(0).path("available").asBoolean() || rows.get(1).path("enabled").asBoolean() && !rows.get(1).path("available").asBoolean()))rows.get(4).put("status","unknown").put("reason","statistics_unavailable");
    chunk.remove("__index_raw_metadata");
    out.set("indexes",json.valueToTree(rows));return out;
  }

  private List<ObjectNode> indexes(JsonNode knowledge, JsonNode kb, boolean chunk) {
    JsonNode metadata=knowledge.path("metadata");if(!metadata.isNull()&&!metadata.isMissingNode()&&!metadata.isObject())throw new IllegalArgumentException("Invalid document metadata");
    var effective=process.resolve(kb,metadata.path("process_overrides"));var indexing=kb.path("indexing_strategy");
    boolean[] enabled={indexing.path("vector_enabled").asBoolean(),indexing.path("keyword_enabled").asBoolean(),effective.path("graph_enabled").asBoolean(),indexing.path("wiki_enabled").asBoolean(),effective.path("question_generation_config").path("enabled").asBoolean()&&(indexing.path("vector_enabled").asBoolean()||indexing.path("keyword_enabled").asBoolean())};
    List<ObjectNode> out=new ArrayList<>();int i=0;
    for(String kind:List.of("vector","keywords","graph","wiki","questions")) {var row=json.createObjectNode().put("kind",kind).put("enabled",enabled[i++]);if(chunk)row.put("available",false);else row.putNull("count");out.add(row);}return out;
  }

  private KnowledgeIndexReadRepository.Counts count(Map<String,List<String>> stores,String kind,String kb,String doc) {
    if(stores==null||stores.getOrDefault(kind,List.of()).isEmpty())return null;
    KnowledgeIndexReadRepository.Counts out=null;
    for(String store:stores.get(kind)) {var count=repository.counts(store,kb,doc,kind);out=out==null?count:new KnowledgeIndexReadRepository.Counts(Math.min(out.chunks(),count.chunks()),Math.min(out.questions(),count.questions()));}
    return out;
  }
  private List<ObjectNode> records(Map<String,List<String>> stores,String kind,String kb,String doc,String chunk) {
    if(stores.getOrDefault(kind,List.of()).isEmpty())return null;
    List<ObjectNode> out=new ArrayList<>();for(String store:stores.get(kind))out.addAll(repository.records(store,kb,doc,chunk,kind));
    out.sort(Comparator.comparing((ObjectNode r)->r.path("store_type").asText()).thenComparing(r->r.path("source_id").asText()));return out;
  }
  private void model(ObjectNode out,long owner,String id) {if(id.isEmpty())return;out.put("model_id",id);String name=attempt(()->repository.modelName(owner,id));if(name!=null&&!name.isEmpty())out.put("model_name",name);}
  private static void scope(JsonNode knowledge,KnowledgeBaseReadAuthorization.ReadAccess grant) {
    if(grant.effectiveTenantId()==0 || knowledge.path("tenant_id").asLong()!=grant.effectiveTenantId() || grant.knowledgeBase().path("tenant_id").asLong()!=grant.effectiveTenantId() || !knowledge.path("knowledge_base_id").asText().equals(grant.knowledgeBaseId()))throw new IllegalArgumentException("Invalid document index scope");
  }
  private static JsonNode metadata(JsonNode chunk) throws Exception {String raw=chunk.has("__index_raw_metadata")?chunk.path("__index_raw_metadata").asText():chunk.path("metadata").isMissingNode()?"null":chunk.path("metadata").toString();return RerankMetadata.document(raw.isEmpty()?"null":raw);}
  private static boolean current(JsonNode metadata,JsonNode question,long revision) {return (question.hasNonNull("content_revision")?question.path("content_revision").asLong():metadata.path("generated_questions_revision").asLong())==revision;}
  static String sourceId(String chunk,String question) throws Exception {
    String candidate=chunk+"-"+question;if(candidate.getBytes(StandardCharsets.UTF_8).length<=64)return candidate;
    byte[] hash=MessageDigest.getInstance("SHA-256").digest(question.getBytes(StandardCharsets.UTF_8));return chunk+"-q"+HexFormat.of().formatHex(Arrays.copyOf(hash,12));
  }
  private static <T> T attempt(java.util.function.Supplier<T> operation) {try{return operation.get();}catch(RuntimeException ex){return null;}}
}
