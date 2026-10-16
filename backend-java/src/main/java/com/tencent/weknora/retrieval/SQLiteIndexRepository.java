package com.tencent.weknora.retrieval;

import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native Go SQLite index tables, FTS5 bigrams and sqlite-vec cosine retrieval. */
public final class SQLiteIndexRepository implements IndexWriter, IndexCopier {
    private final DataSource source;private final SQLiteVectorExtension extension;
    private final Set<Integer> dimensions=new HashSet<>();
    public SQLiteIndexRepository(DataSource source,SQLiteVectorExtension extension){this.source=Objects.requireNonNull(source);this.extension=Objects.requireNonNull(extension);}
    private Connection open()throws SQLException {Connection c=source.getConnection();try{extension.load(c);return c;}catch(Exception ex){c.close();throw ex;}}
    /** Mirrors the production constructor's metadata/FTS migration and image repair. */
    public synchronized void initialize()throws SQLException {
        try(Connection c=open();Statement s=c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS `lite_embeddings` (`id` integer PRIMARY KEY AUTOINCREMENT,`created_at` datetime,`updated_at` datetime,`source_id` text NOT NULL,`source_type` integer NOT NULL,`chunk_id` text,`knowledge_id` text,`knowledge_base_id` text,`tag_id` text,`content` text NOT NULL,`dimension` integer NOT NULL,`is_enabled` numeric DEFAULT true)");
            for(String column:List.of("chunk_id","knowledge_id","knowledge_base_id","tag_id","is_enabled"))s.execute("CREATE INDEX IF NOT EXISTS `idx_lite_embeddings_"+column+"` ON `lite_embeddings`(`"+column+"`)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS `idx_sqlite_emb_source` ON `lite_embeddings`(`source_id`,`source_type`)");
            String existing="";try(ResultSet r=s.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='lite_embeddings_fts'")){if(r.next())existing=r.getString(1);}
            if(existing.contains("content='lite_embeddings'")){s.execute("DROP TABLE lite_embeddings_fts");existing="";}
            if(existing.isEmpty()){
                s.execute("CREATE VIRTUAL TABLE IF NOT EXISTS lite_embeddings_fts USING fts5(content,source_id,chunk_id,knowledge_id,knowledge_base_id,content='',contentless_delete=1,tokenize='unicode61')");
                List<Row> rows=rows(c,"SELECT id,dimension,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,is_enabled FROM lite_embeddings",List.of());
                for(Row row:rows)insertFTS(c,row.id,row.entry);
            }
            // Existing production chunks table is required; no synthetic replacement table.
            s.execute("UPDATE lite_embeddings SET is_enabled=1 WHERE is_enabled=0 AND chunk_id IN (SELECT id FROM chunks WHERE chunk_type IN ('image_ocr','image_caption') AND is_enabled=1)");
            List<Integer> current=new ArrayList<>();try(ResultSet r=s.executeQuery("SELECT DISTINCT dimension FROM lite_embeddings WHERE dimension>0")){while(r.next())current.add(r.getInt(1));}
            for(int dimension:current)ensure(c,dimension);
        }
    }
    private static String table(int dimension){if(dimension<=0)throw new IllegalArgumentException("invalid vector dimension");return "vec_embeddings_"+dimension;}
    private synchronized void ensure(Connection c,int dimension)throws SQLException {
        if(dimensions.contains(dimension))return;
        try(Statement s=c.createStatement()){s.execute("CREATE VIRTUAL TABLE IF NOT EXISTS "+table(dimension)+" USING vec0(embedding float["+dimension+"] distance_metric=cosine)");}
        dimensions.add(dimension);
    }
    @Override public void prepare(Set<Integer> requested)throws SQLException {try(Connection c=open()){for(int dimension:requested)if(dimension>0)ensure(c,dimension);}}
    private void transaction(Connection c)throws SQLException {if(c.getAutoCommit())throw new SQLException("Index mutations require the caller's fenced transaction");extension.load(c);}
    @Override public void batchSave(Connection c,List<IndexEntry> entries,Map<String,float[]> vectors)throws SQLException {
        transaction(c);if(entries.isEmpty())return;Map<Key,IndexEntry> last=new LinkedHashMap<>();
        for(IndexEntry entry:entries)last.put(new Key(entry.sourceId(),entry.sourceType()),entry);
        for(IndexEntry entry:last.values()){
            float[] vector=vectors==null?null:vectors.get(entry.sourceId());int dimension=vector==null?0:vector.length;
            if(dimension>0){FloatVectors.validate(vector,0);synchronized(this){if(!dimensions.contains(dimension))throw new SQLException("Vector dimension table must be prepared before the chunk transaction");}}
            List<Row> previous=rows(c,"SELECT id,dimension,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,is_enabled FROM lite_embeddings WHERE source_id=? AND source_type=?",List.of(entry.sourceId(),entry.sourceType()));
            remove(c,previous);execute(c,"DELETE FROM lite_embeddings WHERE source_id=? AND source_type=?",List.of(entry.sourceId(),entry.sourceType()));
        }
        // Go deletes all matching sources before inserting the ordered, last-value batch.
        for(IndexEntry input:last.values()){
            IndexEntry entry=new IndexEntry(input.sourceId(),input.sourceType(),input.chunkId(),input.knowledgeId(),input.knowledgeBaseId(),input.tagId(),IndexText.clean(input.content()),input.enabled());
            float[] vector=vectors==null?null:vectors.get(entry.sourceId());int dimension=vector==null?0:vector.length;
            long id;
            try(PreparedStatement p=c.prepareStatement("INSERT INTO lite_embeddings(created_at,updated_at,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,is_enabled) VALUES(CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?,?,?,?,?,?,?,?) RETURNING id")){
                bind(p,Arrays.asList(entry.sourceId(),entry.sourceType(),entry.chunkId(),entry.knowledgeId(),entry.knowledgeBaseId(),entry.tagId(),entry.content(),dimension,entry.enabled()));
                try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Index insert did not return its row ID");id=r.getLong(1);}
            }
            insertFTS(c,id,entry);if(dimension>0)execute(c,"INSERT INTO "+table(dimension)+"(rowid,embedding) VALUES(?,?)",List.of(id,FloatVectors.encode(vector)));
        }
    }
    private static void insertFTS(Connection c,long id,IndexEntry entry)throws SQLException {
        execute(c,"INSERT INTO lite_embeddings_fts(rowid,content,source_id,chunk_id,knowledge_id,knowledge_base_id) VALUES(?,?,?,?,?,?)",Arrays.asList(id,IndexText.bigrams(entry.content()),entry.sourceId(),entry.chunkId(),entry.knowledgeId(),entry.knowledgeBaseId()));
    }
    private record Key(String source,int type){}
    private record Row(long id,int dimension,IndexEntry entry){}
    private static List<Row> rows(Connection c,String sql,List<?> args)throws SQLException {
        List<Row> out=new ArrayList<>();try(PreparedStatement p=c.prepareStatement(sql)){bind(p,args);try(ResultSet r=p.executeQuery()){while(r.next())out.add(new Row(r.getLong("id"),r.getInt("dimension"),new IndexEntry(r.getString("source_id"),r.getInt("source_type"),r.getString("chunk_id"),r.getString("knowledge_id"),r.getString("knowledge_base_id"),r.getString("tag_id"),r.getString("content"),r.getBoolean("is_enabled"))));}}return out;
    }
    private void remove(Connection c,List<Row> rows)throws SQLException {
        for(Row row:rows){if(row.dimension>0)execute(c,"DELETE FROM "+table(row.dimension)+" WHERE rowid=?",List.of(row.id));execute(c,"DELETE FROM lite_embeddings_fts WHERE rowid=?",List.of(row.id));}
    }
    private void delete(Connection c,String field,List<String> ids)throws SQLException {
        transaction(c);if(ids.isEmpty())return;String where=field+" IN ("+placeholders(ids.size())+")";
        remove(c,rows(c,"SELECT id,dimension,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,is_enabled FROM lite_embeddings WHERE "+where,ids));execute(c,"DELETE FROM lite_embeddings WHERE "+where,ids);
    }
    @Override public void deleteByKnowledge(Connection c,List<String> ids)throws SQLException {delete(c,"knowledge_id",ids);}
    @Override public List<String> sourceIdsByKnowledge(Connection c,List<String> ids)throws SQLException {
        if(ids.isEmpty())return List.of();
        try(PreparedStatement p=c.prepareStatement("SELECT source_id FROM lite_embeddings WHERE knowledge_id IN ("+placeholders(ids.size())+")")){
            bind(p,ids);try(ResultSet r=p.executeQuery()){List<String> sources=new ArrayList<>();while(r.next())sources.add(r.getString(1));return List.copyOf(sources);}
        }
    }
    @Override public void deleteByChunk(Connection c,List<String> ids)throws SQLException {delete(c,"chunk_id",ids);}
    @Override public void deleteBySource(Connection c,List<String> ids)throws SQLException {delete(c,"source_id",ids);}
    /** Go table-task cleanup has independent physical and metadata writes, including partial failure. */
    public void cleanupBySource(List<String> ids,java.util.function.Consumer<Connection> fence)throws SQLException {
        if(ids.isEmpty())return;
        try(Connection c=open()) {
            if(!c.getAutoCommit())throw new SQLException("Cleanup requires independent checkpoints");
            List<Row> selected=rows(c,"SELECT id,dimension,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,is_enabled FROM lite_embeddings WHERE source_id IN ("+placeholders(ids.size())+")",ids);
            for(Row row:selected){fence.accept(c);
                if(row.dimension>0)execute(c,"DELETE FROM "+table(row.dimension)+" WHERE rowid=?",List.of(row.id));
                execute(c,"DELETE FROM lite_embeddings_fts WHERE rowid=?",List.of(row.id));
            }
            fence.accept(c);execute(c,"DELETE FROM lite_embeddings WHERE source_id IN ("+placeholders(ids.size())+")",ids);
        }
    }
    public void updateEnabled(Connection c,Map<String,Boolean> values)throws SQLException {transaction(c);for(var item:values.entrySet())execute(c,"UPDATE lite_embeddings SET is_enabled=? WHERE chunk_id=?",List.of(item.getValue(),item.getKey()));}
    public void updateTags(Connection c,Map<String,String> values)throws SQLException {transaction(c);for(var item:values.entrySet())execute(c,"UPDATE lite_embeddings SET tag_id=? WHERE chunk_id=?",List.of(item.getValue(),item.getKey()));}
    /** Move metadata in place: FTS and vec0 rows retain their physical identities. */
    public void move(Connection c,String sourceKb,String targetKb,String knowledgeId)throws SQLException {
        transaction(c);
        execute(c,"UPDATE lite_embeddings SET knowledge_base_id=?,tag_id='',updated_at=? WHERE knowledge_base_id=? AND knowledge_id=?",List.of(targetKb,nowTimestamp(),sourceKb,knowledgeId));
    }
    private static String nowTimestamp(){return new java.time.format.DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm:ss")
            .appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND,0,9,true)
            .appendLiteral("+00:00").toFormatter(Locale.ROOT).withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
    }
    /** SQLite Go selects by chunk alone, retains tags and tolerates each failed physical write. */
    @Override public void copyIndices(String sourceKb,Map<String,String> documents,Map<String,String> chunks,String targetKb,int dimension,String knowledgeType)throws SQLException {
        if(chunks.isEmpty())return;
        try(Connection c=open()) {
            if(!c.getAutoCommit())throw new SQLException("Index copying requires independent checkpoints");
            List<String> ids=new ArrayList<>(chunks.keySet());
            for(int start=0;start<ids.size();start+=500){
                List<String> batch=ids.subList(start,Math.min(start+500,ids.size()));
                List<CopyRow> selected=new ArrayList<>();
                try(PreparedStatement p=c.prepareStatement("SELECT id,source_id,source_type,chunk_id,knowledge_id,tag_id,content,dimension,is_enabled FROM lite_embeddings WHERE chunk_id IN ("+placeholders(batch.size())+") ORDER BY id")){
                    bind(p,batch);try(ResultSet r=p.executeQuery()){while(r.next())selected.add(new CopyRow(r.getLong(1),zero(r.getString(2)),r.getInt(3),zero(r.getString(4)),zero(r.getString(5)),zero(r.getString(6)),zero(r.getString(7)),r.getInt(8),r.getObject(9)==null||r.getBoolean(9)));}
                }
                for(CopyRow row:selected){
                    String chunk=chunks.get(row.chunk());
                    IndexEntry entry=new IndexEntry(IndexCopier.sourceId(row.source(),row.chunk(),chunk),row.type(),chunk,documents.getOrDefault(row.document(),""),targetKb,row.tag(),row.content(),row.enabled());
                    long copied;
                    try(PreparedStatement p=c.prepareStatement("INSERT INTO lite_embeddings(created_at,updated_at,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,is_enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING id")){
                        String now=nowTimestamp();
                        bind(p,Arrays.asList(now,now,entry.sourceId(),entry.sourceType(),entry.chunkId(),entry.knowledgeId(),entry.knowledgeBaseId(),entry.tagId(),entry.content(),row.dimension(),entry.enabled()));
                        try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("Index copy did not return its row ID");copied=r.getLong(1);}
                    }catch(SQLException failure){copyFailure("metadata",failure);continue;}
                    try{insertFTS(c,copied,entry);}catch(SQLException failure){copyFailure("FTS",failure);}
                    if(row.dimension()>0){
                        boolean exists;
                        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")){p.setString(1,table(row.dimension()));try(ResultSet r=p.executeQuery()){exists=r.next();}}
                        if(exists)try{execute(c,"INSERT INTO "+table(row.dimension())+"(rowid,embedding) SELECT ?,embedding FROM "+table(row.dimension())+" WHERE rowid=?",List.of(copied,row.id()));}catch(SQLException failure){copyFailure("vector",failure);}
                    }
                }
            }
        }
    }
    private record CopyRow(long id,String source,int type,String chunk,String document,String tag,String content,int dimension,boolean enabled){}
    private static void copyFailure(String stage,SQLException failure){org.slf4j.LoggerFactory.getLogger(SQLiteIndexRepository.class).warn("Index copy {} write failed: SQL state={}, vendor code={}",stage,failure.getSQLState(),failure.getErrorCode());}
    @Override public long estimateStorageSize(List<IndexEntry> entries,Map<String,float[]> vectors){long total=0;for(IndexEntry entry:entries)total+=entry.content().getBytes(StandardCharsets.UTF_8).length+200;return total;}
    public List<RetrievalResult> retrieve(RetrievalQuery query)throws SQLException {
        if(query.embedding().length>0&&(query.kind().isEmpty()||query.kind().equals("vector")))prepare(Set.of(query.embedding().length));
        try(Connection c=open()){List<RetrievalResult> results=new ArrayList<>();
            if((query.kind().isEmpty()||query.kind().equals("keywords"))&&!query.query().isEmpty())results.add(keywords(c,query));
            if((query.kind().isEmpty()||query.kind().equals("vector"))&&query.embedding().length>0)results.add(vector(c,query));
            return results;
        }
    }
    private RetrievalResult keywords(Connection c,RetrievalQuery q)throws SQLException {
        StringBuilder sql=new StringBuilder("SELECT e.id,e.source_id,e.source_type,e.chunk_id,e.knowledge_id,e.knowledge_base_id,e.tag_id,e.content,(bm25(lite_embeddings_fts)*-1000000.0) AS score FROM lite_embeddings_fts JOIN lite_embeddings e ON e.id=lite_embeddings_fts.rowid WHERE lite_embeddings_fts MATCH ? AND (e.is_enabled IS NULL OR e.is_enabled=1)");
        List<Object> args=new ArrayList<>();args.add(IndexText.ftsQuery(q.query()));filter(sql,args,q,"e");sql.append(" ORDER BY score DESC LIMIT ?");args.add(q.topK());return hits(c,sql.toString(),args,"keywords",1);
    }
    private RetrievalResult vector(Connection c,RetrievalQuery q)throws SQLException {
        StringBuilder sql=new StringBuilder("SELECT v.rowid AS id,e.source_id,e.source_type,e.chunk_id,e.knowledge_id,e.knowledge_base_id,e.tag_id,e.content,(1-v.distance) AS score FROM "+table(q.embedding().length)+" v JOIN lite_embeddings e ON e.id=v.rowid WHERE v.embedding MATCH ? AND k=? AND v.rowid IN (SELECT filtered.id FROM lite_embeddings filtered WHERE (filtered.is_enabled IS NULL OR filtered.is_enabled=1)");
        List<Object> args=new ArrayList<>();args.add(FloatVectors.encode(q.embedding()));args.add(q.topK());filter(sql,args,q,"filtered");sql.append(") ORDER BY v.distance ASC");RetrievalResult all=hits(c,sql.toString(),args,"vector",0);
        if(q.threshold()<=0)return all;return new RetrievalResult(all.results().stream().filter(h->h.score()>=q.threshold()).toList(),"sqlite","vector");
    }
    static void filter(StringBuilder sql,List<Object> args,RetrievalQuery query,String alias){
        List<List<String>> values=List.of(query.knowledgeBaseIds(),query.knowledgeIds(),query.tagIds());List<String> names=List.of("knowledge_base_id","knowledge_id","tag_id");
        for(int i=0;i<values.size();i++)if(!values.get(i).isEmpty()){sql.append(" AND ").append(alias).append('.').append(names.get(i)).append(" IN (").append(placeholders(values.get(i).size())).append(')');args.addAll(values.get(i));}
    }
    static RetrievalResult hits(Connection c,String sql,List<?> args,String kind,int match)throws SQLException {
        List<RetrievalHit> hits=new ArrayList<>();try(PreparedStatement p=c.prepareStatement(sql)){bind(p,args);try(ResultSet r=p.executeQuery()){while(r.next())hits.add(new RetrievalHit(Long.toString(r.getLong("id")),zero(r.getString("content")),zero(r.getString("source_id")),r.getInt("source_type"),zero(r.getString("chunk_id")),zero(r.getString("knowledge_id")),zero(r.getString("knowledge_base_id")),zero(r.getString("tag_id")),r.getDouble("score"),match));}}
        return new RetrievalResult(hits,"sqlite",kind);
    }
    private static String zero(String value){return value==null?"":value;}
    static String placeholders(int n){return String.join(",",Collections.nCopies(n,"?"));}
    static void bind(PreparedStatement p,List<?> args)throws SQLException {for(int i=0;i<args.size();i++){Object value=args.get(i);if(value instanceof byte[] bytes)p.setBytes(i+1,bytes);else p.setObject(i+1,value);}}
    static void execute(Connection c,String sql,List<?> args)throws SQLException {
        try(PreparedStatement p=c.prepareStatement(sql)){bind(p,args);p.executeUpdate();}
        catch(SQLException e){
            // SQL templates contain placeholders; never log document contents or vectors.
            org.slf4j.LoggerFactory.getLogger(SQLiteIndexRepository.class).warn("Index mutation failed: statement={}, SQL state={}, vendor code={}",sql.substring(0,Math.min(sql.length(),512)),e.getSQLState(),e.getErrorCode());
            throw e;
        }
    }
}
