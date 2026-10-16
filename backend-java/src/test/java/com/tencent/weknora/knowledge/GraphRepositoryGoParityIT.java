package com.tencent.weknora.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.CallContext;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;

/** Actual Go repository golden over live Neo4j: ordinary/alias/KB search and document deletion. */
class GraphRepositoryGoParityIT {
  final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS);Driver driver;Neo4jGraphRepository graph;String kb,doc,peer;JsonNode oracle;
  @BeforeEach void connect()throws Exception{driver=GraphDatabase.driver(Objects.requireNonNull(System.getProperty("weknora.graph.test.uri"),"Explicit isolated real Neo4j required"),AuthTokens.none());driver.verifyConnectivity();graph=new Neo4jGraphRepository(driver,json);try(var s=driver.session()){assertFalse(s.run("RETURN apoc.version()").single().get(0).asString().isEmpty());}oracle=json.readTree(Objects.requireNonNull(getClass().getResourceAsStream("/go-graph-repository.json")));kb=oracle.path("namespace").path("knowledge_base").asText();doc=oracle.path("namespace").path("knowledge").asText();peer=oracle.path("foreign_namespace").path("knowledge").asText();cleanup();}
  @AfterEach void close(){try{if(driver!=null)cleanup();}finally{if(driver!=null)driver.close();}}
  void cleanup(){if(doc==null||peer==null)return;try(var s=driver.session()){s.run("MATCH(n) WHERE n.kg IN $docs DETACH DELETE n",Map.of("docs",List.of(doc,peer))).consume();}}
  void seed(String id,boolean privateGraph,boolean emptyOwner)throws Exception{String target=privateGraph?"Private":"Release",chunk=privateGraph?"private-chunk":"c1";var nodes=List.of(new GraphExtractionText.Node("Alice",emptyOwner?List.of():List.of(privateGraph?"Foreign":"Owner"),List.of(chunk)),new GraphExtractionText.Node(target,List.of(),List.of(chunk)));try(var call=CallContext.timeout(Duration.ofSeconds(20))){graph.add(kb,id,new GraphExtractionText.Graph(nodes,List.of(new GraphExtractionText.Relation("Alice",target,privateGraph?"private":"owns"))),call,()->{});}}
  ObjectNode projection(GraphExtractionText.Graph data){var out=json.createObjectNode();if(!data.nodes().isEmpty()){var nodes=out.putArray("node");for(var n:data.nodes()){var item=nodes.addObject();if(!n.name().isEmpty())item.put("name",n.name());if(!n.chunks().isEmpty())item.set("chunks",json.valueToTree(n.chunks()));if(!n.attributes().isEmpty())item.set("attributes",json.valueToTree(n.attributes()));}}if(!data.relations().isEmpty()){var relations=out.putArray("relation");for(var r:data.relations()){var item=relations.addObject();if(!r.source().isEmpty())item.put("node1",r.source());if(!r.target().isEmpty())item.put("node2",r.target());if(!r.type().isEmpty())item.put("type",r.type());}}return out;}
  ObjectNode search(String base,String knowledge,List<String> names){try(var call=CallContext.timeout(Duration.ofSeconds(20))){return projection(graph.search(base,knowledge,names,call,()->{}));}}
  void remove(String id){try(var call=CallContext.timeout(Duration.ofSeconds(20))){graph.delete(kb,id,call,()->{});}}
  @Test void actualGoSearchAndDeleteRetainExactDocumentBindingAndForeignGraph()throws Exception{
    seed(doc,false,false);assertEquals(oracle.path("before"),graph.counts(kb,doc));assertEquals(oracle.path("search"),search(kb,doc,List.of("Alice")));
    seed(peer,true,false);assertEquals(oracle.path("collision_search"),search(kb,doc,List.of("Alice")));assertEquals(oracle.path("kbwide_search"),search(kb,"",List.of("Alice")));
    remove(doc);assertEquals(oracle.path("after"),graph.counts(kb,doc));assertEquals(oracle.path("foreign_after"),graph.counts(kb,peer));assertEquals(oracle.path("empty_search"),search(kb,doc,List.of()));assertEquals(oracle.path("wrong_namespace_search"),search(kb+"-wrong",doc,List.of("Alice")));
    seed(doc,false,true);try(var session=driver.session()){session.run("MATCH(a {kg:$own,name:'Alice'}),(b {kg:$peer,name:'Private'}) MERGE(a)-[:external]->(b)",Map.of("own",doc,"peer",peer)).consume();}remove(doc);assertFalse(oracle.path("cross_delete_error").asBoolean());assertEquals(oracle.path("cross_after"),graph.counts(kb,doc));assertEquals(oracle.path("peer_cross_after"),graph.counts(kb,peer));
  }
}
