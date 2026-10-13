package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.model.client.ModelEndpoint;
import com.tencent.weknora.model.client.OpenAIResponses;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpenAIResponsesParityTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode fixtures()throws Exception{return JSON.readTree(Files.readString(Path.of("src/test/java/com/tencent/weknora/model/client/go-responses-fixtures.json")));}
    @Test void actualGoResponsesBuildersAcrossEveryProviderAndThinkingLevel()throws Exception{
        CatalogRuntime catalog=CatalogRuntime.builtins();int count=0;for(JsonNode f:fixtures().path("chat_bodies")){var r=catalog.resolve(new CatalogRuntime.Ref(f.path("provider").asText(),"unknown","https://relay.invalid/v1","KnowledgeQA",JSON.createObjectNode().put("api","openai-responses"),null,0));ModelEndpoint e=new ModelEndpoint("id","unknown",r.remoteModel(),"https://relay.invalid/v1/responses",(ObjectNode)r.settings().path("openai_responses"),r.thinkingLevels(),r.model().path("reasoning").asBoolean(),Map.of(),List.of());OpenAIResponses client=new OpenAIResponses(null,e,Duration.ofSeconds(1),Duration.ofSeconds(1));NativeModelClientsTest.equalJSON(client.request(NativeModelClientsTest.messages(f.path("messages")),(ObjectNode)f.path("options"),true),f.path("body"),f.path("provider")+" "+f.path("level"));count++;}assertThat(count).isEqualTo(189);
    }
    @Test void actualGoResponsesAndTypedSSEOverRealHTTP()throws Exception{
        try(var h=new NativeModelClientsTest.Harness()){int count=0;for(JsonNode f:fixtures().path("wire")){h.fixture=f;h.requests.clear();String id="response-"+(count++);ObjectNode p=(ObjectNode)f.path("parameters").deepCopy();p.put("base_url",h.url()+p.remove("base_suffix").asText());p.put("api_key",h.secrets.encryptLegacyWhenConfigured("synthetic-api-key"));h.seed(id,1,false,"active",f.path("name").asText(),"KnowledgeQA",p);Object result;try(var call=CallContext.timeout(Duration.ofSeconds(3))){var client=h.factory.chat(h.viewer,id);if(f.path("kind").asText().equals("chat"))result=client.chat(call,NativeModelClientsTest.messages(f.path("messages")),(ObjectNode)f.path("options"));else{List<ObjectNode> events=new ArrayList<>();client.stream(call,NativeModelClientsTest.messages(f.path("messages")),(ObjectNode)f.path("options"),events::add);result=events;}}String label=f.path("kind")+" "+f.path("provider")+" "+f.path("name");NativeModelClientsTest.equalJSON(result,f.path("result"),"response "+label);NativeModelClientsTest.equalJSON(h.requests,f.path("requests"),"wire "+label);}assertThat(count).isEqualTo(25);}
    }
}
