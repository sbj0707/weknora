package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.model.client.ModelClientException;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.vectorstore.*;
import com.tencent.weknora.security.ApiException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;

/** Default SQLite/PostgreSQL indexes plus independently owned, registered bound instances. */
public final class HybridSearchStore {
  private VectorStoreRegistry registry;
  public HybridSearchStore withRegistry(VectorStoreRegistry registry){this.registry=registry;return this;}
  public VectorStoreEngine bound(long owner,String id,CallContext call){if(registry==null)throw unsupported("bound vector stores");try(var child=new CallContext(HybridSearchDeadline.deadline(call));var cancellation=call.onCancel(child::cancel)){return registry.resolve(owner,id,child);}catch(VectorStoreRegistry.Failure failure){if(failure.reason()==VectorStoreRegistry.Reason.FORBIDDEN)throw new ApiException(400,"VECTOR_STORE_BINDING_INVALID","vector store bound to the knowledge base is not available").withAppCode(2200);throw new ApiException(400,"VECTOR_STORE_UNAVAILABLE","vector store is currently unavailable").withAppCode(2201);}catch(ModelClientException deadline){throw new ApiException(400,"VECTOR_STORE_UNAVAILABLE","vector store is currently unavailable").withAppCode(2201);}catch(java.util.concurrent.CancellationException canceled){throw canceled;}catch(RuntimeException failure){throw failure;}catch(Exception closing){throw new IllegalStateException(closing);}}
  public List<RetrievalResult> retrieve(RetrievalQuery query,VectorStoreEngine bound,CallContext call){if(bound==null)return retrieve(query,call);try(var child=new CallContext(HybridSearchDeadline.deadline(call));var cancellation=call.onCancel(child::cancel)){return bound.retrieve(query,child);}catch(RuntimeException failure){throw failure;}catch(Exception close){throw new IllegalStateException(close);}}
  private final DataSource source;private final Environment environment;private SQLiteIndexRepository sqlite;private PostgresIndexRepository postgres;
  public HybridSearchStore(DataSource source,Environment environment){this.source=HybridSearchDeadline.source(source);this.environment=environment;}
  public Set<String> capabilities(JsonNode engines){var values=engines==null?null:engines.isArray()?engines:engines.path("engines");if(values==null||values.isNull()||values.isMissingNode()||values.isEmpty())return Set.of("vector","keywords");var result=new HashSet<String>();for(var entry:values){String engine=entry.path("retriever_engine_type").asText(),kind=entry.path("retriever_type").asText();String configured=environment.getProperty("RETRIEVE_DRIVER",engine);if(!engine.equals(configured)||!Set.of("sqlite","postgres").contains(engine))throw unsupported("retrieval engine "+engine);if(!Set.of("vector","keywords").contains(kind))throw unsupported("retriever type "+kind);result.add(kind);}return result;}
  public void checkBinding(JsonNode kb){if(kb.hasNonNull("vector_store_id")&&!kb.path("vector_store_id").asText().isEmpty())throw unsupported("bound vector stores");}
  public List<RetrievalResult> retrieve(RetrievalQuery query,CallContext call){
    call.check();try{resolve(call);try(var retrieval=HybridSearchDeadline.start(call,null)){var result=sqlite!=null?sqlite.retrieve(query):postgres.retrieve(query);call.check();return result;}}catch(SQLException failure){org.slf4j.LoggerFactory.getLogger(HybridSearchStore.class).warn("Native hybrid physical store retrieval failed",failure);throw new ApiException(503,"VECTOR_STORE_UNAVAILABLE","vector store is currently unavailable").withAppCode(2201);}
  }
  void resolve(CallContext call){try(var resolution=HybridSearchDeadline.start(call,java.time.Duration.ofSeconds(12))){initialize();}catch(SQLException failure){throw new ApiException(503,"VECTOR_STORE_UNAVAILABLE","vector store is currently unavailable").withAppCode(2201);}}
  HybridSearchDeadline groupBudget(CallContext call,boolean multiple){return HybridSearchDeadline.start(call,multiple?budget():null);}
  private java.time.Duration budget(){long seconds=30;try{long configured=Long.parseLong(environment.getProperty("MULTI_STORE_RETRIEVE_TIMEOUT_SEC",""));if(configured>0)seconds=configured;}catch(NumberFormatException ignored){}return java.time.Duration.ofSeconds(seconds);}
  private synchronized void initialize()throws SQLException {
    if(sqlite!=null||postgres!=null)return;
    String database;try(var c=source.getConnection()){database=c.getMetaData().getDatabaseProductName();}
    String driver=environment.getProperty("RETRIEVE_DRIVER",database.equals("SQLite")?"sqlite":"postgres");
    if(database.equals("SQLite")&&driver.equals("sqlite")){
      String path=environment.getProperty("weknora.sqlite.vec.extension",environment.getProperty("WEKNORA_SQLITE_VEC_EXTENSION",""));if(path.isEmpty())throw unsupported("SQLite without WEKNORA_SQLITE_VEC_EXTENSION");var next=new SQLiteIndexRepository(source,new SQLiteVectorExtension(Path.of(path)));next.initialize();sqlite=next;
    }else if(database.equals("PostgreSQL")&&driver.equals("postgres")){var next=new PostgresIndexRepository(source);next.initialize();postgres=next;}
    else throw unsupported("retrieval engine "+driver+" on "+database);
  }
  static ApiException unsupported(String detail){return new ApiException(503,"HYBRID_SEARCH_NOT_MIGRATED","Native hybrid search is not migrated for "+detail).withAppCode(2201);}
}
