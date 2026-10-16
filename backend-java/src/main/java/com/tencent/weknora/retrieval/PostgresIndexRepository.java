package com.tencent.weknora.retrieval;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Native embeddings/halfvec and ParadeDB adapter over the existing Go schema. */
public final class PostgresIndexRepository implements IndexWriter, IndexCopier {
  private final DataSource source;

  public PostgresIndexRepository(DataSource source) { this.source = Objects.requireNonNull(source); }

  /** Schema changes remain the versioned database migration's responsibility. */
  public void initialize() throws SQLException {
    try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
      s.executeQuery("SELECT source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,embedding,is_enabled FROM embeddings LIMIT 0").close();
    }
  }

  @Override public void prepare(Set<Integer> dimensions) {}

  private static void transaction(Connection c) throws SQLException {
    if (c.getAutoCommit()) throw new SQLException("Index mutations require the caller's fenced transaction");
  }

  private static String vector(float[] values) {
    StringJoiner text = new StringJoiner(",", "[", "]");
    if (values != null) for (float value : values) text.add(Float.toString(value));
    return text.toString();
  }

  private record Key(String source, int type) {}

  @Override public void batchSave(Connection c, List<IndexEntry> entries, Map<String,float[]> vectors) throws SQLException {
    transaction(c);
    if (entries.isEmpty()) return;
    Map<Key,IndexEntry> last = new LinkedHashMap<>();
    for (IndexEntry e : entries) last.put(new Key(e.sourceId(),e.sourceType()),e);
    String row = "(CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?,?,?,?,?,?,?,?::halfvec,?)";
    String sql = "INSERT INTO embeddings(created_at,updated_at,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,embedding,is_enabled) VALUES "
        + String.join(",",Collections.nCopies(last.size(),row))
        + " ON CONFLICT(source_id,source_type) DO UPDATE SET chunk_id=excluded.chunk_id,knowledge_id=excluded.knowledge_id,knowledge_base_id=excluded.knowledge_base_id,tag_id=excluded.tag_id,content=excluded.content,dimension=excluded.dimension,embedding=excluded.embedding,is_enabled=excluded.is_enabled,updated_at=excluded.updated_at";
    List<Object> args = new ArrayList<>();
    for (IndexEntry e : last.values()) {
      float[] values = vectors == null ? null : vectors.get(e.sourceId());
      args.addAll(Arrays.asList(e.sourceId(),e.sourceType(),e.chunkId(),e.knowledgeId(),e.knowledgeBaseId(),e.tagId(),IndexText.clean(e.content()),values == null ? 0 : values.length,vector(values),e.enabled()));
    }
    SQLiteIndexRepository.execute(c,sql,args);
  }

  @Override public List<String> sourceIdsByKnowledge(Connection c,List<String> ids) throws SQLException {
    if(ids.isEmpty()) return List.of();
    List<String> result=new ArrayList<>();
    try(PreparedStatement p=c.prepareStatement("SELECT source_id FROM embeddings WHERE knowledge_id IN ("+SQLiteIndexRepository.placeholders(ids.size())+")")) {
      SQLiteIndexRepository.bind(p,ids);
      try(ResultSet r=p.executeQuery()){while(r.next()) result.add(r.getString(1));}
    }
    return result;
  }

  private static void delete(Connection c,String column,List<String> ids) throws SQLException {
    transaction(c);
    if(!ids.isEmpty()) SQLiteIndexRepository.execute(c,"DELETE FROM embeddings WHERE "+column+" IN ("+SQLiteIndexRepository.placeholders(ids.size())+")",ids);
  }
  @Override public void deleteByKnowledge(Connection c,List<String> ids) throws SQLException {delete(c,"knowledge_id",ids);}
  @Override public void deleteByChunk(Connection c,List<String> ids) throws SQLException {delete(c,"chunk_id",ids);}
  @Override public void deleteBySource(Connection c,List<String> ids) throws SQLException {delete(c,"source_id",ids);}

  public void updateEnabled(Connection c,Map<String,Boolean> values) throws SQLException {
    transaction(c);
    for(var e:values.entrySet()) SQLiteIndexRepository.execute(c,"UPDATE embeddings SET is_enabled=?,updated_at=CURRENT_TIMESTAMP WHERE chunk_id=?",List.of(e.getValue(),e.getKey()));
  }
  public void updateTags(Connection c,Map<String,String> values) throws SQLException {
    transaction(c);
    for(var e:values.entrySet()) SQLiteIndexRepository.execute(c,"UPDATE embeddings SET tag_id=?,updated_at=CURRENT_TIMESTAMP WHERE chunk_id=?",List.of(e.getValue(),e.getKey()));
  }
  public void move(Connection c,String sourceKb,String targetKb,String knowledgeId) throws SQLException {
    transaction(c);
    SQLiteIndexRepository.execute(c,"UPDATE embeddings SET knowledge_base_id=?,tag_id='',updated_at=? WHERE knowledge_base_id=? AND knowledge_id=?",List.of(targetKb,Timestamp.from(java.time.Instant.now()),sourceKb,knowledgeId));
  }

  public void copy(Connection c,String sourceKb,Map<String,String> documents,Map<String,String> chunks,String targetKb) throws SQLException {
    transaction(c);
    copyPages(c,sourceKb,documents,chunks,targetKb);
  }

  /** A page is one atomic INSERT; a later failed page retains earlier committed pages. */
  @Override public void copyIndices(String sourceKb,Map<String,String> documents,Map<String,String> chunks,String targetKb,int dimension,String knowledgeType)throws SQLException {
    if(chunks.isEmpty())return;
    try(Connection c=source.getConnection()){
      if(!c.getAutoCommit())throw new SQLException("Index copying requires independent checkpoints");
      copyPages(c,sourceKb,documents,chunks,targetKb);
    }
  }

  private void copyPages(Connection c,String sourceKb,Map<String,String> documents,Map<String,String> chunks,String targetKb)throws SQLException {
    if(chunks.isEmpty() || documents.isEmpty()) return;
    long last=0;
    while(true) {
      String sql="SELECT id,source_id,source_type,chunk_id,knowledge_id,content,dimension,embedding::text,is_enabled FROM embeddings WHERE knowledge_base_id=? AND knowledge_id IN ("+SQLiteIndexRepository.placeholders(documents.size())+") AND id>? ORDER BY id LIMIT 500";
      List<Object> args=new ArrayList<>();args.add(sourceKb);args.addAll(documents.keySet());args.add(last);
      List<List<Object>> rows=new ArrayList<>();int count=0;
      try(PreparedStatement p=c.prepareStatement(sql)) {
        SQLiteIndexRepository.bind(p,args);
        try(ResultSet r=p.executeQuery()) {while(r.next()) {
          count++;last=r.getLong(1);
          String oldChunk=r.getString(4),chunk=chunks.get(oldChunk),document=documents.get(r.getString(5));
          if(chunk==null || document==null) continue;
          String oldSource=r.getString(2);
          String target=IndexCopier.sourceId(oldSource==null?"":oldSource,oldChunk==null?"":oldChunk,chunk);
          rows.add(Arrays.asList(target,r.getInt(3),chunk,document,targetKb,"",r.getString(6),r.getInt(7),r.getString(8),r.getBoolean(9)));
        }}
      }
      if(!rows.isEmpty()){
        String values=String.join(",",Collections.nCopies(rows.size(),"(?,?,?,?,?,?,?,?,?,?,?::halfvec,?)"));
        List<Object> argsToInsert=new ArrayList<>();Timestamp now=Timestamp.from(java.time.Instant.now());
        rows.forEach(row->{argsToInsert.add(now);argsToInsert.add(now);argsToInsert.addAll(row);});
        SQLiteIndexRepository.execute(c,"INSERT INTO embeddings(created_at,updated_at,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,embedding,is_enabled) VALUES "+values+" ON CONFLICT DO NOTHING",argsToInsert);
      }
      if(count<500) break;
    }
  }

  @Override public long estimateStorageSize(List<IndexEntry> entries,Map<String,float[]> vectors) {
    long total=0;
    for(IndexEntry e:entries) {
      float[] v=vectors==null?null:vectors.get(e.sourceId());
      total+=IndexText.clean(e.content()).getBytes(StandardCharsets.UTF_8).length+200L+(v==null?0:6L*v.length);
    }
    return total;
  }

  public List<RetrievalResult> retrieve(RetrievalQuery q) throws SQLException {
    try(Connection c=source.getConnection()) {
      List<RetrievalResult> results=new ArrayList<>();
      if(q.kind().isEmpty() || q.kind().equals("keywords")) results.add(keywords(c,q));
      if(q.kind().equals("vector") || q.kind().isEmpty() && q.embedding().length>0) results.add(vector(c,q));
      if(results.isEmpty()) throw new SQLException("invalid retriever type");
      return results;
    }
  }

  private RetrievalResult keywords(Connection c,RetrievalQuery q) throws SQLException {
    StringBuilder sql=new StringBuilder("SELECT id,content,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,paradedb.score(id) AS score FROM embeddings e WHERE content ||| ? AND (is_enabled IS NULL OR is_enabled=TRUE)");
    List<Object> args=new ArrayList<>();args.add(q.query());SQLiteIndexRepository.filter(sql,args,q,"e");
    sql.append(" ORDER BY score DESC");if(q.topK()>=0){sql.append(" LIMIT ?");args.add(q.topK());}
    return hits(c,sql.toString(),args,"keywords",1);
  }

  private RetrievalResult vector(Connection c,RetrievalQuery q) throws SQLException {
    int dimension=q.embedding().length;
    int expanded=Math.max(q.topK(),Math.min(200,Math.max(100,q.topK()*2)));
    // Reuse one SQL parameter in SELECT and ORDER BY. Two separately bound equal
    // vectors prevent PostgreSQL from matching the HNSW ordering expression.
    String cast="query.v";
    StringBuilder sql=new StringBuilder("SELECT id,content,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,(1-distance) AS score FROM (SELECT id,content,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,embedding::halfvec("+dimension+") <=> "+cast+" AS distance FROM embeddings e CROSS JOIN (SELECT ?::halfvec("+dimension+") AS v) query WHERE dimension=? AND (is_enabled IS NULL OR is_enabled=TRUE)");
    List<Object> args=new ArrayList<>();args.add(vector(q.embedding()));args.add(dimension);SQLiteIndexRepository.filter(sql,args,q,"e");
    sql.append(" ORDER BY embedding::halfvec(").append(dimension).append(") <=> ").append(cast).append(" LIMIT ?) candidates WHERE distance<=? ORDER BY distance ASC LIMIT ?");
    args.add(expanded);args.add(1-q.threshold());args.add(q.topK());
    c.setAutoCommit(false);
    try {
      try(Statement s=c.createStatement()) {
        s.execute("SET LOCAL hnsw.ef_search="+Math.max(40,expanded));
        s.execute("SET LOCAL hnsw.iterative_scan=strict_order");
      }
      RetrievalResult result=hits(c,sql.toString(),args,"vector",0);c.commit();return result;
    } catch(SQLException e) {
      c.rollback();
      if(e.getMessage()!=null && (e.getMessage().contains("hnsw.ef_search") || e.getMessage().contains("hnsw.iterative_scan"))) {
        c.setAutoCommit(true);return hits(c,sql.toString(),args,"vector",0);
      }
      throw e;
    } finally {c.setAutoCommit(true);}
  }

  private static RetrievalResult hits(Connection c,String sql,List<?> args,String kind,int match) throws SQLException {
    List<RetrievalHit> rows=new ArrayList<>();
    try(PreparedStatement p=c.prepareStatement(sql)) {
      SQLiteIndexRepository.bind(p,args);
      try(ResultSet r=p.executeQuery()) {while(r.next()) {
        // ParadeDB score is real/float4. Widen its actual float32 value like pgx,
        // rather than parsing the server's shortened textual decimal as float64.
        double score=match==1?(double)r.getFloat("score"):r.getDouble("score");
        rows.add(new RetrievalHit(Long.toString(r.getLong("id")),zero(r.getString("content")),zero(r.getString("source_id")),r.getInt("source_type"),zero(r.getString("chunk_id")),zero(r.getString("knowledge_id")),zero(r.getString("knowledge_base_id")),zero(r.getString("tag_id")),score,match));
      }}
    }
    return new RetrievalResult(rows,"postgres",kind);
  }
  private static String zero(String value) {return value==null?"":value;}
}
