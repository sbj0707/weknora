package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.retrieval.*;
import com.tencent.weknora.task.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Native production table-summary task, using the original Go prompts and persistence checkpoints. */
public final class DataTableSummaryHandler implements TaskHandler {
  public interface Models {
    ChatModel chat(long owner,String id);
    EmbeddingModel embedding(long owner,String id);
  }
  @FunctionalInterface public interface Files { InputStream open(long owner,String kb,String path)throws IOException; }
  @FunctionalInterface public interface Connections { Connection open()throws SQLException; }
  private final DataTableSummaryRepository repository;
  private final ObjectMapper json;
  private final Models models;
  private final Files files;
  private final DocumentProcessingHandler.Indexes indexes;
  private final Connections connections;
  private final long maximumBytes;
  private final DataTableSummaryText text;

  public DataTableSummaryHandler(DataTableSummaryRepository repository,ObjectMapper json,Models models,
      Files files,DocumentProcessingHandler.Indexes indexes,Connections connections,long maximumBytes) {
    this.repository=repository;this.json=json;this.models=models;this.files=files;
    this.indexes=indexes;this.connections=connections;this.maximumBytes=maximumBytes;
    text=new DataTableSummaryText(json);
  }
  @Override public String type(){return "datatable:summary";}
  @Override public Duration timeout(){return Duration.ofMinutes(30);}

  @Override public JsonNode execute(TaskRecord job,TaskCancellation cancellation)throws Exception {
    cancellation.check();
    var loaded=repository.load(job);
    if(loaded.isEmpty())return json.createObjectNode().put("skipped","knowledge_not_found");
    var state=loaded.get();var knowledge=state.knowledge();long owner=job.tenantId();
    // Preserve Go resource-validation order before opening storage or making any model request.
    ChatModel chat=models.chat(owner,job.payload().path("summary_model").asText());
    EmbeddingModel embedding=models.embedding(owner,job.payload().path("embedding_model").asText());
    IndexWriter writer=indexes.resolve(owner,state.knowledgeBase());
    String type=knowledge.path("file_type").asText().toLowerCase(Locale.ROOT);
    Path local=java.nio.file.Files.createTempFile("weknora-table-","."+type);
    try(var call=new CallContext();var db=connections.open();
        var session=new DuckDbTableSession(db,"table_summary_"+knowledge.path("id").asText(),cancellation::check)) {
      Thread worker=Thread.currentThread();
      var watcher=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("table-summary-cancel").factory());
      var monitor=watcher.scheduleWithFixedDelay(()->{
        try {if(worker.isInterrupted()||cancellation.isCanceled()){call.cancel();session.cancel();}}
        catch(RuntimeException e){call.cancel();session.cancel();}
      },50,50,TimeUnit.MILLISECONDS);
      try {
        materialize(job,state,local,cancellation);
        ObjectNode schema=session.load(knowledge.path("id").asText(),type,local);
        var sample=session.sample(schema.path("table_name").asText());
        JsonNode effective=new KnowledgeProcessConfig(json).resolve(state.knowledgeBase(),
            knowledge.path("metadata").path("process_overrides"));
        String instructions=effective.path("chunking_config").path("table_metadata_instructions").asText();
        String description=DuckDbTableSession.description(schema), samples=session.sampleDescription(sample);
        String summary=generate(chat,call,false,description,samples,instructions);
        cancellation.check();
        String columns=generate(chat,call,true,description,samples,instructions);
        cancellation.check();
        var chunks=chunks(summary,columns);
        var entries=chunks.stream().map(c->new IndexEntry(c.id(),0,c.id(),knowledge.path("id").asText(),
            state.knowledgeBase().path("id").asText(),"",c.content(),true)).toList();
        try {
          repository.create(job,state,chunks);
          var vectors=embed(embedding,call,entries,cancellation);
          writer.prepare(Set.of(embedding.dimensions()));
          repository.index(job,entries,vectors,writer);
          repository.indexed(job,chunks);
        } catch(CancellationException e){throw e;}
        catch(Exception e) {
          String message=diagnostic(e);
          try {repository.cleanup(job,state,chunks,writer,message);}
          catch(CancellationException canceled){throw canceled;}
          catch(RuntimeException cleanupError){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Table summary failure checkpoint unavailable");}
          throw new TaskFailure(message,true);
        }
        return json.createObjectNode().put("chunks",chunks.size());
      } finally {monitor.cancel(false);watcher.shutdownNow();}
    } finally {java.nio.file.Files.deleteIfExists(local);}
  }

  private void materialize(TaskRecord job,DataTableSummaryRepository.Snapshot state,Path path,
      TaskCancellation cancellation)throws IOException {
    try(var in=files.open(job.tenantId(),state.knowledgeBase().path("id").asText(),state.knowledge().path("file_path").asText());
        var out=java.nio.file.Files.newOutputStream(path)) {
      byte[] buffer=new byte[65536];long size=0;int read;
      while((read=in.read(buffer))!=-1){cancellation.check();size+=read;
        if(size>maximumBytes)throw new IOException("table file exceeds MAX_FILE_SIZE_MB");
        out.write(buffer,0,read);
      }
    }
  }
  private String generate(ChatModel chat,CallContext call,boolean column,String schema,String sample,String instructions)throws TaskFailure {
    String stage=column?"column descriptions":"table description";
    try {
      ObjectNode message=json.createObjectNode().put("role","user").put("content",text.prompt(column,schema,sample,instructions));
      return text.content(column,chat.chat(call,List.of(message),text.options(column)));
    }catch(CancellationException e){throw e;}
    catch(RuntimeException e){
      String message=diagnostic(e);
      if(chat instanceof OpenAIChat && e instanceof ModelClientException && message.startsWith("API request failed"))
        message="create chat completion: "+message;
      throw new TaskFailure("failed to generate "+stage+": "+message,true);
    }
  }
  private static Map<String,float[]> embed(EmbeddingModel model,CallContext call,List<IndexEntry> entries,
      TaskCancellation cancellation) {
    RuntimeException last=null;
    for(int attempt=0;attempt<5;attempt++){
      cancellation.check();
      try {
        var values=model.embed(call,entries.stream().map(e->IndexText.embeddingInput(e.content())).toList(),false);
        if(values.length!=entries.size())throw new ModelClientException("embedding model returned "+values.length+" embeddings for "+entries.size()+" inputs");
        var out=new LinkedHashMap<String,float[]>();
        for(int i=0;i<values.length;i++){FloatVectors.validate(values[i],model.dimensions());out.put(entries.get(i).sourceId(),values[i]);}
        return out;
      }catch(CancellationException e){throw e;}
      catch(RuntimeException e){last=e;if(attempt<4)call.pause(Duration.ofMillis(200L<<attempt));}
    }
    throw last;
  }
  private static List<DocumentChunks.Stored> chunks(String summary,String columns) {
    String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
    return List.of(new DocumentChunks.Stored(a,summary,"",0,0,0,"table_summary","","",b,List.of()),
        new DocumentChunks.Stored(b,columns,"",1,0,0,"table_column",a,a,"",List.of()));
  }
  static String diagnostic(Throwable error) {
    Throwable root=error;
    while(root.getCause()!=null)root=root.getCause();
    String message=root.getMessage();
    if(message==null||message.isEmpty())return error.getClass().getSimpleName();
    if(root instanceof org.sqlite.SQLiteException && message.endsWith(")")){
      int start=message.lastIndexOf(" (");if(start>=0)return message.substring(start+2,message.length()-1);
    }
    return message;
  }
}
