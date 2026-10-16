package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

/** Actual owned ES8 physical adapter replay; explicit IT requires Go fixture and owned engine URL. */
class Elasticsearch8GoParityIT {
  final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> actualGoPhysicalEngine()throws Exception {
    String address=Objects.requireNonNull(System.getProperty("weknora.es.url"));if(!address.matches("http://127\\.0\\.0\\.1:[0-9]+"))throw new IllegalArgumentException("Owned loopback engine required");
    var cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.es.fixture")))));assertEquals(2,cases.size());return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("mapping").asText(),()->replay(c,address)));
  }
  void replay(JsonNode oracle,String address)throws Exception{
    String index="weknora-native-"+UUID.randomUUID().toString();var environment=new MockEnvironment().withProperty("SSRF_WHITELIST","127.0.0.1");
    try(var http=new SafeModelHttp(new ModelEgressPolicy(null,json,environment));var call=new CallContext()){
      var create=json.createObjectNode();create.putObject("settings").put("number_of_shards",1).put("number_of_replicas",0);var properties=create.putObject("mappings").putObject("properties");properties.putObject("content").put("type","text");properties.putObject("embedding").put("type","dense_vector").put("dims",2).put("index",true).put("similarity","cosine");for(String field:List.of("chunk_id","source_id","knowledge_id","knowledge_base_id","tag_id")){var p=properties.putObject(field).put("type",oracle.path("mapping").asText());if(oracle.path("mapping").asText().equals("text"))p.putObject("fields").putObject("keyword").put("type","keyword");}
      try(var response=http.exchange(call,address+"/"+index,"PUT",Map.of("Content-Type","application/json"),json.writeValueAsBytes(create),java.time.Duration.ofSeconds(30))){assertEquals(200,response.status(),response.text(100000));}
      try{
        var engine=new Elasticsearch8Store(new VectorStoreMetadata.Store("own-store",1,"elasticsearch",json.createObjectNode().put("addr",address),json.createObjectNode().put("index_name",index)),http,json,call);
        var entries=new ArrayList<IndexEntry>();for(var e:oracle.path("entries"))entries.add(new IndexEntry(e.path("SourceID").asText(),e.path("SourceType").asInt(),e.path("ChunkID").asText(),e.path("KnowledgeID").asText(),e.path("KnowledgeBaseID").asText(),e.path("TagID").asText(),e.path("Content").asText(),e.path("IsEnabled").asBoolean()));var vectors=new HashMap<String,float[]>();oracle.path("vectors").fields().forEachRemaining(e->{float[] v=new float[e.getValue().size()];for(int i=0;i<v.length;i++)v[i]=(float)e.getValue().get(i).asDouble();vectors.put(e.getKey(),v);});engine.batchSave(entries,vectors,call);engine.request("POST","/"+index+"/_refresh",null,call);
        int scenarios=0;for(var sample:oracle.path("queries")){var q=sample.path("query");var query=new RetrievalQuery(q.path("Query").asText(),floats(q.path("Embedding")),strings(q.path("KnowledgeBaseIDs")),strings(q.path("KnowledgeIDs")),strings(q.path("TagIDs")),q.path("TopK").asInt(),q.path("Threshold").asDouble(),q.path("RetrieverType").asText());var actual=engine.retrieve(query,call);var expected=sample.path("results");assertEquals(expected.size(),actual.size());for(int b=0;b<actual.size();b++){var block=actual.get(b);assertEquals(expected.get(b).path("RetrieverEngineType").asText(),block.engine());assertEquals(expected.get(b).path("RetrieverType").asText(),block.kind());var rows=expected.get(b).path("Results");assertEquals(rows.isNull()?0:rows.size(),block.results().size());for(int r=0;r<block.results().size();r++){var hit=block.results().get(r);var wanted=rows.get(r);assertFalse(hit.id().isEmpty());assertEquals(wanted.path("Content").asText(),hit.content());assertEquals(wanted.path("SourceID").asText(),hit.sourceId());assertEquals(wanted.path("SourceType").asInt(),hit.sourceType());assertEquals(wanted.path("ChunkID").asText(),hit.chunkId());assertEquals(wanted.path("KnowledgeID").asText(),hit.knowledgeId());assertEquals(wanted.path("KnowledgeBaseID").asText(),hit.knowledgeBaseId());assertEquals(wanted.path("TagID").asText(),hit.tagId());assertEquals(wanted.path("MatchType").asInt(),hit.matchType());assertEquals(Double.doubleToRawLongBits(wanted.path("Score").asDouble()),Double.doubleToRawLongBits(hit.score()),"actual ES score raw bits");}}scenarios++;}assertEquals(12,scenarios);
        assertEquals(oracle.path("delete_counts").get(0).asLong(),engine.request("GET","/"+index+"/_count",null,call).path("count").asLong());int i=1;for(var deletion:List.of(Map.entry("chunk_id","a"),Map.entry("source_id","b"),Map.entry("knowledge_id","doc-a"))){engine.delete(deletion.getKey(),List.of(deletion.getValue()),call);engine.request("POST","/"+index+"/_refresh",null,call);assertEquals(oracle.path("delete_counts").get(i++).asLong(),engine.request("GET","/"+index+"/_count",null,call).path("count").asLong());}
      }finally{try(var response=http.exchange(call,address+"/"+index,"DELETE",Map.of(),new byte[0],java.time.Duration.ofSeconds(30))){assertEquals(200,response.status(),response.text(100000));}}
    }
  }
  static List<String> strings(JsonNode node){var out=new ArrayList<String>();node.forEach(n->out.add(n.asText()));return out;}
  static float[] floats(JsonNode node){float[] out=new float[node.size()];for(int i=0;i<out.length;i++)out[i]=(float)node.get(i).asDouble();return out;}
}
