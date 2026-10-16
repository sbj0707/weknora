package com.tencent.weknora.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import java.util.*;
import java.util.function.Function;

/** Actual model server and source-ID correspondence; does not replace production model/index code. */
final class ChunkRegenerationParitySupport {
  final ObjectMapper json;final boolean pg;String variant;final Map<String,String> ids=new HashMap<>();
  ChunkRegenerationParitySupport(ObjectMapper json,boolean pg){this.json=json;this.pg=pg;}
  void configure(DocumentProcessingCompatibilityTest f,String variant){
    this.variant=variant;ids.clear();if(!variant.equals("no-summary-model"))f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='chat' WHERE id='kb'");
    String config=switch(variant){case "count-one"->"{\"question_count\":1}";case "count-max"->"{\"question_count\":25}";case "count-negative"->"{\"question_count\":-2}";case "custom-instructions"->"{\"question_count\":2,\"custom_instructions\":\"Only objective questions 中文\"}";default->null;};
    if(config!=null)f.jdbc.update("UPDATE knowledge_bases SET question_generation_config="+(pg?"?::jsonb":"?")+" WHERE id='kb'",pg?config:config.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    if(variant.equals("empty-content"))f.jdbc.update("UPDATE chunks SET content='' WHERE id='chunk'");
    if(variant.equals("overrides-count")){String value="{\"process_overrides\":{\"question_generation_config\":{\"question_count\":2}}}";f.jdbc.update("UPDATE knowledges SET metadata="+(pg?"?::jsonb":"?")+" WHERE id='doc'",pg?value:value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    if(variant.startsWith("neighbor-")){f.jdbc.update("UPDATE chunks SET pre_chunk_id='other',next_chunk_id='foreign' WHERE id='chunk'");f.jdbc.update("UPDATE chunks SET knowledge_id='doc',tenant_id=1 WHERE id IN ('other','foreign')");switch(variant){case "neighbor-foreign-doc"->f.jdbc.update("UPDATE chunks SET knowledge_id='other' WHERE id='other'");case "neighbor-foreign-owner"->f.jdbc.update("UPDATE chunks SET tenant_id=2 WHERE id='other'");case "neighbor-foreign-kb"->f.jdbc.update("UPDATE chunks SET knowledge_base_id='other-kb' WHERE id='other'");}}
  }
  void modelRecord(DocumentProcessingCompatibilityTest f,ObjectNode params){if(!variant.equals("chat-model-missing"))f.jdbc.update("INSERT INTO models(id,tenant_id,name,type,source,parameters,is_builtin,status) VALUES('chat',1,'unknown','KnowledgeQA','remote',?,false,'active')",params.toString());}
  void modelServer(DocumentProcessingCompatibilityTest f,com.sun.net.httpserver.HttpServer server,List<JsonNode> requests){server.createContext("/v1/chat/completions",exchange->{var input=json.readTree(exchange.getRequestBody());requests.add(json.createObjectNode().put("path",exchange.getRequestURI().getPath()).set("body",input));
    switch(variant){
      case "edit-during-provider","chat-edit"->f.jdbc.update("UPDATE chunks SET content='Successor edit',content_revision=5"+(variant.equals("edit-during-provider")?",last_editor_id='successor',index_status='processing'":"")+" WHERE id='chunk'");
      case "metadata-during-provider","chat-metadata"->f.jdbc.update("UPDATE chunks SET metadata='{\"generated_questions\":[{\"id\":\"successor-q\",\"question\":\"Successor question 中文\"}]}' WHERE id='chunk'");
      case "doc-owner-during-provider","chat-owner-change"->f.jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id='doc'");
      case "kb-owner-during-provider","chat-kb-owner-change"->f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'");
      case "chunk-owner-during-provider","chat-chunk-owner-change"->f.jdbc.update("UPDATE chunks SET tenant_id=2 WHERE id='chunk'");
      case "chat-title"->f.jdbc.update("UPDATE knowledges SET title='Successor title' WHERE id='doc'");
      case "chat-embedding-model-change"->f.jdbc.update("UPDATE knowledge_bases SET embedding_model_id='replacement' WHERE id='kb'");
      case "chat-summary-model"->f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='replacement' WHERE id='kb'");
      case "chat-config"->changeConfig(f);
      case "chat-cancel"->f.jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE id='doc'");
    }
    byte[] bytes;int status=200;
    if(Set.of("chat-error","provider-error").contains(variant)){status=400;String message=variant.equals("chat-error")?"controlled regeneration chat failure":"controlled edit embedding failure";bytes=json.writeValueAsBytes(Map.of("error",Map.of("message",message)));}
    else{String content=variant.equals("chat-empty")?"1. a\n *  \n":"1. First question 中文\n2. Second question 中文\n* Third question 中文\n4. Fourth question 中文";bytes=json.writeValueAsBytes(Map.of("id","controlled","model","unknown","choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",content),"finish_reason","stop"))));}
    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}exchange.close();});}
  private void changeConfig(DocumentProcessingCompatibilityTest f){String value="{\"question_count\":2}";f.jdbc.update("UPDATE knowledge_bases SET question_generation_config="+(pg?"?::jsonb":"?")+" WHERE id='kb'",pg?value:value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  void embeddingMutation(DocumentProcessingCompatibilityTest f){switch(variant){
    case "embedding-edit"->f.jdbc.update("UPDATE chunks SET content='Embedding successor',content_revision=5 WHERE id='chunk'");
    case "embedding-disable"->f.jdbc.update("UPDATE chunks SET is_enabled=false,content_revision=5 WHERE id='chunk'");
    case "embedding-metadata"->f.jdbc.update("UPDATE chunks SET metadata='{\"generated_questions\":[{\"id\":\"successor-q\",\"question\":\"Successor question 中文\"}]}' WHERE id='chunk'");
    case "embedding-doc-owner"->f.jdbc.update("UPDATE knowledges SET tenant_id=2 WHERE id='doc'");
    case "embedding-kb-owner"->f.jdbc.update("UPDATE knowledge_bases SET tenant_id=2 WHERE id='kb'");
    case "embedding-chunk-owner"->f.jdbc.update("UPDATE chunks SET tenant_id=2 WHERE id='chunk'");
    case "embedding-title"->f.jdbc.update("UPDATE knowledges SET title='Embedding successor title' WHERE id='doc'");
    case "embedding-summary-model"->f.jdbc.update("UPDATE knowledge_bases SET summary_model_id='replacement' WHERE id='kb'");
    case "embedding-model"->f.jdbc.update("UPDATE knowledge_bases SET embedding_model_id='replacement' WHERE id='kb'");
    case "embedding-config"->changeConfig(f);
    case "embedding-cancel"->f.jdbc.update("UPDATE knowledges SET parse_status='cancelled' WHERE id='doc'");
  }}
  Object controller(ChunkEditService service,KnowledgeBaseWriteAuthorization writes,DocumentProcessingCompatibilityTest f,org.springframework.core.env.Environment env)throws Exception{
    var models=new com.tencent.weknora.model.ModelClientFactory(f.jdbc,json,new GoSecretCodec(""),new com.tencent.weknora.model.ModelCatalogRuntime(f.jdbc,"","/nonexistent"),new com.tencent.weknora.model.ModelEgressPolicy(f.jdbc,json,env),env);var questions=new ChunkQuestionService(f.jdbc,json,writes,service);var text=new QuestionGenerationText(json,variant.equals("prompt-missing")?"  ":null);
    return new ChunkQuestionRegenerationController(questions,new ChunkQuestionRegenerationService(f.jdbc,json,service,questions,models,text),new KnowledgeProcessingLanguage(env));
  }
  JsonNode canonical(JsonNode value,Function<JsonNode,JsonNode> parent){
    if(value.isObject()&&value.has("question")&&value.has("id")){String id=value.path("id").asText();try{assertThat(UUID.fromString(id).toString()).isEqualTo(id);String identity="<question:"+value.path("question").asText()+">";String previous=ids.putIfAbsent(id,identity);assertThat(previous==null||previous.equals(identity)).as("one UUID cannot identify different questions").isTrue();}catch(IllegalArgumentException oldQuestion){}}
    if(value.isTextual()){String raw=value.asText();if(ids.containsKey(raw))return new TextNode(ids.get(raw));if(raw.startsWith("chunk-")&&ids.containsKey(raw.substring(6)))return new TextNode("chunk-"+ids.get(raw.substring(6)));}
    JsonNode out=parent.apply(value);if(out.isArray()&&!out.isEmpty()&&out.get(0).isObject()&&out.get(0).has("source_id")){var rows=new ArrayList<JsonNode>();out.forEach(rows::add);rows.sort(Comparator.comparing(n->n.path("source_id").asText()+":"+n.path("source_type").asText()));return json.valueToTree(rows);}return out;
  }
}
