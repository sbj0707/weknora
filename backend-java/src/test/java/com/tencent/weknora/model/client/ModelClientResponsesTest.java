package com.tencent.weknora.model.client;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelClientResponsesTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode fixtures()throws Exception{return JSON.readTree(Files.readString(Path.of("src/test/java/com/tencent/weknora/model/client/go-client-fixtures.json")));}
    static ModelEndpoint endpoint(String url,ObjectNode settings){return new ModelEndpoint("id","model","wire-model",url,settings,JSON.createObjectNode(),false,Map.of("Authorization","Bearer synthetic-api-key"),List.of("synthetic-api-key"));}
    static SafeModelHttp http(){return new SafeModelHttp((uri,ctx)->{try{return new InetAddress[]{InetAddress.getByName("127.0.0.1")};}catch(Exception ex){throw new AssertionError(ex);}});}
    @Test void actualGoIndexedEmbeddingPlacementAndUnicodeBatchFailures()throws Exception{
        int placements=0,batches=0;for(JsonNode f:fixtures().path("placement")){if(f.has("error"))assertThatThrownBy(()->OpenAIEmbedding.place(f.path("data"),f.path("want").asInt())).hasMessage(f.path("error").asText());else{float[][] vectors=OpenAIEmbedding.place(f.path("data"),f.path("want").asInt());assertThat(vectors.length).isEqualTo(f.path("result").size());for(int i=0;i<vectors.length;i++)for(int j=0;j<vectors[i].length;j++)assertThat(vectors[i][j]).isEqualTo((float)f.path("result").get(i).get(j).asDouble());}placements++;}
        for(JsonNode f:fixtures().path("batches")){List<String> items=new ArrayList<>();f.path("items").forEach(v->items.add(v.asText()));if(f.has("error"))assertThatThrownBy(()->ClientBatches.split(items,f.path("fixed").asInt(),f.path("max_items").asInt(),f.path("max_item_runes").asInt(),f.path("max_total_runes").asInt())).hasMessage(f.path("error").asText());else{ArrayNode actual=JSON.createArrayNode();for(var batch:ClientBatches.split(items,f.path("fixed").asInt(),f.path("max_items").asInt(),f.path("max_item_runes").asInt(),f.path("max_total_runes").asInt())){ObjectNode node=JSON.createObjectNode().put("Start",batch.start());node.set("Items",JSON.valueToTree(batch.items()));actual.add(node);}assertThat(actual).isEqualTo(f.path("result"));}batches++;}assertThat(placements).isEqualTo(9);assertThat(batches).isEqualTo(15);
    }
    @Test void HTTPAndDecodeErrorsAreNotRetriedAndSecretsAreRedacted()throws Exception{
        AtomicInteger requests=new AtomicInteger();AtomicReference<String> payload=new AtomicReference<>("synthetic-api-key");AtomicInteger status=new AtomicInteger(500);
        try(Server server=new Server(x->{requests.incrementAndGet();reply(x,status.get(),payload.get());});SafeModelHttp http=http();var call=CallContext.timeout(Duration.ofSeconds(3))){
            OpenAIEmbedding client=new OpenAIEmbedding(http,endpoint(server.url(),JSON.createObjectNode()),0,false);
            assertThatThrownBy(()->client.embed(call,List.of("input"),false)).hasMessageContaining("status 500").hasMessageContaining("[redacted]").hasMessageNotContaining("synthetic-api-key");assertThat(requests.get()).isEqualTo(1);
            status.set(200);payload.set("not JSON");assertThatThrownBy(()->client.embed(call,List.of("input"),false)).hasMessage("decode response: invalid JSON");assertThat(requests.get()).isEqualTo(2);
            payload.set("{\"data\":[{\"index\":3,\"embedding\":[1]}]}");assertThatThrownBy(()->client.embed(call,List.of("input"),false)).hasMessage("embedding index 3 out of range for 1 inputs");assertThat(requests.get()).isEqualTo(3);
            payload.set("{\"data\":[{\"index\":0.5,\"embedding\":[1]}]}");assertThatThrownBy(()->client.embed(call,List.of("input"),false)).hasMessageContaining("index must be an integer");assertThat(requests.get()).isEqualTo(4);
        }
    }
    @Test void rerankBatchesRespectConcurrencyOffsetsAndStableSorting()throws Exception{
        AtomicInteger inFlight=new AtomicInteger(),peak=new AtomicInteger(),requests=new AtomicInteger();
        try(Server server=new Server(x->{requests.incrementAndGet();int current=inFlight.incrementAndGet();peak.accumulateAndGet(current,Math::max);JsonNode body=JSON.readTree(x.getRequestBody());try{Thread.sleep(40);}catch(InterruptedException ex){Thread.currentThread().interrupt();}ArrayNode data=JSON.createArrayNode();for(int i=body.path("documents").size()-1;i>=0;i--)data.add(JSON.createObjectNode().put("index",i).put("score",0));inFlight.decrementAndGet();reply(x,200,JSON.createObjectNode().set("results",data).toString());});SafeModelHttp http=http();var call=CallContext.timeout(Duration.ofSeconds(3))){
            ObjectNode settings=JSON.createObjectNode().put("max_documents",2).put("max_concurrency",2).put("score_scale","logit");CohereRerank client=new CohereRerank(http,endpoint(server.url(),settings));List<RerankModel.Result> result=client.rerank(call,"中😀",List.of("a","b","c","d","e"));assertThat(result).extracting(RerankModel.Result::index).containsExactly(1,0,3,2,4);assertThat(result).allMatch(r->r.relevanceScore()==0.5);assertThat(result).extracting(RerankModel.Result::text).containsExactly("b","a","d","c","e");assertThat(requests.get()).isEqualTo(3);assertThat(peak.get()).isEqualTo(2);
        }
    }
    @Test void brokenSSEEmitsTerminalErrorAndCancellationNeverCompletesAnAnswer()throws Exception{
        String payload="data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: invalid JSON\n";
        try(Server server=new Server(x->reply(x,200,payload));SafeModelHttp http=http();var call=CallContext.timeout(Duration.ofSeconds(2))){
            OpenAIChat client=new OpenAIChat(http,endpoint(server.url(),JSON.createObjectNode()),Duration.ofSeconds(1),Duration.ofSeconds(1));List<ObjectNode> events=new ArrayList<>();client.stream(call,List.of(JSON.createObjectNode().put("role","user").put("content","question")),null,events::add);assertThat(events).hasSize(2);assertThat(events.getFirst().path("content").asText()).isEqualTo("partial");assertThat(events.getLast().path("response_type").asText()).isEqualTo("error");assertThat(events.getLast().path("finish_reason").asText()).isEqualTo("incomplete");assertThat(events.getLast().path("done").asBoolean()).isTrue();
            List<ObjectNode> cancelled=new ArrayList<>();assertThatThrownBy(()->client.stream(call,List.of(JSON.createObjectNode().put("role","user").put("content","question")),null,event->{cancelled.add(event);call.cancel();})).isInstanceOf(CancellationException.class);assertThat(cancelled).hasSize(1);assertThat(cancelled.getFirst().path("done").asBoolean()).isFalse();
        }
    }
    static void reply(HttpExchange x,int status,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}x.close();}
    static final class Server implements AutoCloseable{final HttpServer server;final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();Server(HttpHandler handler)throws IOException{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",handler);server.setExecutor(executor);server.start();}String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/";}@Override public void close(){server.stop(0);executor.shutdownNow();}}
}
