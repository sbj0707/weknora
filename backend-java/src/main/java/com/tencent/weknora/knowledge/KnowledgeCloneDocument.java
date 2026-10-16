package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.GoStoredStructDecoder;
import com.tencent.weknora.storage.FileStore;
import java.io.*;
import java.util.*;

/** Go cloneKnowledge: independently owned file and durable document around native CloneChunk. */
final class KnowledgeCloneDocument {
  interface Effects extends KnowledgeCloneChunks.Effects {
    JsonNode loadSource(String id)throws Exception;
    FileStore sourceStore(JsonNode source,String path)throws Exception;
  }
  private final KnowledgeCloneRepository repository;
  private final KnowledgeTransferRepository transfers;
  private final KnowledgeTransferMetadata metadata;
  private final KnowledgeCloneChunks chunks;
  private final ObjectMapper json;
  private final Effects effects;
  private final GoStoredStructDecoder profiles;

  KnowledgeCloneDocument(KnowledgeCloneRepository repository,KnowledgeTransferRepository transfers,
      KnowledgeCloneChunks chunks,ObjectMapper json,Effects effects) {
    this.repository=repository;this.transfers=transfers;this.chunks=chunks;
    this.json=json;this.metadata=new KnowledgeTransferMetadata(json);this.effects=effects;
    try(var in=getClass().getResourceAsStream("/knowledge-read-schema.json")) {
      profiles=new GoStoredStructDecoder(json,json.readTree(Objects.requireNonNull(in)).path("profile"));
    }catch(IOException e){throw new UncheckedIOException(e);}
  }

  void copy(Scope scope,JsonNode source,JsonNode target,Map<String,Object> src)throws Exception {
    requirePair(scope,source,target);
    if(src==null||text(src,"id").isEmpty()||text(src,"knowledge_base_id").isEmpty()||number(src,"tenant_id")==0)
      throw new IllegalArgumentException("error code: 1003, error message: knowledge not found");
    if(number(src,"tenant_id")!=source.path("tenant_id").asLong()||!text(src,"knowledge_base_id").equals(source.path("id").asText()))
      throw new IllegalArgumentException("error code: 1002, error message: knowledge does not belong to its knowledge base");
    effects.requireTransfer(scope,source,target);
    String sourceId=text(src,"id");
    if(!text(src,"parse_status").equals("completed"))
      throw new IllegalArgumentException("source knowledge "+sourceId+" is not completed");
    transfers.chunks(scope.tenant(),sourceId,Set.of(scope.source()));
    var dst=new LinkedHashMap<String,Object>();
    for(String key:List.of("type","channel","title","description","source","file_name","folder_path","file_type","file_hash","file_path"))
      dst.put(key,text(src,key));
    dst.put("file_size",number(src,"file_size"));
    dst.put("metadata",src.get("metadata"));dst.put("custom_metadata",src.get("custom_metadata"));
    String profile=KnowledgeCloneRepository.raw(src.get("profile"));
    if(!profile.isEmpty()) {
      var typed=profiles.project(profile);
      var ordered=json.createObjectNode();
      for(String key:List.of("gist","topics","doc_type","typical_question"))if(typed.has(key))ordered.set(key,typed.get(key));
      dst.put("profile",json.writeValueAsString(ordered).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026").replace("\u2028","\\u2028").replace("\u2029","\\u2029"));
    }
    dst.put("id",UUID.randomUUID().toString());dst.put("tenant_id",scope.tenant());dst.put("knowledge_base_id",scope.target());
    dst.put("parse_status","processing");dst.put("enable_status","disabled");
    dst.put("embedding_model_id",target.path("embedding_model_id").asText(""));
    dst.put("storage_size",0L);dst.put("pending_subtasks_count",0);dst.put("error_message","");
    ObjectNode state=json.createObjectNode().put("task_id",scope.task()).put("operation","clone")
        .put("source_kb",scope.source()).put("target_kb",scope.target()).put("source_id",sourceId).put("phase","cloning");
    dst.put("metadata",metadata.write(dst.get("metadata"),state));
    var copied=new ArrayList<String>();
    String path=text(src,"file_path");
    if(!path.isEmpty()) {
      JsonNode loaded;
      try{loaded=effects.loadSource(scope.source());}
      catch(Exception e){throw wrap("clone knowledge: failed to load source knowledge base: ",e);}
      var sourceStore=effects.sourceStore(loaded,path);
      var targetStore=effects.destinationStore(target);
      String created;
      try{created=KnowledgeCloneObjects.copy(sourceStore,targetStore,path,scope.tenant());}
      catch(Exception e){throw wrap("clone knowledge file copy failed: ",e);}
      dst.put("file_path",created);copied.add(created);
    }
    try {
      repository.createDocument(dst);
      // Go reloads only UpdatedAt; concurrent changes to other fields remain subject to final CAS.
      var stored=repository.document(scope.tenant(),text(dst,"id"));
      binding(stored,target);
      dst.put("updated_at",stored.get("updated_at"));
      chunks.copy(scope,source,target,src,dst);
      var before=new LinkedHashMap<>(dst);
      state.put("phase","done");dst.put("metadata",metadata.write(dst.get("metadata"),state));
      dst.put("parse_status","completed");dst.put("enable_status","enabled");dst.put("storage_size",number(src,"storage_size"));
      transfers.checkpoint(before,dst);
    }catch(Exception failure) {
      failed(scope,source,target,src,dst,copied,failure);
      throw failure;
    }
  }

  private void failed(Scope scope,JsonNode source,JsonNode target,Map<String,Object> src,
      Map<String,Object> dst,List<String> copied,Exception failure) {
    Map<String,Object> stored;
    try{stored=repository.document(scope.tenant(),text(dst,"id"));}
    catch(KnowledgeCloneRepository.MissingDocument absent) {
      try{KnowledgeCloneObjects.cleanup(effects.destinationStore(target),copied);}catch(Exception ignored){}
      return;
    }catch(Exception uncertain){return;}
    try {
      binding(stored,target);
      var state=metadata.read(stored.get("metadata"));
      if(!KnowledgeTransferMetadata.matches(state,scope.task(),scope.source(),scope.target(),text(src,"id"),"clone","")||text(stored,"parse_status").equals("completed"))return;
      var after=new LinkedHashMap<>(stored);after.put("parse_status","failed");after.put("error_message",Objects.toString(failure.getMessage(),""));
      transfers.checkpoint(stored,after);
    }catch(Exception ignored){/* Go failure bookkeeping is best effort and never replaces the original error. */}
  }
  private static void binding(Map<String,Object> row,JsonNode target) {
    if(row==null||text(row,"id").isEmpty()||number(row,"tenant_id")!=target.path("tenant_id").asLong()||!text(row,"knowledge_base_id").equals(target.path("id").asText()))
      throw new IllegalArgumentException("knowledge binding does not match the transfer scope");
  }
  private static Exception wrap(String prefix,Exception e){return new IllegalStateException(prefix+Objects.toString(e.getMessage(),""),e);}
}
