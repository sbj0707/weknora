package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.retrieval.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

/** Read-only inspection of Go's existing tables. Never initializes or repairs indexes. */
@Repository
public class KnowledgeIndexReadRepository {
  public record Counts(long chunks, long questions) {}
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Environment env;
  private final KnowledgeContentReadRepository content;
  private volatile SQLiteVectorExtension extension;

  public KnowledgeIndexReadRepository(JdbcTemplate jdbc, ObjectMapper json, Environment env, KnowledgeContentReadRepository content) {
    this.jdbc = KnowledgeIndexReadDeadline.jdbc(Objects.requireNonNull(jdbc.getDataSource())); this.json = json; this.env = env; this.content = content;
  }

  public ObjectNode chunk(long owner, String kb, String doc, String id) {
    var rows = jdbc.queryForList("SELECT * FROM chunks WHERE tenant_id=? AND knowledge_base_id=? AND knowledge_id=? AND id=? AND deleted_at IS NULL", owner, kb, doc, id);
    if (rows.isEmpty()) return null;
    var out = project(rows.getFirst());
    out.put("source_content", Objects.toString(rows.getFirst().get("source_content"), ""));
    out.put("context_header", Objects.toString(rows.getFirst().get("context_header"), ""));
    return out;
  }

  public List<ObjectNode> children(long owner, String kb, String doc, String parent) {
    var rows = jdbc.queryForList("SELECT * FROM chunks WHERE tenant_id=? AND parent_chunk_id=? AND deleted_at IS NULL", owner, parent);
    return rows.stream().map(this::project).toList();
  }

  public List<ObjectNode> questionChunks(long owner, String doc) {
    return jdbc.queryForList("SELECT id,tenant_id,knowledge_base_id,knowledge_id,content_revision,metadata FROM chunks WHERE tenant_id=? AND knowledge_id=? AND deleted_at IS NULL", owner, doc).stream().map(this::project).toList();
  }

