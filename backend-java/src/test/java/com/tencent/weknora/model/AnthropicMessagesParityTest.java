package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.AnthropicMessages;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.model.client.ChatModel;
import com.tencent.weknora.model.client.ModelEndpoint;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnthropicMessagesParityTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode fixtures()throws Exception{return JSON.readTree(Files.readString(Path.of(System.getProperty("weknora.anthropic.fixture","src/test/java/com/tencent/weknora/model/client/go-anthropic.json"))));}
    @Test void actualGoBuildersAcrossCatalogProvidersAndThinkingLevels()throws Exception{
        int count=0;CatalogRuntime catalog=CatalogRuntime.builtins();for(JsonNode f:fixtures().path("bodies")){var r=catalog.resolve(new CatalogRuntime.Ref(f.path("provider").asText(),f.path("name").asText(),"https://relay.invalid/v1","KnowledgeQA",JSON.createObjectNode().put("api","anthropic-messages"),null,0));ModelEndpoint e=new ModelEndpoint("id",f.path("name").asText(),r.remoteModel(),"https://relay.invalid/v1/messages",(ObjectNode)r.settings().path("anthropic_messages"),r.thinkingLevels(),r.model().path("reasoning").asBoolean(),Map.of(),List.of());AnthropicMessages client=new AnthropicMessages(null,e,Duration.ofSeconds(1),Duration.ofSeconds(1));NativeModelClientsTest.equalJSON(client.request(NativeModelClientsTest.messages(f.path("messages")),f.path("options").isNull()?null:(ObjectNode)f.path("options"),true),f.path("body"),"Anthropic builder "+count+" "+f.path("provider")+" "+f.path("level"));count++;}assertThat(count).isEqualTo(558);
    }
    @Test void actualGoAnthropicFactoryHTTPChatAndTypedSSE()throws Exception{
        int count=0;try(var h=new NativeModelClientsTest.Harness()){for(JsonNode f:fixtures().path("wire")){h.fixture=f;h.requests.clear();String id="anthropic-"+count;ObjectNode p=(ObjectNode)f.path("parameters").deepCopy();p.put("base_url",h.url()+p.remove("base_suffix").asText());p.put("api_key",h.secrets.encryptLegacyWhenConfigured("synthetic-api-key"));h.seed(id,1,false,"active",f.path("name").asText(),"KnowledgeQA",p);ObjectNode options=f.path("options").isNull()?null:(ObjectNode)f.path("options");Object result;try(var call=CallContext.timeout(Duration.ofSeconds(3))){ChatModel client=h.factory.chat(h.viewer,id);assertThat(client).isInstanceOf(AnthropicMessages.class);if(f.has("error")){assertThatThrownBy(()->client.chat(call,NativeModelClientsTest.messages(f.path("messages")),options)).as("case %s",count).hasMessage(f.path("error").asText());result=null;}else if(f.path("kind").asText().equals("chat"))result=client.chat(call,NativeModelClientsTest.messages(f.path("messages")),options);else{List<ObjectNode> events=new ArrayList<>();client.stream(call,NativeModelClientsTest.messages(f.path("messages")),options,events::add);result=events;}if(!f.has("error"))NativeModelClientsTest.equalJSON(result,f.path("result"),"Anthropic wire result "+count);NativeModelClientsTest.equalJSON(h.requests,f.path("requests"),"Anthropic wire requests "+count);}count++;}}assertThat(count).isEqualTo(82);
    }
}
