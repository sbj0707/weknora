package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.storage.FileStore;
import java.time.Clock;
import java.util.*;

/** Native Go cloneFAQKnowledgeBase stages. This is distinct from ordinary document cloning. */
final class KnowledgeFAQClone {
  interface Model {int dimensions();}
  interface Engine {
    void delete(List<String> ids,int dimensions)throws Exception;
    void index(Model model,List<KnowledgeFAQMetadata.Entry> entries)throws Exception;
    void enabled(Map<String,Boolean> updates)throws Exception;
    void tags(Map<String,String> updates)throws Exception;
  }
  interface Effects {
    FileStore destinationStore(JsonNode target)throws Exception;
    Engine engine(JsonNode base)throws Exception;
    Model model(String id)throws Exception;
    void progress(ObjectNode value)throws Exception;
    void error(ObjectNode progress,Exception error,String message);
  }
  private final KnowledgeClonePlanner planner;
  private final KnowledgeFAQCloneRepository repository;
  private final KnowledgeFAQMetadata metadata;
  private final KnowledgeTransferMetadata transfers;
  private final ObjectMapper json;
  private final Effects effects;
  private final Clock clock;
  KnowledgeFAQClone(KnowledgeClonePlanner planner,KnowledgeFAQCloneRepository repository,ObjectMapper json,Effects effects,Clock clock) {
    this.planner=planner;this.repository=repository;this.json=json;this.effects=effects;this.clock=clock;
    metadata=new KnowledgeFAQMetadata(json);transfers=new KnowledgeTransferMetadata(json);
  }
  void execute(Scope scope,JsonNode source,JsonNode target,ObjectNode progress)throws Exception {
    var checked=planner.preflightFaq(scope,source,target);
    var store=effects.destinationStore(target);var cache=new LinkedHashMap<String,String>();var copied=new ArrayList<String>();
    try {
      List<Map<String,Object>> sources;
      try{sources=repository.documents(scope.tenant(),scope.source());}
      catch(Exception e){throw failure(progress,e,"Failed to get source FAQ knowledge");}
      if(sources.isEmpty()){complete(progress,"Source FAQ knowledge base is empty");return;}
      var srcDocument=sources.getFirst();KnowledgeFAQCloneRepository.Diff diff;
      try{diff=repository.diff(scope.tenant(),scope.source(),scope.target());}
      catch(Exception e){throw failure(progress,e,"Failed to calculate FAQ chunk difference");}
      for(String id:diff.add())if(!checked.source().containsKey(id))throw new IllegalArgumentException("FAQ source diff escaped transfer scope");
      for(String id:diff.remove())if(!checked.target().containsKey(id))throw new IllegalArgumentException("FAQ target diff escaped transfer scope");
      var matched=new ArrayList<KnowledgeFAQCloneRepository.Pair>();
      for(var pair:diff.matched()) {
        var src=checked.source().get(pair.source());var dst=checked.target().get(pair.target());
        if(src==null||dst==null)throw new IllegalArgumentException("FAQ matched diff escaped transfer scope");
        if(number(dst,"status")!=2){diff.add().add(pair.source());diff.remove().add(pair.target());}
        else matched.add(pair);
      }
      var tags=new HashMap<String,String>();
      java.util.function.Function<String,String> resolve=tag->tag.isEmpty()?"":tags.computeIfAbsent(tag,key->repository.tag(scope.tenant(),scope.tenant(),scope.target(),key));
      Map<String,Map<String,Object>> srcStatus,dstStatus;var updates=new ArrayList<KnowledgeFAQCloneRepository.Pair>();
      try {
        srcStatus=repository.statuses(scope.tenant(),matched.stream().map(KnowledgeFAQCloneRepository.Pair::source).toList());
        dstStatus=repository.statuses(scope.tenant(),matched.stream().map(KnowledgeFAQCloneRepository.Pair::target).toList());
        for(var pair:matched) {
          var src=srcStatus.get(pair.source());var dst=dstStatus.get(pair.target());
          if(src!=null&&dst!=null&&needs(src,dst,resolve.apply(text(src,"tag_id"))))updates.add(pair);
        }
      }catch(Exception e){throw failure(progress,e,"Failed to build FAQ status sync plan");}
      int total=diff.add().size()+diff.remove().size()+updates.size(),done=0;
      progress.put("total",total);save(progress,"Found "+diff.add().size()+" FAQ entries to add, "+diff.remove().size()+" to delete, "+updates.size()+" to update status");
      if(total==0){complete(progress,"FAQ knowledge base is already in sync");return;}
      Engine engine;Model model;
      try{engine=effects.engine(source);}catch(Exception e){throw failure(progress,e,"Failed to initialize retrieve engine");}
      try{model=effects.model(target.path("embedding_model_id").asText(""));}catch(Exception e){throw failure(progress,e,"Failed to get embedding model");}
      if(!diff.remove().isEmpty()) {
        try{engine.delete(diff.remove(),model.dimensions());}catch(Exception e){throw failure(progress,e,"Failed to delete FAQ entries from vector store");}
        try{repository.remove(scope.tenant(),diff.remove());}catch(Exception e){throw failure(progress,e,"Failed to delete FAQ entries from database");}
        done+=diff.remove().size();count(progress,done,total);save(progress,"Deleted "+diff.remove().size()+" FAQ entries, adding "+diff.add().size()+"...");
      }
      Map<String,Object> destination;
      try{destination=container(scope,target,srcDocument);}catch(Exception e){throw failure(progress,e,"Failed to prepare FAQ knowledge entry");}
      for(int at=0;at<diff.add().size();at+=50) {
        var batch=diff.add().subList(at,Math.min(diff.add().size(),at+50));var rows=new ArrayList<Map<String,Object>>();
        for(String id:batch) {
          var src=checked.source().get(id);String tag=resolve.apply(text(src,"tag_id"));String images;
          // Go appends a call's copied URLs only on success. A partial failure inside that call
          // can orphan its earlier images; do not silently change that failure contract here.
          var perChunk=new ArrayList<String>();
          try{images=KnowledgeCloneObjects.images(text(src,"image_info"),store,scope.tenant(),cache,perChunk,json);}
          catch(Exception e){throw failure(progress,e,"Failed to copy FAQ entry images");}
          copied.addAll(perChunk);
          var row=new LinkedHashMap<String,Object>();
          for(String key:List.of("source_content","last_editor_id","context_header","pre_chunk_id","next_chunk_id","parent_chunk_id"))row.put(key,"");
          for(String key:List.of("content_revision","start_at","end_at"))row.put(key,0);
          row.put("id",UUID.randomUUID().toString());row.put("tenant_id",scope.tenant());row.put("knowledge_id",text(destination,"id"));row.put("knowledge_base_id",scope.target());
          row.put("tag_id",tag);row.put("content",KnowledgeCloneObjects.rewrite(text(src,"content"),cache));row.put("chunk_index",number(src,"chunk_index"));
          row.put("is_enabled",KnowledgeFAQMetadata.enabled(src));row.put("flags",number(src,"flags"));row.put("chunk_type","faq");row.put("metadata",src.get("metadata"));
          row.put("content_hash",text(src,"content_hash"));row.put("image_info",images);row.put("status",1);row.put("index_status","ready");row.put("created_at",repository.now());row.put("updated_at",repository.now());
          rows.add(row);
        }
        try{repository.createChunks(rows);}catch(Exception e){throw failure(progress,e,"Failed to create FAQ entries");}
        copied.clear(); // Persisted chunks own the objects even when indexing fails later.
        try {
          var index=effects.engine(target);var entries=new ArrayList<KnowledgeFAQMetadata.Entry>();
          for(var row:rows)entries.addAll(metadata.indexes(target,row));
          index.index(model,entries);repository.indexedDocument(destination);
        }catch(Exception e){throw failure(progress,e,"Failed to index FAQ entries");}
        for(var row:rows)row.put("status",2);
        try{repository.updateRows(rows);}catch(Exception e){throw new IllegalStateException("failed to update FAQ chunks status: "+Objects.toString(e.getMessage(),""),e);}
        done+=batch.size();count(progress,done,total);save(progress,"Added "+(done-diff.remove().size())+"/"+diff.add().size()+" FAQ entries");
      }
      for(int at=0;at<updates.size();at+=50) {
        var batch=updates.subList(at,Math.min(updates.size(),at+50));
        try{sync(scope,target,batch,srcStatus,dstStatus,resolve);}catch(Exception e){throw failure(progress,e,"Failed to sync FAQ status fields");}
        done+=batch.size();count(progress,done,total);save(progress,"Updated "+(done-diff.remove().size()-diff.add().size())+"/"+updates.size()+" FAQ status fields");
      }
      progress.put("processed",total);complete(progress,"FAQ knowledge base clone completed successfully");
    }catch(Exception e){KnowledgeCloneObjects.cleanup(store,copied);throw e;}
  }
  private Map<String,Object> container(Scope scope,JsonNode target,Map<String,Object> source)throws Exception {
    var documents=repository.documents(scope.tenant(),scope.target());if(!documents.isEmpty())return documents.getFirst();
    var row=new LinkedHashMap<String,Object>();
    for(String key:List.of("title","description","source","channel"))row.put(key,text(source,key));
    for(String key:List.of("file_name","folder_path","file_type","file_hash","file_path","error_message"))row.put(key,"");
    for(String key:List.of("file_size","storage_size","pending_subtasks_count"))row.put(key,0);
    row.put("id",UUID.randomUUID().toString());row.put("tenant_id",scope.tenant());row.put("knowledge_base_id",scope.target());
    row.put("type","faq");row.put("parse_status","completed");row.put("enable_status","enabled");row.put("embedding_model_id",target.path("embedding_model_id").asText(""));
    try{row.put("metadata",transfers.withoutTransfer(source.get("metadata")));}catch(Exception e){throw new IllegalStateException("decode source FAQ metadata: "+Objects.toString(e.getMessage(),""),e);}
    repository.createDocument(row);return row;
  }
  private void sync(Scope scope,JsonNode target,List<KnowledgeFAQCloneRepository.Pair> pairs,
      Map<String,Map<String,Object>> sources,Map<String,Map<String,Object>> targets,java.util.function.Function<String,String> resolve)throws Exception {
    var rows=new ArrayList<Map<String,Object>>();var enabled=new LinkedHashMap<String,Boolean>();var tags=new LinkedHashMap<String,String>();var patch=new ArrayList<KnowledgeFAQCloneRepository.Pair>();boolean recommended=false;
    for(var pair:pairs) {
      var src=sources.get(pair.source());var dst=targets.get(pair.target());if(src==null||dst==null)continue;
      String tag=resolve.apply(text(src,"tag_id"));if(!needs(src,dst,tag))continue;
      var row=new LinkedHashMap<String,Object>();row.put("id",pair.target());row.put("is_enabled",KnowledgeFAQMetadata.enabled(src));row.put("flags",number(src,"flags"));row.put("tag_id",tag);rows.add(row);
      if(KnowledgeFAQMetadata.enabled(src)!=KnowledgeFAQMetadata.enabled(dst))enabled.put(pair.target(),KnowledgeFAQMetadata.enabled(src));
      if(!tag.equals(text(dst,"tag_id")))tags.put(pair.target(),tag);
      if(((number(src,"flags")^number(dst,"flags"))&1)!=0)recommended=true;
      if(!text(src,"answer_strategy").equals(text(dst,"answer_strategy")))patch.add(pair);
    }
    if(!rows.isEmpty())repository.updateRows(rows);
    for(var pair:patch) {
      String value;
      try{value=metadata.patch(targets.get(pair.target()).get("metadata"),sources.get(pair.source()).get("metadata"));}
      catch(Exception e){throw new IllegalStateException("patch answer_strategy for "+pair.target()+": "+Objects.toString(e.getMessage(),""),e);}
      repository.patchMetadata(scope.tenant(),pair.target(),value);
    }
    if(enabled.isEmpty()&&tags.isEmpty()&&!recommended)return;
    var engine=effects.engine(target);if(!enabled.isEmpty())engine.enabled(enabled);if(!tags.isEmpty())engine.tags(tags);
    // Go has no portable vector recommended flag update; only the SQL flags change.
  }
  private static boolean needs(Map<String,Object> src,Map<String,Object> dst,String tag) {
    return !tag.equals(text(dst,"tag_id"))||KnowledgeFAQMetadata.enabled(src)!=KnowledgeFAQMetadata.enabled(dst)||number(src,"flags")!=number(dst,"flags")||!text(src,"answer_strategy").equals(text(dst,"answer_strategy"));
  }
  private Exception failure(ObjectNode progress,Exception error,String message){effects.error(progress,error,message);return error;}
  private void count(ObjectNode p,int done,int total){p.put("processed",done);if(total>0)p.put("progress",done*100/total);}
  private void complete(ObjectNode p,String message){p.put("status","completed");p.put("progress",100);save(p,message);}
  private void save(ObjectNode p,String message){p.put("message",message);p.put("updated_at",clock.instant().getEpochSecond());try{effects.progress(p.deepCopy());}catch(Exception ignored){/* Go logs progress persistence errors and continues. */}}
}
