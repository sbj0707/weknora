package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.tencent.weknora.retrieval.IndexWriter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.*;
import org.junit.jupiter.api.*;

/** Actual Go Asynq/PostgreSQL worker, full production schema, halfvec and one pooled connection. */
class KnowledgeReparseWorkerPostgresGoParityIT extends KnowledgeReparseWorkerGoParityTest {
  private final KnowledgeReparsePostgresGoParityIT postgres=new KnowledgeReparsePostgresGoParityIT();
  @Override @TestFactory Stream<DynamicTest> folders()throws Exception{
    var cases=json.readTree(Files.readAllBytes(Path.of(Objects.requireNonNull(System.getProperty("weknora.reparse.pg.fixture")))));
    try(var in=getClass().getResourceAsStream("/go-knowledge-reparse-worker.json")){
      assertThat(in).isNotNull();assertThat(cases.size()).isEqualTo(json.readTree(in).size());
    }
    var sequence=new AtomicInteger();
    return StreamSupport.stream(cases.spliterator(),false).map(row->DynamicTest.dynamicTest("postgres/"+row.path("name").asText(),()->compare(row,sequence.incrementAndGet())));
  }
  @Override DocumentProcessingCompatibilityTest database(int no)throws Exception{postgres.temp=temp;return postgres.database(no);}
  @Override void migratePending(DocumentProcessingCompatibilityTest f){}
  @Override IndexWriter writer(DocumentProcessingCompatibilityTest f){return postgres.writer(f);}
  @Override List<Map<String,Object>> indexRows(DocumentProcessingCompatibilityTest f){return postgres.indexRows(f);}
  @Override void executeSeed(DocumentProcessingCompatibilityTest f,String sql,Object[] args){postgres.executeSeed(f,sql,args);}
  @Override void release(DocumentProcessingCompatibilityTest f){postgres.release(f);}
}
