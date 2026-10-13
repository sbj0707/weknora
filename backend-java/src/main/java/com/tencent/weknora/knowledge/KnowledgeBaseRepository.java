package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KnowledgeBaseRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KnowledgeBaseCodec codec;
    private final String retrieveDriver;
    public KnowledgeBaseRepository(JdbcTemplate jdbc, ObjectMapper json, @Value("${weknora.retrieve-driver:postgres}") String retrieveDriver) throws IOException {
        this.jdbc = jdbc; this.json = json; this.retrieveDriver = retrieveDriver;
        this.codec=new KnowledgeBaseCodec(json);
    }
    public List<ObjectNode> list(long tenant, String user) {
        var result = jdbc.queryForList("SELECT * FROM knowledge_bases WHERE tenant_id=? AND is_temporary=? AND deleted_at IS NULL ORDER BY created_at DESC", tenant, false)
            .stream().map(this::project).toList();
        var pins = jdbc.queryForList("SELECT kb_id,pinned_at FROM user_kb_pins WHERE tenant_id=? AND user_id=?", tenant, user);
        for (var kb: result) {
            enrichCounts(kb);
            var creators = jdbc.queryForList("SELECT username,email FROM users WHERE id=? AND deleted_at IS NULL", kb.path("creator_id").asText());
            if (!creators.isEmpty()) {
                var c = creators.getFirst(); String name = Objects.toString(c.get("username"), "");
                if (name.isEmpty()) name=Objects.toString(c.get("email"), "");
                if (!name.isEmpty()) kb.put("creator_name", name);
            }
            kb.put("share_count", count("SELECT COUNT(*) FROM kb_shares WHERE knowledge_base_id=? AND deleted_at IS NULL", kb.path("id").asText()));
            for(var pin:pins) if(kb.path("id").asText().equals(pin.get("kb_id"))) { kb.put("is_pinned", true); kb.put("pinned_at", timestamp(pin.get("pinned_at"))); }
        }
        var sorted = new ArrayList<>(result);
        sorted.sort(Comparator.<ObjectNode,Boolean>comparing(n -> n.path("is_pinned").asBoolean()).reversed()
            .thenComparing(n -> n.path("pinned_at").asText(), Comparator.reverseOrder())
            .thenComparing(n -> n.path("created_at").asText(), Comparator.reverseOrder()));
        return sorted;
    }
    public Optional<ObjectNode> get(long tenant, String id) {
        var rows=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE id=? AND tenant_id=? AND deleted_at IS NULL",id,tenant);
        if(rows.isEmpty()) return Optional.empty();
        var result=project(rows.getFirst()); enrichCounts(result); return Optional.of(result);
    }
    public ObjectNode getForRead(com.tencent.weknora.security.KnowledgeBaseReadAuthorization.ReadAccess grant,long callerTenant) {
        var result=codec.render(grant.knowledgeBase());enrichReadCounts(result);
        if(grant.ownTenant())return decorateStore(result);
        if(result.hasNonNull("vector_store_id")&&!result.path("vector_store_id").asText().isEmpty()) {
            result.remove(List.of("vector_store_id","vector_store_name","vector_store_engine_type","vector_store_source","vector_store_status"));result.put("vector_store_source","shared");result.put("vector_store_status","available");
        }else decorateStore(result);
        result.put("my_permission",grant.permission());return result;
    }
    private void enrichReadCounts(ObjectNode kb){long tenant=kb.path("tenant_id").asLong();String id=kb.path("id").asText();
        try{if(kb.path("type").asText().equals("document"))kb.put("knowledge_count",count("SELECT COUNT(*) FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL AND parse_status <> 'deleting'",tenant,id));else if(kb.path("type").asText().equals("faq"))kb.put("chunk_count",count("SELECT COUNT(*) FROM chunks WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL",tenant,id));}catch(org.springframework.dao.DataAccessException ignored){}
        try{long processing=count("SELECT COUNT(*) FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL AND parse_status IN ('pending','processing')",tenant,id);kb.put("processing_count",processing);kb.put("is_processing",processing>0);}catch(org.springframework.dao.DataAccessException ignored){}
    }
    @Transactional
    public ObjectNode togglePin(long tenant, String user, String id) {
        // The caller must enforce visibility before this write. Scope every predicate to tenant + user + KB.
        var current=jdbc.queryForList("SELECT pinned_at FROM user_kb_pins WHERE tenant_id=? AND user_id=? AND kb_id=?",tenant,user,id);
        ObjectNode kb=get(tenant,id).orElseThrow();
        if(current.isEmpty()) {
            Timestamp now=Timestamp.from(Instant.now());
            boolean sqlite=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().equals("SQLite")));
            Object bound=sqlite?java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC).format(now.toInstant()):now;
            jdbc.update("INSERT INTO user_kb_pins (tenant_id,user_id,kb_id,pinned_at) VALUES (?,?,?,?)",tenant,user,id,bound);
            kb.put("is_pinned",true); kb.put("pinned_at", timestamp(now));
        } else {
            jdbc.update("DELETE FROM user_kb_pins WHERE tenant_id=? AND user_id=? AND kb_id=?",tenant,user,id);
            kb.put("is_pinned",false); kb.putNull("pinned_at");
        }
        return kb;
    }
    ObjectNode project(Map<String,Object> row) {
        return decorateStore(codec.render(codec.fromRow(row)));
    }
    ObjectNode decorateStore(ObjectNode out) {
        out.remove(List.of("vector_store_name","vector_store_source","vector_store_status","vector_store_engine_type"));
        if(!out.has("vector_store_id")||out.path("vector_store_id").asText().isEmpty()) {
            out.remove("vector_store_id");out.put("vector_store_name","System default");out.put("vector_store_source","env");out.put("vector_store_status","available");
            for(String driver:retrieveDriver.split(",")){String engine=envEngine(driver.trim());if(engine!=null){out.put("vector_store_engine_type",engine);break;}}
        } else {
            var stores=jdbc.queryForList("SELECT name,engine_type FROM vector_stores WHERE id=? AND tenant_id=? AND deleted_at IS NULL",out.path("vector_store_id").asText(),out.path("tenant_id").asLong());
            if(stores.isEmpty()){
                String bound=out.path("vector_store_id").asText();boolean resolved=false;
                for(String driver:retrieveDriver.split(",")){driver=driver.trim();if(envEngine(driver)!=null&&bound.equals("__env_"+driver+"__")){out.put("vector_store_name",envName(driver));out.put("vector_store_engine_type",envEngine(driver));out.put("vector_store_source","env");out.put("vector_store_status","available");resolved=true;break;}}
                if(!resolved){out.put("vector_store_source","unavailable");out.put("vector_store_status","unavailable");}
            }
            else {var store=stores.getFirst();out.put("vector_store_name",store.get("name").toString());out.put("vector_store_engine_type",store.get("engine_type").toString());out.put("vector_store_source","user");out.put("vector_store_status","available");}
        }
        return out;
    }
    private static String envEngine(String driver){return switch(driver){case "postgres","sqlite","opensearch","qdrant","milvus","tencent_vectordb","weaviate","doris" -> driver;case "elasticsearch_v7","elasticsearch_v8" -> "elasticsearch";default -> null;};}
    private static String envName(String driver){return switch(driver){case "postgres" -> "PostgreSQL";case "sqlite" -> "SQLite";case "elasticsearch_v7" -> "Elasticsearch v7";case "elasticsearch_v8" -> "Elasticsearch v8";case "opensearch" -> "OpenSearch";case "qdrant" -> "Qdrant";case "milvus" -> "Milvus";case "tencent_vectordb" -> "Tencent VectorDB";case "weaviate" -> "Weaviate";case "doris" -> "Apache Doris";default -> "";};}
    private void enrichCounts(ObjectNode kb) {
        long tenant=kb.path("tenant_id").asLong();String id=kb.path("id").asText();
        if(kb.path("type").asText().equals("document")) kb.put("knowledge_count",count("SELECT COUNT(*) FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL AND parse_status <> 'deleting'",tenant,id));
        else if(kb.path("type").asText().equals("faq")) kb.put("chunk_count",count("SELECT COUNT(*) FROM chunks WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL",tenant,id));
        long processing=count("SELECT COUNT(*) FROM knowledges WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL AND parse_status IN ('pending','processing')",tenant,id);
        kb.put("processing_count",processing);kb.put("is_processing",processing>0);
    }
    private long count(String sql,Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args)); }
    private static boolean bool(Object v) { return v instanceof Boolean b?b:v!=null&&(v.toString().equals("1")||v.toString().equalsIgnoreCase("true")); }
    static String timestamp(Object v) {
        if(v instanceof Number n) return Instant.ofEpochMilli(n.longValue()).toString();
        if(v instanceof Timestamp t) return t.toInstant().toString();
        if(v instanceof OffsetDateTime t) return t.toInstant().toString();
        if(v instanceof LocalDateTime t) return t.toInstant(ZoneOffset.UTC).toString();
        if(v instanceof java.util.Date t) return t.toInstant().toString();
        String s=v.toString().replace(' ','T');
        try { return Instant.parse(s).toString(); } catch(java.time.format.DateTimeParseException e) { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toString(); }
    }
}
