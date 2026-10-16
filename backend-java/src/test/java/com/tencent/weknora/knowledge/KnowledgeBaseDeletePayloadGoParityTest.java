package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;

class KnowledgeBaseDeletePayloadGoParityTest {
  final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> actualGoTypedPayloads()throws Exception {
    JsonNode rows;try(var in=getClass().getResourceAsStream("/go-knowledge-base-delete-payload.json")){rows=json.readTree(in);}
    assertThat(rows.size()).isEqualTo(88);
    return StreamSupport.stream(rows.spliterator(),false).map(row->DynamicTest.dynamicTest(row.path("name").asText(),()->{
      String error=row.path("error").asText();
      if(!error.isEmpty()){assertThatThrownBy(()->KnowledgeBaseDeletePayload.decode(row.path("raw").asText(),json)).isInstanceOf(IOException.class).hasMessage(error);return;}
      var payload=KnowledgeBaseDeletePayload.decode(row.path("raw").asText(),json);var expected=row.path("decoded");
      assertThat(payload.tenantId()).isEqualTo(expected.path("tenant_id").asLong());
      assertThat(payload.knowledgeBaseId()).isEqualTo(expected.path("knowledge_base_id").asText());
      var sources=new ArrayList<String>();for(var id:expected.path("data_source_ids"))sources.add(id.asText(""));assertThat(payload.dataSourceIds()).isEqualTo(sources);
      assertThat(payload.vectorStoreId()).isEqualTo(expected.hasNonNull("vector_store_id")?expected.path("vector_store_id").asText():null);
      var engines=expected.path("effective_engines");
      if(engines.isNull())assertThat(payload.effectiveEngines().isNull()||payload.effectiveEngines().isMissingNode()).isTrue();
      else assertThat(payload.effectiveEngines()).isEqualTo(engines);
    }));
  }
}
