package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Ordinary KB clone: one whole-batch preflight, synchronous removals, then resumable documents. */
final class KnowledgeCloneExecution {
  @FunctionalInterface interface Progress { void update(int done,int total); }
  @FunctionalInterface interface Removals {
    void delete(KnowledgeTransferRepository.Scope scope,JsonNode target,List<String> ids)throws Exception;
  }
  private final KnowledgeClonePlanner planner;
  private final KnowledgeCloneDocument documents;
  private final Removals removals;
  KnowledgeCloneExecution(KnowledgeClonePlanner planner,KnowledgeCloneDocument documents,Removals removals) {
    this.planner=planner;this.documents=documents;this.removals=removals;
  }
  void execute(KnowledgeTransferRepository.Scope scope,JsonNode source,JsonNode target,Progress progress)throws Exception {
    var plan=planner.plan(scope,source,target);
    int total=plan.add().size()+plan.remove().size(),done=0;
    if(progress!=null)progress.update(done,total);
    if(!plan.remove().isEmpty()) {
      removals.delete(scope,target,plan.remove());
      done+=plan.remove().size();
      if(progress!=null)progress.update(done,total);
    }
    for(var row:plan.add()) {
      documents.copy(scope,source,target,row);
      done++;
      if(progress!=null)progress.update(done,total);
    }
  }
}
