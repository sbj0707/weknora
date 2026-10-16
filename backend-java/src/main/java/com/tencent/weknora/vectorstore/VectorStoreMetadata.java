package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.GoSecretCodec;
import com.tencent.weknora.security.GoStoredStructDecoder;
import com.tencent.weknora.knowledge.HybridSearchDeadline;
import com.tencent.weknora.model.client.CallContext;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Internal, owner-scoped metadata. Credentials must never be projected by public DTOs. */
public final class VectorStoreMetadata {
  public record Store(String id,long tenant,String engine,ObjectNode connection,ObjectNode index) {
    @Override public String toString(){return "VectorStore[credentials redacted]";}
  }
  private final JdbcTemplate jdbc; private final ObjectMapper json; private final GoSecretCodec secrets;private final GoStoredStructDecoder connectionDecoder,indexDecoder;
  public VectorStoreMetadata(JdbcTemplate jdbc,ObjectMapper json,GoSecretCodec secrets){this.jdbc=new JdbcTemplate(HybridSearchDeadline.source(Objects.requireNonNull(jdbc.getDataSource())));this.json=json;this.secrets=secrets;try(var in=VectorStoreMetadata.class.getResourceAsStream("/vector-store-schema.json")){var schema=json.readTree(in);connectionDecoder=new GoStoredStructDecoder(json,schema.path("connection"));indexDecoder=new GoStoredStructDecoder(json,schema.path("index"));}catch(Exception invalid){throw new IllegalStateException("Vector store schema unavailable",invalid);}}
  public boolean owned(long tenant,String id,CallContext call){try(var budget=HybridSearchDeadline.start(call,null)){return owned(tenant,id);}}
  public Store get(long tenant,String id,CallContext call){try(var budget=HybridSearchDeadline.start(call,null)){return get(tenant,id);}}
  public boolean owned(long tenant,String id){return get(tenant,id)!=null;}
  public Store get(long tenant,String id){var rows=jdbc.queryForList("SELECT * FROM vector_stores WHERE id=? AND tenant_id=? AND deleted_at IS NULL",id,tenant);if(rows.isEmpty())return null;return decodeRow(rows.getFirst());}
  Store decodeRow(Map<String,Object> row){return decodeRow(row,true);}
  Store decodeRow(Map<String,Object> row,boolean decrypt){String id=Objects.toString(row.get("id"),"");long tenant=((Number)row.get("tenant_id")).longValue();var cc=object(row.get("connection_config"),connectionDecoder);for(String key:List.of("password","api_key"))if(decrypt&&cc.hasNonNull(key))cc.put(key,secrets.decryptStrict(cc.path(key).asText()));return new Store(id,tenant,Objects.toString(row.get("engine_type"),""),cc,object(row.get("index_config"),indexDecoder));}
  private ObjectNode object(Object raw,GoStoredStructDecoder decoder){try{if(raw==null||raw instanceof String)return json.createObjectNode();String text=raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):raw.toString();JsonNode node=decoder.project(text);return node==null||node.isNull()?json.createObjectNode():(ObjectNode)node;}catch(Exception invalid){throw new IllegalStateException("Invalid persisted vector store configuration");}}
}
