package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tencent.weknora.model.client.CallContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Shared Go description behavior for queued and synchronous generation. */
public final class KnowledgeBaseProfileGenerator {
  public static final class Unsupported extends IllegalArgumentException {
    Unsupported(){super("knowledge base type does not support generated descriptions");}
  }
  public static final class ModelMissing extends IllegalArgumentException {
    ModelMissing(){super("no chat model configured for knowledge base description");}
  }
  public record Result(ObjectNode profile,boolean cached){}
  private final KnowledgeBaseProfileRepository repository;
  private final ObjectMapper json;
  private final KnowledgeBaseProfileHandler.Models models;
  private final KnowledgeBaseProfileText text;
  private final Clock clock;
  public KnowledgeBaseProfileGenerator(KnowledgeBaseProfileRepository repository,ObjectMapper json,KnowledgeBaseProfileHandler.Models models,KnowledgeBaseProfileText text,Clock clock){this.repository=repository;this.json=json;this.models=models;this.text=text;this.clock=clock;}
  public Result generate(long owner,JsonNode base,boolean force,String language,CallContext call,Runnable guard,Consumer<ObjectNode> save){
    if(!KnowledgeBaseProfileHandler.eligible(base))throw new Unsupported();
    guard.run();call.check();
    var aggregate=text.aggregate(repository.rows(owner,base.path("id").asText()));
    var existing=base.path("generated_profile");var now=clock.instant();
    if(aggregate.path("stats").path("document_count").asInt()==0){
      var empty=json.createObjectNode().put("aggregate_hash",aggregate.path("hash").asText()).put("status","empty").put("generated_at",now.toString());
      empty.set("stats",aggregate.path("stats"));guard.run();call.check();save.accept(empty);return new Result(empty,false);
    }
    if(!force&&existing.path("status").asText().equals("ready")&&existing.path("aggregate_hash").equals(aggregate.path("hash")))return new Result(((ObjectNode)existing).deepCopy(),true);
    String model=SummaryGenerationText.trim(base.path("profile_config").path("model_id").asText());
    if(model.isEmpty())model=SummaryGenerationText.trim(base.path("summary_model_id").asText());
    try{
      if(model.isEmpty())throw new ModelMissing();
      var messages=new ArrayList<ObjectNode>();text.messages(base,aggregate,language).forEach(m->messages.add((ObjectNode)m));
      com.tencent.weknora.model.client.ChatModel client;
      try{client=models.chat(owner,model);}catch(RuntimeException unavailable){String detail=unavailable instanceof com.tencent.weknora.security.ApiException a&&a.status()==404?"model not found":unavailable.getMessage();throw new IllegalStateException("get chat model "+model+": "+detail,unavailable);}
      var response=client.chat(call,messages,text.options());guard.run();call.check();
      var profile=text.parse(response);profile.set("stats",aggregate.path("stats"));
      profile.put("aggregate_hash",aggregate.path("hash").asText()).put("status","ready").put("model_id",model).put("generated_at",now.toString());
      save.accept(profile);return new Result(profile,false);
    }catch(CancellationException e){throw e;}
    catch(RuntimeException e){
      guard.run();call.check();
      var failed=existing.isObject()?((ObjectNode)existing).deepCopy():json.createObjectNode();
      failed.put("status","failed").put("error",SummaryGenerationText.bound(Objects.requireNonNullElse(e.getMessage(),"knowledge base description failed"),300));failed.set("stats",aggregate.path("stats"));
      try{save.accept(failed);}catch(CancellationException lost){throw lost;}catch(RuntimeException ignored){/* Keep the original generation error when saving failure also fails. */}
      throw e;
    }
  }
}
