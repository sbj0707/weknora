package com.tencent.weknora.vectorstore;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.security.GoSecretCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Typed production Go Scanner, strict credential and real SQL owner/tombstone replay. */
class VectorStoreMetadataGoParityTest {
  final ObjectMapper json=new ObjectMapper();
  @TestFactory Stream<DynamicTest> actualGoPersistedConfiguration()throws Exception{JsonNode cases;try(var in=getClass().getResourceAsStream("/go-vector-store-metadata.json")){cases=json.readTree(in);}assertEquals(69,cases.size());return StreamSupport.stream(cases.spliterator(),false).map(c->DynamicTest.dynamicTest(c.path("name").asText(),()->{
    var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);try{var jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE vector_stores(id TEXT PRIMARY KEY,tenant_id INTEGER,engine_type TEXT,connection_config BLOB,index_config BLOB,deleted_at TEXT)");Object raw=switch(c.path("storage").asText()){case "null"->null;case "text"->c.path("raw").asText();default->c.path("raw").asText().getBytes(StandardCharsets.UTF_8);};
      for(String side:List.of("connection","index")){jdbc.update("DELETE FROM vector_stores");jdbc.update("INSERT INTO vector_stores VALUES('store',1,'elasticsearch',?,?,NULL)",side.equals("connection")?raw:"{}".getBytes(StandardCharsets.UTF_8),side.equals("index")?raw:"{}".getBytes(StandardCharsets.UTF_8));var metadata=new VectorStoreMetadata(jdbc,json,new GoSecretCodec(c.path("key").asText()));assertNull(metadata.get(2,"store"));if(c.path(side+"_error").asBoolean()){assertThrows(RuntimeException.class,()->metadata.get(1,"store"));assertThrows(RuntimeException.class,()->metadata.owned(1,"store"));}else{var actual=metadata.get(1,"store");assertNotNull(actual);assertEquals(numbers(c.path(side)),numbers(side.equals("connection")?actual.connection():actual.index()));assertTrue(metadata.owned(1,"store"));assertFalse(actual.toString().contains("synthetic-secret"));}jdbc.update("UPDATE vector_stores SET deleted_at='2026-01-01'");assertNull(metadata.get(1,"store"));assertFalse(metadata.owned(1,"store"));}
    }finally{source.destroy();}
  }));}
  JsonNode numbers(JsonNode node){if(node.isIntegralNumber())return com.fasterxml.jackson.databind.node.BigIntegerNode.valueOf(node.bigIntegerValue());if(node.isObject()){var out=json.createObjectNode();node.fields().forEachRemaining(e->out.set(e.getKey(),numbers(e.getValue())));return out;}return node;}
}
