package com.tencent.weknora.knowledge;

import static com.tencent.weknora.knowledge.KnowledgeTransferRepository.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;

/** FAQ typed metadata and the exact, sanitized (not normalized) Go indexing text. */
final class KnowledgeFAQMetadata {
  record Entry(String content,String sourceId,int sourceType,String chunkId,String knowledgeId,
      String knowledgeBaseId,String knowledgeType,String tagId,boolean enabled,boolean recommended) {}
  private final ObjectMapper json;
  private final ObjectNode schema;
  KnowledgeFAQMetadata(ObjectMapper json) {
    this.json=json;schema=KnowledgeMoveJson.schema(json,"FAQChunkMetadata");
    for(String field:List.of("standard_question","answer_strategy","source"))
      KnowledgeMoveJson.field(json,schema,field,"string",field.equals("answer_strategy")?"types.AnswerStrategy":"string");
    KnowledgeMoveJson.field(json,schema,"version","int","int");
    for(String field:List.of("similar_questions","negative_questions","answers"))
      KnowledgeMoveJson.add(json,schema,field,json.createObjectNode().put("kind","slice").put("type","[]string")
          .set("elem",json.createObjectNode().put("kind","string").put("type","string")));
  }
  ObjectNode decode(Object stored)throws IOException {
    String raw=KnowledgeCloneRepository.raw(stored);
    var out=json.createObjectNode();
    if(!raw.isEmpty()) {
      var value=new GoUploadJson(json).decode(raw,schema);
      if(value.isObject())out.setAll((ObjectNode)value);
    }
    return out;
  }
  String strategy(Object stored) {
    try{return normalizedStrategy(decode(stored).path("answer_strategy").asText(""));}
    catch(IOException ignored){return "all";}
  }
  private static String normalizedStrategy(String value){return value.isEmpty()?"all":value;}
  String patch(Object destination,Object source)throws IOException {
    var dst=decode(destination);dst.set("answer_strategy",decode(source).path("answer_strategy"));
    var out=json.createObjectNode().put("standard_question",dst.path("standard_question").asText(""));
    for(String key:List.of("similar_questions","negative_questions","answers"))
      if(dst.path(key).isArray()&&!dst.path(key).isEmpty())out.set(key,dst.get(key));
    if(!dst.path("answer_strategy").asText("").isEmpty())out.put("answer_strategy",dst.path("answer_strategy").asText());
    if(dst.path("version").asLong()!=0)out.put("version",dst.path("version").asLong());
    if(!dst.path("source").asText("").isEmpty())out.put("source",dst.path("source").asText());
    return json.writeValueAsString(out).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026").replace("\u2028","\\u2028").replace("\u2029","\\u2029");
  }
  List<Entry> indexes(JsonNode kb,Map<String,Object> chunk)throws IOException {
    var meta=decode(chunk.get("metadata"));
    String question=KnowledgeCloneRepository.raw(chunk.get("metadata")).isEmpty()?text(chunk,"content"):
        KnowledgeUploadForms.goTrim(meta.path("standard_question").asText(""));
    var similar=sanitize(meta.path("similar_questions"));var answers=sanitize(meta.path("answers"));
    String mode=kb.path("faq_config").path("index_mode").asText("");
    String questionMode=kb.path("faq_config").path("question_index_mode").asText("");
    boolean answer=mode.isEmpty()||mode.equals("question_answer");
    boolean combined=questionMode.isEmpty()||questionMode.equals("combined");
    var out=new ArrayList<Entry>();String id=text(chunk,"id");
    if(combined) {
      var content=new ArrayList<String>();content.add(question);content.addAll(similar);if(answer)content.addAll(answers);
      out.add(entry(chunk,id,String.join("\n",content)));
    } else {
      var questions=new ArrayList<String>();questions.add(question);questions.addAll(similar);
      for(int i=0;i<questions.size();i++) {
        String content=questions.get(i);if(answer)for(String value:answers)content+="\n"+value;
        out.add(entry(chunk,id+(i==0?"":"-"+(i-1)),content));
      }
    }
    return out;
  }
  private static Entry entry(Map<String,Object> row,String source,String content) {
    return new Entry(content,source,0,text(row,"id"),text(row,"knowledge_id"),text(row,"knowledge_base_id"),"faq",text(row,"tag_id"),enabled(row),(number(row,"flags")&1)!=0);
  }
  static boolean enabled(Map<String,Object> row){return row.get("is_enabled")!=null&&Set.of("true","1").contains(row.get("is_enabled").toString());}
  private static List<String> sanitize(JsonNode value) {
    var out=new LinkedHashSet<String>();for(var item:value){String text=KnowledgeUploadForms.goTrim(item.asText(""));if(!text.isEmpty())out.add(text);}return new ArrayList<>(out);
  }
}
