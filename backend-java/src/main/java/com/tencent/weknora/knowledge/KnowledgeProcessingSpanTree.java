package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Exact public five-stage tree, including legacy and missing-stage fallback. */
final class KnowledgeProcessingSpanTree {
  static final List<String> STAGES=List.of("docreader","chunking","embedding","multimodal","postprocess");
  record Result(ObjectNode root,String currentStage,ObjectNode failure) {}
  private final ObjectMapper json;
  KnowledgeProcessingSpanTree(ObjectMapper json) {this.json=json;}
  Result build(String knowledge,long attempt,List<ObjectNode> rows,String parseStatus,OffsetDateTime now) {
    Map<String,ObjectNode> nodes=new HashMap<>(),stages=new HashMap<>();
    ObjectNode rootRow=null,failure=null;String current="";
    for(var row:rows) {
      nodes.put(row.path("span_id").asText(),row.deepCopy());
      if(row.path("kind").asText().equals("root")&&rootRow==null)rootRow=row;
      if(row.path("kind").asText().equals("stage"))stages.put(row.path("name").asText(),row);
      if(row.path("status").asText().equals("running")&&row.path("kind").asText().equals("stage")&&current.isEmpty())current=row.path("name").asText();
      if(row.path("status").asText().equals("failed")&&(failure==null||!stall(failure)||stall(row)))failure=row;
    }
    if(current.isEmpty())current=currentStage(rows);
    String synthetic=switch(parseStatus) {case "completed"->"done";case "failed"->"failed";case "cancelled"->"cancelled";default->"pending";};
    ObjectNode root=rootRow==null?placeholder(knowledge,attempt,"knowledge_processing","root",synthetic,now):nodes.get(rootRow.path("span_id").asText());
    for(var row:rows) {
      var node=nodes.get(row.path("span_id").asText());if(node==null||node==root)continue;
      String parent=row.path("parent_span_id").asText();
      child(parent.isEmpty()?root:nodes.getOrDefault(parent,root),node);
    }
    int failed=-1;
    for(int i=0;i<STAGES.size();i++) {var row=stages.get(STAGES.get(i));if(row!=null&&row.path("status").asText().equals("failed")){failed=i;break;}}
    for(int i=0;i<STAGES.size();i++)if(!stages.containsKey(STAGES.get(i))) {
      String status=failed<0?synthetic:i>failed?"cancelled":"skipped";
      child(root,placeholder(knowledge,attempt,STAGES.get(i),"stage",status,now));
    }
    return new Result(root,current,failure);
  }
  private ObjectNode placeholder(String knowledge,long attempt,String name,String kind,String status,OffsetDateTime now) {
    return json.createObjectNode().put("knowledge_id",knowledge).put("attempt",attempt).put("span_id","").put("name",name).put("kind",kind).put("status",status).put("created_at",KnowledgeProcessingSpanRepository.timestamp(now.toString())).put("updated_at",KnowledgeProcessingSpanRepository.timestamp(now.toString()));
  }
  private void child(ObjectNode parent,ObjectNode child) {if(!parent.has("children"))parent.set("children",json.createArrayNode());((ArrayNode)parent.get("children")).add(child);}
  private static boolean stall(ObjectNode row) {return row.path("error_code").asText().equals("TASK_STALLED");}
  private static String currentStage(List<ObjectNode> rows) {
    Map<String,ObjectNode> nodes=new HashMap<>();for(var row:rows)nodes.put(row.path("span_id").asText(),row);
    for(int i=rows.size()-1;i>=0;i--) {
      var row=rows.get(i);if(!row.path("status").asText().equals("running")||row.path("kind").asText().equals("root"))continue;
      for(int depth=0;row!=null&&depth<64;depth++) {if(row.path("kind").asText().equals("stage"))return row.path("name").asText();row=nodes.get(row.path("parent_span_id").asText());}
    }
    return "";
  }
}
