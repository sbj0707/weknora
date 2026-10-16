package com.tencent.weknora.retrieval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SQLiteIndexGoParityTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode oracle()throws Exception{return JSON.readTree(Files.readString(Path.of(System.getProperty("weknora.retrieval.fixture","src/test/java/com/tencent/weknora/retrieval/go-retrieval.json"))));}
    static final class Database implements AutoCloseable {
        final Path file=Files.createTempFile("weknora-native-index-",".db");final DriverManagerDataSource ds=new DriverManagerDataSource();final SQLiteIndexRepository repo;
        Database()throws Exception{
            ds.setDriverClassName("org.sqlite.JDBC");ds.setUrl("jdbc:sqlite:"+file);
            try(Connection c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("../migrations/sqlite/000000_init.up.sql"));}
            String library=System.getProperty("weknora.sqlite.vec.extension",System.getenv("WEKNORA_SQLITE_VEC_EXTENSION"));
            repo=new SQLiteIndexRepository(ds,new SQLiteVectorExtension(library==null?null:Path.of(library)));repo.initialize();
        }
        Connection connection()throws Exception{return ds.getConnection();}
        Connection transaction()throws Exception{Connection c=connection();c.setAutoCommit(false);return c;}
        @Override public void close()throws Exception{Files.deleteIfExists(file);}
    }
    static IndexEntry entry(JsonNode e){return new IndexEntry(e.path("source_id").asText(),e.path("source_type").asInt(),e.path("chunk_id").asText(),e.path("knowledge_id").asText(),e.path("knowledge_base_id").asText(),e.path("tag_id").asText(),e.path("content").asText(),e.path("enabled").asBoolean());}
    static float[] floats(JsonNode value){float[] result=new float[value.size()];for(int i=0;i<result.length;i++)result[i]=(float)value.get(i).asDouble();return result;}
    static List<String> strings(JsonNode value){List<String> result=new ArrayList<>();for(JsonNode n:value)result.add(n.asText());return result;}
    static RetrievalQuery query(JsonNode p){return new RetrievalQuery(p.path("Query").asText(),floats(p.path("Embedding")),strings(p.path("KnowledgeBaseIDs")),strings(p.path("KnowledgeIDs")),strings(p.path("TagIDs")),p.path("TopK").asInt(),p.path("Threshold").asDouble(),p.path("RetrieverType").asText());}
    static JsonNode goResults(List<RetrievalResult> results){
        if(results.isEmpty())return NullNode.instance;ArrayNode out=JSON.createArrayNode();for(RetrievalResult result:results){ArrayNode hits=JSON.createArrayNode();for(RetrievalHit h:result.results())hits.add(JSON.createObjectNode().put("ID",h.id()).put("Content",h.content()).put("SourceID",h.sourceId()).put("SourceType",h.sourceType()).put("ChunkID",h.chunkId()).put("KnowledgeID",h.knowledgeId()).put("KnowledgeBaseID",h.knowledgeBaseId()).put("TagID",h.tagId()).put("Score",h.score()).put("MatchType",h.matchType()).put("IsEnabled",false));ObjectNode r=JSON.createObjectNode().put("RetrieverEngineType",result.engine()).put("RetrieverType",result.kind());r.set("Results",hits);r.putNull("Error");out.add(r);}return out;
    }
    static void equal(JsonNode actual,JsonNode expected,String context){
        if(actual.isNumber()&&expected.isNumber()){assertThat(actual.asDouble()).as(context).isCloseTo(expected.asDouble(),within(context.endsWith(".Score")?Math.max(1e-12,4*Math.ulp(expected.asDouble())):0));return;}
        assertThat(actual.getNodeType()).as(context).isEqualTo(expected.getNodeType());
        if(actual.isObject()){assertThat(actual.size()).as(context).isEqualTo(expected.size());expected.fields().forEachRemaining(e->equal(actual.path(e.getKey()),e.getValue(),context+"."+e.getKey()));}
        else if(actual.isArray()){assertThat(actual.size()).as(context).isEqualTo(expected.size());for(int i=0;i<actual.size();i++)equal(actual.get(i),expected.get(i),context+"["+i+"]");}
        else assertThat(actual).as(context).isEqualTo(expected);
    }
    static ArrayNode rows(Connection c)throws Exception {
        ArrayNode out=JSON.createArrayNode();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id,source_id,source_type,chunk_id,knowledge_id,knowledge_base_id,tag_id,content,dimension,is_enabled FROM lite_embeddings ORDER BY id")){
            ResultSetMetaData m=r.getMetaData();while(r.next()){ObjectNode row=JSON.createObjectNode();for(int i=1;i<=m.getColumnCount();i++)row.set(m.getColumnName(i),JSON.valueToTree(r.getObject(i)));out.add(row);}}
        return out;
    }
    static ObjectNode blobs(Connection c,SQLiteVectorExtension extension)throws Exception{
        extension.load(c);ObjectNode out=JSON.createObjectNode();List<Integer> dims=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT DISTINCT dimension FROM lite_embeddings WHERE dimension>0")){while(r.next())dims.add(r.getInt(1));}
        for(int dim:dims)try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT rowid,embedding FROM vec_embeddings_"+dim+" ORDER BY rowid")){while(r.next())out.put(Long.toString(r.getLong(1)),HexFormat.of().formatHex(r.getBytes(2)));}return out;
    }
    @Test void actualGoSchemaMutationsVectorsAnd704RetrievalQueries()throws Exception{
        JsonNode root=oracle();int mutations=0,queries=0;try(Database db=new Database()){
            try(Connection c=db.connection();PreparedStatement p=c.prepareStatement("SELECT sql FROM sqlite_master WHERE name=?")){
                for(JsonNode table:root.path("schema")){p.setString(1,table.path("name").asText());try(ResultSet r=p.executeQuery()){assertThat(r.next()).isTrue();assertThat(r.getString(1).replaceAll("\\s+","").replace("IFNOTEXISTS","")).isEqualTo(table.path("sql").asText().replaceAll("\\s+", ""));}}
            }
            for(JsonNode step:root.path("steps")){
                Map<String,float[]> vectors=new LinkedHashMap<>();step.path("embeddings").fields().forEachRemaining(e->vectors.put(e.getKey(),floats(e.getValue())));Set<Integer> dims=new HashSet<>();vectors.values().forEach(v->dims.add(v.length));db.repo.prepare(dims);
                List<IndexEntry> entries=new ArrayList<>();step.path("inputs").forEach(e->entries.add(entry(e)));List<String> ids=strings(step.path("ids"));
                try(Connection tx=db.transaction()){
                    switch(step.path("op").asText()){
                        case "save"->db.repo.batchSave(tx,entries,vectors);
                        case "delete_chunk"->db.repo.deleteByChunk(tx,ids);
                        case "delete_source"->db.repo.deleteBySource(tx,ids);
                        case "delete_knowledge"->db.repo.deleteByKnowledge(tx,ids);
                        case "disable"->db.repo.updateEnabled(tx,Map.of(ids.getFirst(),false));
                        case "tag"->db.repo.updateTags(tx,Map.of(ids.getFirst(),ids.get(1)));
                        default->throw new AssertionError("unknown operation");
                    }tx.commit();
                }
                try(Connection c=db.connection()){
                    equal(rows(c),step.path("rows"),"mutation "+mutations);
                    equal(blobs(c,new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension")))),step.path("vectors"),"vector bytes "+mutations);
                }
                for(JsonNode q:step.path("queries")){assertThat(q.has("error")).isFalse();equal(goResults(db.repo.retrieve(query(q.path("params")))),q.path("result"),"query "+mutations+":"+queries);queries++;}mutations++;
            }
        }
        assertThat(mutations).isEqualTo(11);assertThat(queries).isEqualTo(704);
    }
    @Test void actualGoPublicIndexerEmbeddingSanitizationAndFloat32Encoding()throws Exception {
        int count=0;for(JsonNode c:oracle().path("embedding_inputs")){assertThat(IndexText.embeddingInput(c.path("input").asText())).isEqualTo(c.path("output").asText());count++;}assertThat(count).isEqualTo(8);
        float[] values={0f,-0f,.1f,Float.MIN_VALUE,Float.MIN_NORMAL,Float.MAX_VALUE};byte[] encoded=FloatVectors.encode(values);float[] restored=FloatVectors.decode(encoded,values.length);for(int i=0;i<values.length;i++)assertThat(Float.floatToRawIntBits(restored[i])).isEqualTo(Float.floatToRawIntBits(values[i]));
        assertThatThrownBy(()->FloatVectors.validate(new float[]{Float.NaN},1)).hasMessageContaining("non-finite");assertThatThrownBy(()->FloatVectors.validate(new float[]{1,2},3)).hasMessageContaining("dimension mismatch");assertThatThrownBy(()->FloatVectors.decode(new byte[5],1)).hasMessage("invalid stored vector dimension");
    }
    @Test void chunksMetadataFTSAndVectorsRollbackTogetherAndErrorsNeverReportSuccess()throws Exception {
        try(Database db=new Database()){
            db.repo.prepare(Set.of(2));IndexEntry old=new IndexEntry("rollback",0,"rollback","doc","kb","","oldanswer",true);
            try(Connection c=db.transaction()){db.repo.batchSave(c,List.of(old),Map.of("rollback",new float[]{1,0}));c.commit();}
            try(Connection c=db.transaction()){
                try(Statement s=c.createStatement()){s.execute("INSERT INTO chunks(id,tenant_id,knowledge_base_id,knowledge_id,content,chunk_index,start_at,end_at) VALUES('temporary',1,'kb','doc','body',0,0,4)");s.execute("DROP TABLE lite_embeddings_fts");}
                assertThatThrownBy(()->db.repo.batchSave(c,List.of(new IndexEntry("rollback",0,"rollback","doc","kb","","replacement",true)),Map.of("rollback",new float[]{0,1}))).isInstanceOf(SQLException.class);c.rollback();
            }
            try(Connection c=db.connection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM chunks WHERE id='temporary'")){assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isZero();}
            assertThat(db.repo.retrieve(new RetrievalQuery("oldanswer",null,List.of("kb"),null,null,10,0,"keywords")).getFirst().results()).extracting(RetrievalHit::content).containsExactly("oldanswer");
            assertThat(db.repo.retrieve(new RetrievalQuery("",new float[]{1,0},List.of("kb"),null,null,1,.99,"vector")).getFirst().results()).extracting(RetrievalHit::content).containsExactly("oldanswer");
            try(Connection c=db.connection()){assertThatThrownBy(()->db.repo.deleteByKnowledge(c,List.of("doc"))).isInstanceOf(SQLException.class).hasMessageContaining("fenced transaction");}
        }
    }
    @Test void missingVersionExtensionAndUnpreparedDimensionsFailExplicitly()throws Exception {
        assertThatThrownBy(()->new SQLiteVectorExtension(Path.of("relative.dylib"))).isInstanceOf(IllegalArgumentException.class);
        try(Database db=new Database();Connection c=db.transaction()){
            IndexEntry row=new IndexEntry("a",0,"a","doc","kb","","test",true);
            assertThatThrownBy(()->db.repo.batchSave(c,List.of(row),Map.of("a",new float[]{1,0,0,0}))).isInstanceOf(SQLException.class).hasMessageContaining("prepared");c.rollback();
            assertThat(rows(c)).isEmpty();
        }
    }
    @Test void independentlyConfiguredReadersReuseTheSamePhysicalExtensionRegistration()throws Exception {
        try(Database db=new Database();Connection c=db.connection()) {
            var library=Path.of(System.getProperty("weknora.sqlite.vec.extension"));
            new SQLiteVectorExtension(library).load(c);
            new SQLiteVectorExtension(library).load(c);
            try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT vec_version()")) {
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("v0.1.6");
            }
        }
    }
    @Test void replacementsOnOnePhysicalConnectionPreserveVectorAndFtsData()throws Exception {
        try(Database db=new Database();var pool=new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl(db.ds.getUrl());pool.setMaximumPoolSize(1);pool.setConnectionTimeout(1000);
            var index=new SQLiteIndexRepository(pool,new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))));
            index.initialize();index.prepare(Set.of(3));
            for(int i=0;i<10;i++) {
                try(Connection c=pool.getConnection()) {
                    c.setAutoCommit(false);
                    index.deleteByKnowledge(c,List.of("doc"));
                    index.batchSave(c,List.of(new IndexEntry("a",0,"a","doc","kb","","hello",true),new IndexEntry("b",0,"b","doc","kb","","world",true)),Map.of("a",new float[]{1,0,0},"b",new float[]{0,1,0}));c.commit();
                }
                assertThat(index.retrieve(new RetrievalQuery("",new float[]{1,0,0},List.of("kb"),null,null,2,0,"vector")).getFirst().results()).extracting(RetrievalHit::sourceId).containsExactly("a","b");
            }
        }
    }
    @Test void vectorBlobReadsPermitSubsequentConstrainedQueueWritesOnOneConnection()throws Exception {
        try(Database db=new Database();var pool=new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl(db.ds.getUrl());pool.setMaximumPoolSize(1);pool.setConnectionTimeout(1000);
            try(Connection c=pool.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("migration/sql/java-tasks.sql"));}
            var jdbc=new org.springframework.jdbc.core.JdbcTemplate(pool);
            var tasks=new com.tencent.weknora.task.TaskRepository(jdbc,JSON,new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool));
            var index=new SQLiteIndexRepository(pool,new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))));
            index.initialize();index.prepare(Set.of(3));
            tasks.enqueue(1,"test","before",JSON.createObjectNode(),4,java.time.Duration.ZERO);
            Connection duck=java.sql.DriverManager.getConnection("jdbc:duckdb:");
            tasks.enqueue(1,"test","after-duck",JSON.createObjectNode(),4,java.time.Duration.ZERO);
            try(Connection c=pool.getConnection()) {
                c.setAutoCommit(false);
                index.batchSave(c,List.of(new IndexEntry("a",0,"a","doc","kb","","hello",true)),Map.of("a",new float[]{1,0,0}));c.commit();
            }
            for(int i=0;i<3;i++){
                try(Connection c=pool.getConnection()){
                    new SQLiteVectorExtension(Path.of(System.getProperty("weknora.sqlite.vec.extension"))).load(c);
                    try(var s=c.prepareStatement("SELECT embedding FROM vec_embeddings_3 WHERE rowid=?")){
                        s.setLong(1,1);try(var r=s.executeQuery()){assertThat(r.next()).isTrue();assertThat(FloatVectors.decode(r.getBytes(1),3)).containsExactly(1,0,0);}
                    }
                }
                assertThat(tasks.enqueue(1,"test","after-"+i,JSON.createObjectNode(),4,java.time.Duration.ZERO).status()).isEqualTo("pending");
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM java_task_jobs",Integer.class)).isEqualTo(5);
            duck.close();
        }
    }
}