  private ObjectNode project(Map<String,Object> row) {
    var out=content.projectChunk(row);
    Object raw=row.get("metadata");
    out.put("__index_raw_metadata",raw==null?"null":raw instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):raw.toString());
    return out;
  }

  public ObjectNode knowledgeBase(long owner,String kb) {
    var rows=jdbc.queryForList("SELECT * FROM knowledge_bases WHERE tenant_id=? AND id=? AND deleted_at IS NULL",owner,kb);
    if(rows.isEmpty())throw new IllegalArgumentException("Knowledge base unavailable");
    var row=new LinkedHashMap<>(rows.getFirst());
    // These production Go SQL Scanner implementations accept BLOB ([]byte),
    // but silently keep zero values for SQLite TEXT. Keep that storage contract.
    for(String key:List.of("indexing_strategy","question_generation_config","extract_config","vlm_config","chunking_config","storage_config"))
      if(row.get(key) instanceof String)row.put(key,null);
    try{return new KnowledgeBaseCodec(json).fromRow(row);}catch(java.io.IOException ex){throw new IllegalStateException("KB codec unavailable",ex);}
  }

  public List<ObjectNode> spans(String doc) {
    var attempts = jdbc.queryForObject("SELECT COALESCE(MAX(attempt),0) FROM knowledge_processing_spans WHERE knowledge_id=?", Integer.class, doc);
    if (attempts == null || attempts <= 0) return List.of();
    List<ObjectNode> out = new ArrayList<>();
    for (var row : jdbc.queryForList("SELECT id,attempt,name,status,input,output,error_message,finished_at,updated_at FROM knowledge_processing_spans WHERE knowledge_id=? AND attempt=? ORDER BY id ASC", doc, attempts)) {
      ObjectNode span = json.createObjectNode();
      for (String key : List.of("id", "attempt")) span.put(key, ((Number)row.get(key)).longValue());
      for (String key : List.of("name", "status", "error_message")) span.put(key, Objects.toString(row.get(key), ""));
      for (String key : List.of("input", "output")) {
        JsonNode value = stored(row.get(key));
        if (!value.isNull() && !value.isObject()) throw new IllegalArgumentException("Invalid span JSON map");
        span.set(key, value);
      }
      for (String key : List.of("finished_at", "updated_at")) if (row.get(key) != null) span.put(key, KnowledgeBaseRepository.timestamp(row.get(key)));
      out.add(span);
    }
    return out;
  }

  public String modelName(long owner, String id) {
    var rows = jdbc.queryForList("SELECT name,display_name FROM models WHERE id=? AND (tenant_id=? OR is_builtin=true) AND status='active' AND deleted_at IS NULL", id, owner);
    if (rows.isEmpty()) return "";
    String display = Objects.toString(rows.getFirst().get("display_name"), "");
    return display.isEmpty() ? Objects.toString(rows.getFirst().get("name"), "") : display;
  }

  /** Resolve the storage OWNER's engines; unknown/external bindings never fall back to local data. */
  public Map<String, List<String>> stores(long owner, JsonNode kb) {
    var result = new LinkedHashMap<String, List<String>>();
    result.put("vector", new ArrayList<>()); result.put("keywords", new ArrayList<>());
    String binding = kb.path("vector_store_id").asText();
    if (!binding.isEmpty()) {
      if (binding.startsWith("__env_") && binding.endsWith("__")) {
        String driver = binding.substring(6, binding.length()-2);
        if (!Arrays.stream(env.getProperty("weknora.retrieve-driver", "postgres").split(",")).map(String::trim).anyMatch(driver::equals)) throw new IllegalArgumentException("Unavailable environment store");
        addDriver(result, driver); return result;
      }
      var rows = jdbc.queryForList("SELECT engine_type,connection_config FROM vector_stores WHERE tenant_id=? AND id=? AND deleted_at IS NULL", owner, binding);
      if (rows.isEmpty()) throw new IllegalArgumentException("Unavailable vector binding");
      String engine = Objects.toString(rows.getFirst().get("engine_type"), "");
      JsonNode config = stored(rows.getFirst().get("connection_config"));
      // A separate connection requires its own native reader; querying the main DB would lie.
      if (!engine.equals("postgres") || !config.path("use_default_connection").asBoolean()) throw new IllegalArgumentException("Native bound index reader unavailable");
      addDriver(result, engine); return result;
    }
    var tenants = jdbc.queryForList("SELECT retriever_engines FROM tenants WHERE id=? AND deleted_at IS NULL", owner);
    if (tenants.isEmpty()) throw new IllegalArgumentException("Storage owner unavailable");
    Object rawEngines=tenants.getFirst().get("retriever_engines");
    var configured = rawEngines instanceof String?NullNode.instance:stored(rawEngines);
    var engines = configured.isArray() ? configured : configured.path("engines");
    if (!engines.isMissingNode() && !engines.isNull() && !engines.isArray()) throw new IllegalArgumentException("Invalid tenant engines");
    if (engines.isArray() && !engines.isEmpty()) {
      for (var engine : engines) {
        String kind = engine.path("retriever_type").asText(), store = engine.path("retriever_engine_type").asText();
        if (!result.containsKey(kind)) continue;
        if(!Arrays.stream(env.getProperty("weknora.retrieve-driver","postgres").split(",")).map(String::trim).map(driver->driver.startsWith("elasticsearch_")?"elasticsearch":driver).anyMatch(store::equals))throw new IllegalArgumentException("Retrieve engine is not registered");
        if (!result.get(kind).contains(store)) result.get(kind).add(store);
      }
    } else for (String driver : env.getProperty("weknora.retrieve-driver", "postgres").split(",")) addDriver(result, driver.trim());
    return result;
  }

  private static void addDriver(Map<String, List<String>> out, String driver) {
    String store = driver.startsWith("elasticsearch_") ? "elasticsearch" : driver;
    if (!Set.of("postgres", "sqlite", "elasticsearch_v7", "elasticsearch_v8", "qdrant", "milvus", "weaviate", "doris", "tencent_vectordb", "opensearch").contains(driver)) return;
    for (String kind : out.keySet()) if (!(driver.equals("elasticsearch_v7") && kind.equals("vector")) && !out.get(kind).contains(store)) out.get(kind).add(store);
  }

  public Counts counts(String store, String kb, String doc, String kind) {
    return jdbc.execute((ConnectionCallback<Counts>) c -> {
      String table = table(c, store), physical = physical(c, store, kind, "e");
      String sql = "SELECT COUNT(DISTINCT CASE WHEN e.source_id=e.chunk_id THEN e.chunk_id END), COUNT(DISTINCT CASE WHEN e.source_id<>e.chunk_id THEN e.source_id END) FROM " + table + " e WHERE e.knowledge_base_id=? AND e.knowledge_id=? AND e.source_type=0 AND " + physical;
      try (var p = c.prepareStatement(sql)) { p.setQueryTimeout(KnowledgeIndexReadDeadline.seconds(8)); p.setString(1,kb); p.setString(2,doc); try (var r=p.executeQuery()) { r.next(); return new Counts(r.getLong(1), r.getLong(2)); } }
    });
  }

  public List<ObjectNode> records(String store, String kb, String doc, String chunk, String kind) {
    return jdbc.execute((ConnectionCallback<List<ObjectNode>>) c -> {
      String table = table(c, store), physical = physical(c, store, kind, "e");
      String select = store.equals("postgres") && kind.equals("vector") ? ",e.embedding::text AS vector_text" : "";
      String sql = "SELECT e.id,e.source_id,e.chunk_id,e.content,e.dimension,e.is_enabled,e.updated_at" + select + " FROM " + table + " e WHERE e.knowledge_base_id=? AND e.knowledge_id=? AND e.chunk_id=? AND e.source_type=0 AND " + physical + " ORDER BY e.source_id ASC,e.id ASC";
      List<ObjectNode> out = new ArrayList<>();
      try (var p = c.prepareStatement(sql)) {
        p.setQueryTimeout(KnowledgeIndexReadDeadline.seconds(12)); p.setString(1,kb); p.setString(2,doc); p.setString(3,chunk);
        try (var r = p.executeQuery()) {
          while (r.next()) {
            ObjectNode row = json.createObjectNode().put("id", Long.toUnsignedString(r.getLong("id"))).put("store_type",store).put("source_id",zero(r.getString("source_id"))).put("chunk_id",zero(r.getString("chunk_id"))).put("content",zero(r.getString("content"))).put("dimension",r.getInt("dimension")).put("is_enabled",r.getBoolean("is_enabled"));
            Object updated = r.getObject("updated_at"); row.put("updated_at",updated==null?"0001-01-01T00:00:00Z":KnowledgeBaseRepository.timestamp(updated));
            if (kind.equals("vector")) {
              int dim=r.getInt("dimension"); float[] values;
              if (store.equals("sqlite")) {
                try (var v=c.prepareStatement("SELECT embedding FROM vec_embeddings_"+dim+" WHERE rowid=?")) { v.setQueryTimeout(KnowledgeIndexReadDeadline.seconds(12)); v.setLong(1,r.getLong("id")); try (var vr=v.executeQuery()) { if (!vr.next()) continue; values=FloatVectors.decode(vr.getBytes(1),dim); } }
              } else {
                JsonNode parsed=stored(r.getString("vector_text"));
                if (!parsed.isArray() || parsed.size()!=dim) throw new SQLException("Invalid stored vector dimension");
                values=new float[dim]; for (int i=0;i<dim;i++) { if (!parsed.get(i).isNumber()) throw new SQLException("Invalid stored vector"); values[i]=(float)parsed.get(i).asDouble(); }
              }
              FloatVectors.validate(values, dim); ArrayNode vector=json.createArrayNode(); for(float value:values) vector.add(value); row.set("embedding", vector);
            }
            out.add(row);
          }
        }
      }
      return out;
    });
  }

  private static String table(Connection c, String store) throws SQLException {
    String database=c.getMetaData().getDatabaseProductName();
    if (store.equals("sqlite") && database.equals("SQLite")) return "lite_embeddings";
    if (store.equals("postgres") && database.equals("PostgreSQL")) return "embeddings";
    throw new SQLException("Native index reader unavailable for configured store");
  }

  private String physical(Connection c, String store, String kind, String alias) throws SQLException {
    if (!Set.of("vector", "keywords").contains(kind)) throw new SQLException("Invalid index kind");
    if (store.equals("postgres")) return kind.equals("vector") ? alias+".dimension>0 AND "+alias+".embedding IS NOT NULL" : "1=1";
    if (kind.equals("keywords")) return "EXISTS (SELECT 1 FROM lite_embeddings_fts f WHERE f.rowid="+alias+".id)";
    List<Integer> dims=new ArrayList<>();
    try (var s=c.createStatement(); var r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name GLOB 'vec_embeddings_*'")) {
      while(r.next()) { String name=r.getString(1); if(name.matches("vec_embeddings_[1-9][0-9]*")) { try { dims.add(Integer.parseInt(name.substring(15))); } catch(NumberFormatException ex) { throw new SQLException("Invalid vector dimension",ex); } } }
    }
    if(dims.isEmpty()) return "0=1";
    vectorExtension().load(c);
    List<String> clauses=new ArrayList<>();for(int dim:dims) clauses.add("("+alias+".dimension="+dim+" AND EXISTS (SELECT 1 FROM vec_embeddings_"+dim+" v WHERE v.rowid="+alias+".id))");
    return "("+String.join(" OR ",clauses)+")";
  }

  private synchronized SQLiteVectorExtension vectorExtension() throws SQLException {
    if(extension!=null)return extension;
    String path=env.getProperty("weknora.sqlite.vec.extension", env.getProperty("WEKNORA_SQLITE_VEC_EXTENSION", ""));
    try { extension=new SQLiteVectorExtension(path.isEmpty()?null:Path.of(path)); return extension; }
    catch(IllegalArgumentException ex) { throw new SQLException("SQLite vector inspection requires configured sqlite-vec v0.1.6",ex); }
  }

  /** Exact chunk citations, plus legacy/document summaries without other chunk citations. */
  public List<ObjectNode> wiki(long owner, String kb, String doc, String chunk) {
    List<ObjectNode> out=new ArrayList<>();
    for(var row:wikiRows(owner,kb,doc,chunk,false)) {
      JsonNode refs=goStrings(row.get("source_refs")), chunks=goStrings(row.get("chunk_refs"));
      boolean cited=false;
      for(var ref:chunks) if(ref.asText().equals(chunk)) cited=true;
      ObjectNode page=json.createObjectNode();
      for(String key:List.of("id","slug","title","page_type","status","content","summary")) page.put(key,Objects.toString(row.get(key),""));
      for(String key:List.of("tenant_id","version")) page.put(key,row.get(key) instanceof Number n?n.longValue():0);
      page.put("knowledge_base_id",kb);
      for(String key:List.of("source_refs","chunk_refs","aliases","in_links","out_links")) page.set(key,goStrings(row.get(key)));
      page.set("page_metadata",stored(row.get("page_metadata")));
      for(String key:List.of("created_at","updated_at")) page.put(key,row.get(key)==null?"0001-01-01T00:00:00Z":KnowledgeBaseRepository.timestamp(row.get(key)));
      page.putNull("deleted_at");
      for(String key:List.of("parent_slug","folder_id","wiki_path","last_edit_source","last_editor_id")) if(row.get(key)!=null&&!row.get(key).toString().isEmpty())page.put(key,row.get(key).toString());
      JsonNode category=goStrings(row.get("category_path")); if(!category.isEmpty()) page.set("category_path",category);
      for(String key:List.of("depth","sort_order")) if(row.get(key) instanceof Number n&&n.longValue()!=0)page.put(key,n.longValue());
      out.add(page);
    }
    return out;
  }

  /** Build overview needs only slugs/titles, never every Wiki page body. */
  public List<ObjectNode> wikiPreview(long owner,String kb,String doc) {
    return wikiRows(owner,kb,doc,null,true).stream().map(row->json.createObjectNode().put("slug",Objects.toString(row.get("slug"),"")).put("title",Objects.toString(row.get("title"),""))).toList();
  }

  private List<Map<String,Object>> wikiRows(long owner,String kb,String doc,String chunk,boolean preview) {
    boolean sqlite=Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>)c->c.getMetaData().getDatabaseProductName().equals("SQLite")));
    String prefix=doc+"|", source,cited,empty;
    List<Object> args=new ArrayList<>(List.of(owner,kb));
    if(sqlite) {
      source="EXISTS (SELECT 1 FROM json_each(source_refs) WHERE value=? OR substr(value,1,?)=?)";
      cited="EXISTS (SELECT 1 FROM json_each(chunk_refs) WHERE value=?)";
      empty="COALESCE(json_array_length(chunk_refs),0)=0";
    } else {
      source="EXISTS (SELECT 1 FROM jsonb_array_elements_text(source_refs) AS ref(value) WHERE value=? OR left(value,?)=?)";
      cited="EXISTS (SELECT 1 FROM jsonb_array_elements_text(chunk_refs) AS ref(value) WHERE value=?)";
      empty="COALESCE(jsonb_array_length(chunk_refs),0)=0";
    }
    String predicate;
    if(chunk==null)predicate=source;
    else {predicate="("+cited+" OR ("+empty+" AND "+source+"))";args.add(chunk);}
    args.add(doc);args.add(prefix.codePointCount(0,prefix.length()));args.add(prefix);
    return jdbc.queryForList("SELECT "+(preview?"slug,title":"*")+" FROM wiki_pages WHERE tenant_id=? AND knowledge_base_id=? AND deleted_at IS NULL AND "+predicate+" ORDER BY "+(preview?"slug ASC":"title ASC,slug ASC"),args.toArray());
  }

  private JsonNode goStrings(Object value) {
    if(value instanceof String)return NullNode.instance;
    return strings(value);
  }

  private JsonNode strings(Object value) {
    JsonNode array=stored(value); if(array.isNull())return array;
    if(!array.isArray())throw new IllegalArgumentException("Invalid Wiki references");
    for(var item:array)if(!item.isTextual())throw new IllegalArgumentException("Invalid Wiki reference");
    return array;
  }

  private JsonNode stored(Object value) {
    if(value==null)return NullNode.instance;
    String raw=value instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):value.toString();
    if(raw.isEmpty())return NullNode.instance;
    try { return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw); }
    catch(Exception ex) { throw new IllegalArgumentException("Invalid persisted index JSON",ex); }
  }
  private static String zero(String value) {return value==null?"":value;}
}
