package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.weknora.storage.FileStore;
import java.time.*;
import java.util.*;

/** Native Go CloneChunk ordering, including uncertain writes and cleanup claims. */
final class KnowledgeCloneChunks {
  interface Indices {
    void copy(Map<String,String> documents,Map<String,String> chunks)throws Exception;
    void delete()throws Exception;
  }
  interface Effects {
    void requireTransfer(Scope scope,JsonNode source,JsonNode target)throws Exception;
    FileStore destinationStore(JsonNode target)throws Exception;
    /** Resolve the source-bound engine first, then the destination document's embedding model. */
    Indices indices(JsonNode source,Map<String,Object> destination)throws Exception;
  }
  private final KnowledgeTransferRepository transfers;
  private final KnowledgeCloneRepository repository;
  private final ObjectMapper json;
  private final Effects effects;
  private final Clock clock;
  private final com.tencent.weknora.security.GoStoredStructDecoder locators;

  KnowledgeCloneChunks(KnowledgeTransferRepository transfers,KnowledgeCloneRepository repository,
      ObjectMapper json,Effects effects,Clock clock) {
    this.transfers=transfers;this.repository=repository;this.json=json;this.effects=effects;this.clock=clock;
    try(var in=getClass().getResourceAsStream("/knowledge-read-schema.json")) {
      locators=new com.tencent.weknora.security.GoStoredStructDecoder(json,json.readTree(Objects.requireNonNull(in)).path("locators"));
    }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
  }

  void copy(Scope scope,JsonNode source,JsonNode target,Map<String,Object> src,Map<String,Object> dst)throws Exception {
    requirePair(scope,source,target);binding(src,scope.tenant(),scope.source());binding(dst,scope.tenant(),scope.target());
    effects.requireTransfer(scope,source,target);
    Map<String,Object> stored;
    try {stored=transfers.get(scope.tenant(),text(dst,"id"));}
    catch(IllegalArgumentException e){if("record not found".equals(e.getMessage()))throw new IllegalArgumentException("knowledge not found",e);throw e;}
    if(!text(stored,"id").equals(text(dst,"id"))||number(stored,"tenant_id")!=number(dst,"tenant_id")||!text(stored,"knowledge_base_id").equals(scope.target()))
      throw new IllegalArgumentException("kb_access: forbidden");
    var chunks=transfers.chunks(scope.tenant(),text(src,"id"),Set.of(scope.source()));
    FileStore store;
    try{store=effects.destinationStore(target);}
    catch(Exception e){throw wrap("failed to load destination knowledge base for image copy: ",e);}
    var copied=new ArrayList<String>();var cache=new LinkedHashMap<String,String>();
    var ids=new LinkedHashMap<String,String>();var tags=new HashMap<String,String>();
    boolean attempted=false;Indices indices=null;
    try {
      var result=new ArrayList<Map<String,Object>>();
      // One timestamp for every chunk in this invocation, including independently committed pages.
      Object now=repository.now();
      for(var chunk:chunks) {
        String tag=text(chunk,"tag_id"),mapped="";
        if(!tag.isEmpty())mapped=tags.computeIfAbsent(tag,key->repository.tag(scope.tenant(),scope.tenant(),scope.target(),key));
        String images;
        try{images=KnowledgeCloneObjects.images(text(chunk,"image_info"),store,scope.tenant(),cache,copied,json);}
        catch(Exception e){throw wrap("clone chunk image copy failed: ",e);}
        var row=new LinkedHashMap<String,Object>();
        for(String key:List.of("content","chunk_index","is_enabled","flags","status","index_status","start_at","end_at","pre_chunk_id","next_chunk_id","chunk_type","parent_chunk_id","metadata","content_hash","source_locators"))row.put(key,chunk.get(key));
        for(String key:List.of("content","index_status","pre_chunk_id","next_chunk_id","chunk_type","parent_chunk_id","content_hash"))row.put(key,text(chunk,key));
        if(text(row,"index_status").isEmpty())row.put("index_status","ready");
        if(text(row,"chunk_type").isEmpty())row.put("chunk_type","text");
        for(String key:List.of("chunk_index","flags","status","start_at","end_at"))row.put(key,number(chunk,key));
        row.put("is_enabled",chunk.get("is_enabled")!=null&&Set.of("true","1").contains(chunk.get("is_enabled").toString()));
        String id=UUID.randomUUID().toString();ids.put(text(chunk,"id"),id);
        row.put("id",id);row.put("tenant_id",scope.tenant());row.put("knowledge_id",text(dst,"id"));row.put("knowledge_base_id",scope.target());row.put("tag_id",mapped);row.put("image_info",images);
        row.put("source_content","");row.put("content_revision",0);row.put("last_editor_id","");row.put("context_header","");
        Object storedLocators=chunk.get("source_locators");
        String locatorText=storedLocators instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):Objects.toString(storedLocators,"");
        if(!locatorText.isEmpty()) {
          var projected=locators.project(locatorText);
          row.put("source_locators",projected.isNull()||projected.isEmpty()?null:projected.toString());
        }
        row.put("created_at",now);row.put("updated_at",now);
        result.add(row);
      }
      for(var row:result) {
        row.put("content",KnowledgeCloneObjects.rewrite(text(row,"content"),cache));
        for(String key:List.of("pre_chunk_id","next_chunk_id","parent_chunk_id"))row.put(key,ids.getOrDefault(text(row,key),""));
      }
      for(int at=0;at<result.size();at+=100) {
        attempted=true;repository.createChunks(result.subList(at,Math.min(result.size(),at+100)));
      }
      if(ids.isEmpty()||text(dst,"embedding_model_id").isEmpty())return;
      indices=effects.indices(source,dst);
      indices.copy(Map.of(text(src,"id"),text(dst,"id")),ids);
    }catch(Exception failure) {
      if(attempted) {
        var after=new LinkedHashMap<>(stored);after.put("parse_status","failed");after.put("error_message",Objects.toString(failure.getMessage(),""));
        try{transfers.checkpoint(stored,after);}
        catch(Exception e){throw joined(failure,"claim failed clone cleanup: ",e);}
        if(indices!=null)try{indices.delete();}catch(Exception e){throw joined(failure,"clean failed clone indices: ",e);}
        try{repository.deleteChunks(scope.tenant(),text(dst,"id"));}
        catch(Exception e){throw joined(failure,"clean failed clone chunks: ",e);}
      }
      KnowledgeCloneObjects.cleanup(store,copied);
      throw failure;
    }
  }
  private static void binding(Map<String,Object> row,long tenant,String kb) {
    if(row==null||text(row,"id").isEmpty()||number(row,"tenant_id")!=tenant||!text(row,"knowledge_base_id").equals(kb))throw new IllegalArgumentException("kb_access: forbidden");
  }
  private static Exception wrap(String prefix,Exception e){return new IllegalStateException(prefix+Objects.toString(e.getMessage(),""),e);}
  private static Exception joined(Exception first,String prefix,Exception second){var e=new IllegalStateException(Objects.toString(first.getMessage(),"")+"\n"+prefix+Objects.toString(second.getMessage(),""),first);e.addSuppressed(second);return e;}
}
