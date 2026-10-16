package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.model.client.CallContext;
import java.time.Duration;
import java.util.*;

/** Go KB teardown ordering. Queue admission and lease fencing belong to the task adapter. */
public final class KnowledgeBaseDeleteProcessor {
  public record Payload(long tenantId,String knowledgeBaseId,List<String> dataSourceIds,JsonNode effectiveEngines,String vectorStoreId) {}
  public record Document(String id,String embeddingModelId,String type,String filePath,long storageSize) {}
  public enum Verdict { RETRY, SKIP_RETRY }
  /** Go's nil *ImageInfo panic is recovered by the queue, after deferred Wiki/task cleanup. */
  public static final class Panic extends RuntimeException {
    public Panic(){super("panic: runtime error: invalid memory address or nil pointer dereference");}
  }
  public static final class Failure extends Exception {
    private final Verdict verdict;
    public Failure(Verdict verdict,String message){super(message);this.verdict=verdict;}
    public Verdict verdict(){return verdict;}
  }
  public interface Repository {
    List<Document> documents(Payload payload,CallContext call)throws Exception;
    List<String> images(Payload payload,List<String> ids,CallContext call)throws Exception;
    void chunks(Payload payload,String id,CallContext call)throws Exception;
    void accounting(Payload payload,long delta,CallContext call)throws Exception;
    void documentsDeleted(Payload payload,List<String> ids,CallContext call)throws Exception;
    void wiki(Payload payload,String table,CallContext call)throws Exception;
  }
  @FunctionalInterface public interface Indexes {
    void delete(List<String> ids,int dimensions,String type,CallContext call)throws Exception;
  }
  public interface Effects {
    Indexes resolve(Payload payload,CallContext call)throws Exception;
    int dimensions(Payload payload,String model,CallContext call)throws Exception;
    void file(Payload payload,String reference,CallContext call)throws Exception;
    boolean releaseImage(Payload payload,String reference,List<String> ids,CallContext call)throws Exception;
    void graph(Payload payload,List<String> ids,CallContext call)throws Exception;
    /** Durable pending operations first, then queue cancellation; implementations bound each scan. */
    void scrub(Payload payload,List<String> ids,CallContext call)throws Exception;
  }
  private final Repository repository;
  private final Effects effects;
  public KnowledgeBaseDeleteProcessor(Repository repository,Effects effects){this.repository=repository;this.effects=effects;}

  public void process(Payload payload,CallContext call)throws Exception {
    List<String> ids=null;Exception failure=null;
    try {
      var documents=repository.documents(payload,call);
      ids=documents.stream().map(Document::id).toList();
      scrub(payload,ids,call);
      if(!documents.isEmpty()) {
        // Resolution failure aborts heavy cleanup. Forbidden/not-found must be classified
        // by the resolver as SKIP_RETRY; transient failure/cancellation remains retryable.
        var indexes=effects.resolve(payload,call);
        record Group(String model,String type){}
        var groups=new LinkedHashMap<Group,List<String>>();
        for(var document:documents)groups.computeIfAbsent(new Group(document.embeddingModelId(),document.type()),unused->new ArrayList<>()).add(document.id());
        for(var group:groups.entrySet()) {
          try {
            int dimensions=effects.dimensions(payload,group.getKey().model(),call);
            try {indexes.delete(group.getValue(),dimensions,group.getKey().type(),call);}
            catch(Exception error){warn("indexes",error);}
          }catch(Exception error){warn("embedding model",error);}
        }
        List<String> images=List.of();
        try {images=repository.images(payload,ids,call);}catch(Panic panic){throw panic;}catch(Exception error){warn("image collection",error);}
        for(String id:ids)try{repository.chunks(payload,id,call);}catch(Exception error){warn("chunks",error);}
        long delta=0;
        for(var document:documents) {
          if(!document.filePath().isEmpty())try{effects.file(payload,document.filePath(),call);}catch(Exception error){warn("file",error);}
          delta-=document.storageSize();
        }
        for(String image:images)try {
          if(effects.releaseImage(payload,image,ids,call))try{effects.file(payload,image,call);}catch(Exception error){warn("image file",error);}
        }catch(Exception error){warn("image ownership",error);}
        if(delta!=0)try{repository.accounting(payload,delta,call);}catch(Exception error){warn("accounting",error);}
        try{effects.graph(payload,ids,call);}catch(Exception error){warn("graph",error);}
        repository.documentsDeleted(payload,ids,call);
      }
    }catch(Exception error){failure=error;}
    // Both detached passes run even after cancellation, an initial list failure or SkipRetry.
    // Each gets its own deadline so slow Wiki work cannot starve the final queue scrub.
    boolean interrupted=Thread.interrupted();
    try {if(!payload.knowledgeBaseId().isEmpty()) {
      // A worker timeout can interrupt the parent *after* its body noticed the deadline.
      // Isolate deferred SQL from both that race and subsequent lease/shutdown interrupts,
      // just as context.WithoutCancel isolates Go cleanup. Join before settling the task;
      // no cleanup is left running after this method returns.
      var cleanup=new java.util.concurrent.CompletableFuture<Exception>();
      var cleanupIds=ids;var primary=failure;
      Thread.ofVirtual().name("kb-delete-cleanup").start(()->{
        try {cleanup.complete(detached(payload,cleanupIds,primary));}
        catch(Throwable error){cleanup.completeExceptionally(error);}
      });
      failure=cleanup.join();
    }}finally{if(interrupted)Thread.currentThread().interrupt();}
    if(failure!=null)throw failure;
  }
  private Exception detached(Payload payload,List<String> ids,Exception failure) {
      Exception wikiFailure=null;
      try(var detached=CallContext.timeout(Duration.ofSeconds(5))) {
        for(String table:List.of("wiki_pages","wiki_folders","wiki_page_revisions","wiki_page_issues")) {
          try{repository.wiki(payload,table,detached);}
          catch(Exception error){if(wikiFailure==null)wikiFailure=error;else wikiFailure.addSuppressed(error);}
        }
      }
      if(wikiFailure!=null) {
        if(failure==null||failure instanceof Failure f&&f.verdict()==Verdict.SKIP_RETRY)failure=wikiFailure;
        else failure.addSuppressed(wikiFailure);
      }
      try(var detached=CallContext.timeout(Duration.ofSeconds(5))){scrub(payload,ids,detached);}
      return failure;
  }
  private void scrub(Payload payload,List<String> ids,CallContext call) {
    if(payload.knowledgeBaseId().isEmpty())return;
    try{effects.scrub(payload,ids,call);}catch(Exception error){warn("task scrub",error);}
  }
  private static void warn(String stage,Exception error){org.slf4j.LoggerFactory.getLogger(KnowledgeBaseDeleteProcessor.class).warn("Knowledge base deletion {} cleanup failed ({})",stage,error.getClass().getSimpleName());}
}
