package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real Go clone preflight and one-to-one incremental plan; every rejection must be read-only. */
class KnowledgeClonePlanGoParityTest {
  @TempDir Path temp;
  final ObjectMapper json=new ObjectMapper();
  final AtomicInteger databases=new AtomicInteger();

  @TestFactory Stream<DynamicTest> actualGoClonePlan()throws Exception {
    JsonNode oracle;
    String external=System.getProperty("weknora.clone.plan.fixture","");
    if(!external.isEmpty())oracle=json.readTree(Files.readAllBytes(Path.of(external)));
    else try(var in=getClass().getResourceAsStream("/go-knowledge-clone-plan.json")){oracle=json.readTree(Objects.requireNonNull(in));}
    var tests=new ArrayList<DynamicTest>();
    for(var c:oracle.path("compatibility"))tests.add(DynamicTest.dynamicTest("compatibility/"+c.path("name").asText(),()->{
      String error="";
      try{KnowledgeMoveCompatibility.validateClone(c.get("source"),c.get("target"),c.get("tenant"));}
      catch(Exception failure){error=Objects.toString(failure.getMessage(),"");}
      assertThat(error).isEqualTo(c.path("error").asText());
    }));
    for(var c:oracle.path("plans"))tests.add(DynamicTest.dynamicTest("plan/"+c.path("name").asText(),()->plan(c,false)));
    for(var c:oracle.path("faq"))tests.add(DynamicTest.dynamicTest("faq/"+c.path("name").asText(),()->plan(c,true)));
    assertThat(tests).hasSize(219);
    return tests.stream();
  }

  void plan(JsonNode c,boolean faq)throws Exception {
    var db=database();
    for(var sql:c.path("seed_statements"))db.jdbc.execute(sql.asText());
    JsonNode before=snapshot(db);
    var repository=new KnowledgeTransferRepository(db.jdbc,json,db.manager,Clock.systemUTC());
    var planner=new KnowledgeClonePlanner(db.jdbc,repository,json);
    var scope=new KnowledgeTransferRepository.Scope(7,"clone-task","kb","other");
    List<String> add=new ArrayList<>(),remove=new ArrayList<>();String error="";
    try {
      if(faq){
        var result=planner.preflightFaq(scope,c.path("source"),c.path("target"));
        add.addAll(result.source().keySet());remove.addAll(result.target().keySet());
        Collections.sort(add);Collections.sort(remove);
      }else{
        var result=planner.plan(scope,c.path("source"),c.path("target"));
        for(var row:result.add())add.add(row.get("id").toString());
        remove.addAll(result.remove());
      }
    }catch(Exception failure){error=Objects.toString(failure.getMessage(),"");}
    assertThat(error).isEqualTo(c.path("error").asText());
    assertThat((JsonNode)json.valueToTree(add)).isEqualTo(c.path(faq?"source_chunks":"add"));
    assertThat((JsonNode)json.valueToTree(remove)).isEqualTo(c.path(faq?"target_chunks":"remove"));
    assertThat(snapshot(db)).as("no SQL mutation even when rejecting a late target/chunk/tag").isEqualTo(before);
  }

  DocumentProcessingCompatibilityTest database()throws Exception {
    var f=new DocumentProcessingCompatibilityTest();f.temp=temp;f.database(databases.incrementAndGet(),true);
    for(String table:List.of("knowledge_tag_relations","chunks","knowledges","knowledge_tags","tenants"))f.jdbc.execute("DELETE FROM "+table);
    return f;
  }
  JsonNode snapshot(DocumentProcessingCompatibilityTest db)throws Exception {
    var result=new TreeMap<String,Object>();
    for(String table:List.of("knowledges","chunks","knowledge_tags","knowledge_tag_relations","tenants","java_task_jobs"))
      result.put(table,db.jdbc.queryForList("SELECT * FROM "+table));
    return json.readTree(json.writeValueAsBytes(result));
  }
}
