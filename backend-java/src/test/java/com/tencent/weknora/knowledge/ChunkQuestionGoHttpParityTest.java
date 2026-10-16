package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.security.KnowledgeBaseWriteAuthorization;
import java.util.UUID;

/** Uses the same complete SQL/model/raw-vector assertions against actual Go public question handlers. */
class ChunkQuestionGoHttpParityTest extends ChunkEditGoHttpParityTest {
  @Override String fixtureResource(){return "/go-chunk-question-http.json";}
  @Override int fixtureCount(){return 130;}
  @Override Object controller(ChunkEditService service,KnowledgeBaseWriteAuthorization writes,DocumentProcessingCompatibilityTest f,org.springframework.core.env.Environment env){return new ChunkQuestionController(new ChunkQuestionService(f.jdbc,json,writes,service),json);}
  @Override JsonNode canonical(JsonNode value){
    if(value.isTextual()){
      String text=value.asText();String suffix=text.startsWith("chunk-")?text.substring(6):text;
      try{if(UUID.fromString(suffix).toString().equals(suffix))return json.getNodeFactory().textNode(text.startsWith("chunk-")?"chunk-<new-question-uuid>":"<new-question-uuid>");}catch(IllegalArgumentException ignored){}
    }
    return super.canonical(value);
  }
}
