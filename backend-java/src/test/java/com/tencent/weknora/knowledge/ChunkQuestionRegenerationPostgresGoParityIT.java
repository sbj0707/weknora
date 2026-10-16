package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.security.KnowledgeBaseWriteAuthorization;
import java.util.List;

class ChunkQuestionRegenerationPostgresGoParityIT extends ChunkEditPostgresGoParityIT {
  final ChunkRegenerationParitySupport support=new ChunkRegenerationParitySupport(json,true);
  @Override int fixtureCount(){return 81;}
  @Override boolean refreshVariant(String variant){return false;}
  @Override void extraVariants(DocumentProcessingCompatibilityTest f,String variant){super.extraVariants(f,variant);support.configure(f,variant);}
  @Override void embeddingMutation(DocumentProcessingCompatibilityTest f,String variant){support.embeddingMutation(f);}
  @Override boolean embeddingFails(String variant){return variant.equals("provider-error")||variant.equals("embedding-provider-error");}
  @Override void modelServer(DocumentProcessingCompatibilityTest f,String variant,com.sun.net.httpserver.HttpServer server,List<JsonNode> requests){support.modelServer(f,server,requests);}
  @Override void modelRecord(DocumentProcessingCompatibilityTest f,ObjectNode params,String variant){support.modelRecord(f,params);}
  @Override Object controller(ChunkEditService service,KnowledgeBaseWriteAuthorization writes,DocumentProcessingCompatibilityTest f,org.springframework.core.env.Environment env)throws Exception{return support.controller(service,writes,f,env);}
  @Override JsonNode canonical(JsonNode value){return support.canonical(value,super::canonical);}
}
