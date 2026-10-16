package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Actual ES8 REST adapter. Automatic document IDs and swallowed bulk item errors match current Go. */
public final class Elasticsearch8Store implements VectorStoreEngine {
  @Override public void validateKnowledgeIndexMove() {}
  private final SafeModelHttp http;private final ObjectMapper json;private final String address,index;private final Map<String,String> headers;private boolean keywordSuffix=true;private boolean productVerified;
  public Elasticsearch8Store(VectorStoreMetadata.Store store,SafeModelHttp http,ObjectMapper json,CallContext call){
    this.http=http;this.json=json;var cc=store.connection();address=cc.path("addr").asText().replaceAll("/+$","");String version=cc.path("version").asText();if(version.startsWith("7."))throw new IllegalArgumentException("Native Elasticsearch 7 is not migrated");
    index=store.index().path("index_name").asText("xwrag_default");if(index.isEmpty())throw new IllegalArgumentException("Empty Elasticsearch index");if(!index.matches("[a-z0-9_][a-z0-9_.-]*"))throw new IllegalArgumentException("Native Elasticsearch index name is unsupported");
    var h=new LinkedHashMap<String,String>();h.put("Content-Type","application/json");if(!cc.path("username").asText().isEmpty()||!cc.path("password").asText().isEmpty())h.put("Authorization","Basic "+Base64.getEncoder().encodeToString((cc.path("username").asText()+":"+cc.path("password").asText()).getBytes(StandardCharsets.UTF_8)));headers=Map.copyOf(h);
    // Go constructors log index/mapping failures and defer backend errors to retrieval.
    try{if(status("HEAD","/"+index,null,call)==404){var body=json.createObjectNode();int shards=store.index().path("number_of_shards").asInt();int replicas=store.index().has("number_of_replicas")?store.index().path("number_of_replicas").asInt():-1;if(shards>0||replicas>=0){var settings=body.putObject("settings");if(shards>0)settings.put("number_of_shards",Integer.toString(shards));if(replicas>=0)settings.put("number_of_replicas",Integer.toString(replicas));}request("PUT","/"+index,body,call);}}catch(RuntimeException unavailable){call.check();}
    try{keywordSuffix=!request("GET","/"+index+"/_mapping",null,call).path(index).path("mappings").path("properties").path("chunk_id").path("type").asText().equals("keyword");}catch(RuntimeException unavailable){call.check();}
  }
  @Override public String engine(){return "elasticsearch";}
  @Override public Set<String> capabilities(){return Set.of("vector","keywords");}
  @Override public List<RetrievalResult> retrieve(RetrievalQuery query,CallContext call){
    var body=json.createObjectNode();body.put("size",query.topK());body.putObject("_source").putArray("excludes").add("embedding");var filter=filters(query);
    if(query.kind().equals("vector")){var scriptScore=body.putObject("query").putObject("script_score");scriptScore.set("query",filter);var script=scriptScore.putObject("script");script.put("source","Math.max(cosineSimilarity(params.query_vector, 'embedding'), 0.0)");var embedding=script.putObject("params").putArray("query_vector");for(float value:query.embedding())embedding.add(value);scriptScore.put("min_score",(float)query.threshold());}
    else if(query.kind().equals("keywords")){var bool=body.putObject("query").putObject("bool");bool.putArray("filter").add(filter);bool.putArray("must").addObject().putObject("match").putObject("content").put("query",query.query());}
    else throw new IllegalArgumentException("invalid retriever type: "+query.kind());
    var data=request("POST","/"+index+"/_search",body,call);var hits=new ArrayList<RetrievalHit>();for(var h:data.path("hits").path("hits")){var s=h.path("_source");hits.add(new RetrievalHit(h.path("_id").asText(),s.path("content").asText(),s.path("source_id").asText(),s.path("source_type").asInt(),s.path("chunk_id").asText(),s.path("knowledge_id").asText(),s.path("knowledge_base_id").asText(),s.path("tag_id").asText(),h.path("_score").asDouble(),query.kind().equals("vector")?0:1));}return List.of(new RetrievalResult(hits,engine(),query.kind()));
  }
  private ObjectNode filters(RetrievalQuery query){var root=json.createObjectNode();var bool=root.putObject("bool");var must=bool.putArray("must");terms(must,"knowledge_base_id",query.knowledgeBaseIds());terms(must,"knowledge_id",query.knowledgeIds());terms(must,"tag_id",query.tagIds());bool.putArray("must_not").addObject().putObject("term").putObject("is_enabled").put("value",false);return root;}
  private void terms(ArrayNode into,String field,List<String> values){if(values.isEmpty())return;var array=into.addObject().putObject("terms").putArray(field+(keywordSuffix?".keyword":""));values.forEach(array::add);}
  @Override public void batchSave(List<IndexEntry> entries,Map<String,float[]> embeddings,CallContext call){if(entries.isEmpty())return;var text=new StringBuilder();for(var e:entries){var op=json.createObjectNode();op.putObject("create").put("_index",index);var row=json.createObjectNode().put("content",e.content()).put("source_id",e.sourceId()).put("source_type",e.sourceType()).put("chunk_id",e.chunkId()).put("knowledge_id",e.knowledgeId()).put("knowledge_base_id",e.knowledgeBaseId()).put("tag_id",e.tagId()).put("is_enabled",e.enabled()).put("is_recommended",false);var vector=embeddings.get(e.sourceId());if(vector==null)row.putNull("embedding");else{var array=row.putArray("embedding");for(float f:vector)array.add(f);}text.append(op).append('\n').append(row).append('\n');}bytes("POST","/"+index+"/_bulk",text.toString().getBytes(StandardCharsets.UTF_8),call);}
  @Override public void delete(String field,List<String> ids,CallContext call){if(ids.isEmpty())return;if(!Set.of("chunk_id","source_id","knowledge_id").contains(field))throw new IllegalArgumentException("Invalid index deletion field");var body=json.createObjectNode();var values=body.putObject("query").putObject("terms").putArray(field+(keywordSuffix?".keyword":""));ids.forEach(values::add);request("POST","/"+index+"/_delete_by_query",body,call);}
  /** Move every matching physical record, retaining IDs/vectors/content and including disabled rows. */
  public void moveKnowledgeIndices(String sourceKb,String targetKb,String knowledgeId,List<String> chunkIds,int dimension,String kind,CallContext call){
    var body=json.createObjectNode();var filter=body.putObject("query").putObject("bool").putArray("filter");
    terms(filter,"knowledge_base_id",List.of(sourceKb));terms(filter,"knowledge_id",List.of(knowledgeId));
    var script=body.putObject("script");script.put("source","ctx._source.knowledge_base_id = params.target; ctx._source.tag_id = ''");script.putObject("params").put("target",targetKb);
    byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);
    for(int attempt=0;;attempt++){
      before(call);
      SafeModelHttp.Response response;
      try{response=http.exchangeReusableWithoutRedirects(call,address+"/"+index+"/_update_by_query?refresh=true","POST",headers,payload,Duration.ZERO);}
      catch(java.util.concurrent.CancellationException cancelled){throw cancelled(true);}
      catch(ModelClientException failure){before(call);if(failure.transport()&&attempt<3)continue;if(failure instanceof SafeModelHttp.HttpExchangeException wire)throw new IllegalStateException("an error happened during the UpdateByQuery query execution: "+wire.goMessage(),wire);throw failure;}
      try(response){
        if(attempt<3&&Set.of(502,503,504).contains(response.status())){
          try{response.body().transferTo(java.io.OutputStream.nullOutputStream());}catch(java.io.IOException|ModelClientException|java.util.concurrent.CancellationException ignoredDrainError){}continue;
        }
        try{verifyProduct(response);}catch(ProductMismatch failure){throw new IllegalStateException("an error happened during the UpdateByQuery query execution: "+failure.getMessage(),failure);}
        try{byte[] first=com.tencent.weknora.chunker.GoJsonFirstValue.first(new java.io.BufferedInputStream(response.body(),512),Integer.MAX_VALUE);new Elasticsearch8MoveResponse(json).check(response.status(),first);return;}
        catch(SafeModelHttp.HttpExchangeException failed){throw new IllegalStateException(failed.goMessage(),failed);}
        catch(java.util.concurrent.CancellationException cancelled){throw cancelled(false);}
        catch(ModelClientException failure){try{call.check();}catch(java.util.concurrent.CancellationException cancelled){throw cancelled(false);}catch(ModelClientException deadline){throw new ModelClientException("context deadline exceeded",true);}throw failure;}
        catch(java.io.IOException invalid){throw new IllegalStateException(invalid.getMessage(),invalid);}
      }
    }
  }
  private static java.util.concurrent.CancellationException cancelled(boolean beforeResponse){return new java.util.concurrent.CancellationException((beforeResponse?"an error happened during the UpdateByQuery query execution: ":"")+"context canceled");}
  private static void before(CallContext call){try{call.check();}catch(java.util.concurrent.CancellationException cancelled){throw cancelled(true);}catch(ModelClientException deadline){throw new ModelClientException("an error happened during the UpdateByQuery query execution: context deadline exceeded",true);}}
  private static final class ProductMismatch extends IllegalStateException {ProductMismatch(){super("the client noticed that the server is not Elasticsearch and we do not support this unknown product");}}
  private synchronized void verifyProduct(SafeModelHttp.Response response){
    if(productVerified||response.status()<200||response.status()>=300)return;
    if(!"Elasticsearch".equals(response.headers().get("X-Elastic-Product")))throw new ProductMismatch();
    productVerified=true;
  }
  public JsonNode request(String method,String path,JsonNode body,CallContext call){return bytes(method,path,body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8),call);}
  private int status(String method,String path,JsonNode body,CallContext call){try(var response=http.exchangeReusableWithoutRedirects(call,address+path,method,headers,body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8),Duration.ofSeconds(30))){verifyProduct(response);response.bytes(32*1024*1024);return response.status();}}
  private JsonNode bytes(String method,String path,byte[] body,CallContext call){call.check();try(var response=http.exchangeReusableWithoutRedirects(call,address+path,method,headers,body,Duration.ofSeconds(30))){verifyProduct(response);byte[] data=response.bytes(32*1024*1024);if(response.status()>=300)throw new IllegalStateException("Elasticsearch request failed with status "+response.status());try{return data.length==0?json.createObjectNode():json.readTree(data);}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid Elasticsearch response");}}}
}
