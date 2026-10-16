package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.security.AuthContext;
import java.util.*;

/** Native graph query stages; the session/SSE coordinator remains a separate integration. */
public final class GraphQuerySearch {
  @FunctionalInterface public interface Models { ChatModel chat(AuthContext caller, String model); }
  public record Request(String query, String rewrite, String intent, boolean webSearch, String model,
      List<GraphQueryScope.Target> targets, String agent, String source) {
    public Request { query = Objects.requireNonNullElse(query, ""); rewrite = Objects.requireNonNullElse(rewrite, ""); intent = Objects.requireNonNullElse(intent, ""); targets = targets == null ? List.of() : List.copyOf(targets); }
    public boolean needsRetrieval() {
      return Set.of("", "kb_search", "clarification", "summarize").contains(intent)
          || intent.equals("web_search") && webSearch;
    }
  }
  public record Entities(List<String> names, List<GraphQueryScope.Target> targets, boolean extracted) {
    public Entities(List<String> names,List<GraphQueryScope.Target> targets){this(names,targets,false);}
    public Entities { names = List.copyOf(names); targets = List.copyOf(targets); }
  }
  public record Found(GraphExtractionText.Graph graph, List<ObjectNode> results, boolean searchNothing) {
    public Found(GraphExtractionText.Graph graph,List<ObjectNode> results){this(graph,results,false);}
    public Found { results = results == null ? null : List.copyOf(results); }
  }
  public record Hydrated(List<ObjectNode> results,boolean successful){public Hydrated{results=results==null?null:List.copyOf(results);}}
  @FunctionalInterface public interface Hydration {
    Hydrated hydrate(AuthContext caller, Request request, List<GraphQueryScope.Target> targets,
        List<String> chunkIds, List<ObjectNode> existing, CallContext call);
  }
  private final GraphExtractionStore graph;
  private final GraphQueryScope scope;
  private final Models models;
  private final GraphQueryText text;
  private final Hydration hydration;

  public GraphQuerySearch(GraphExtractionStore graph, GraphQueryScope scope, Models models,
      GraphQueryText text, Hydration hydration) {
    this.graph = graph; this.scope = scope; this.models = models; this.text = text; this.hydration = hydration;
  }

  public Entities extract(AuthContext caller, Request request, CallContext call) {
    call.check();
    if (!graph.enabled() || !request.needsRetrieval()) return new Entities(List.of(), List.of());
    var targets = scope.enabled(caller, request.targets(), request.agent(), request.source());
    if (targets.isEmpty()) return new Entities(List.of(), targets);
    String rewrite = SummaryGenerationText.trim(Objects.requireNonNullElse(request.rewrite(), ""));
    String query = rewrite.isEmpty() ? request.query() : rewrite;
    try {
      var model = models.chat(caller, request.model());
      var response = model.chat(call, text.messages(query), text.options());
      call.check();
      // A provider response cannot preserve a revoked grant or change a target owner.
      var stillEnabled = scope.enabled(caller, targets, request.agent(), request.source());
      if (stillEnabled.isEmpty()) return new Entities(List.of(), List.of());
      return new Entities(text.entities(response.path("content").asText()), stillEnabled,true);
    } catch (RuntimeException unavailable) {
      call.check();
      return new Entities(List.of(), targets); // Go's extraction/model errors degrade to chunk-only retrieval.
    }
  }

  public Found search(AuthContext caller, Request request, Entities entities,
      List<ObjectNode> existing, CallContext call) {
    call.check();
    if (!graph.enabled() || entities.names().isEmpty()) return new Found(null, existing);
    var targets = scope.enabled(caller, entities.targets(), request.agent(), request.source());
    if (targets.isEmpty()) return new Found(null, existing);
    var namespaces = new TreeMap<String, GraphQueryScope.Target>();
    var whole = new HashSet<String>(); targets.stream().filter(GraphQueryScope.Target::wholeBase).forEach(t -> whole.add(t.kb()));
    for (var target : targets) {
      if (target.wholeBase()) namespaces.put(target.kb() + "\u0000", target);
      else if (!whole.contains(target.kb())) for (var doc : target.documents())
        namespaces.put(target.kb() + "\u0000" + doc, target);
    }
    var nodes = new ArrayList<GraphExtractionText.Node>();
    var relations = new ArrayList<GraphExtractionText.Relation>();
    for (var entry : namespaces.entrySet()) {
      var target = entry.getValue();
      String doc = entry.getKey().substring(entry.getKey().indexOf('\u0000') + 1);
      try {
        var found = graph.search(target.kb(), doc, entities.names(), call,
            () -> scope.check(caller, target, request.agent(), request.source()));
        if (found != null) { nodes.addAll(found.nodes()); relations.addAll(found.relations()); }
      } catch (RuntimeException unavailable) { call.check(); }
    }
    var seen = new HashSet<String>();
    if(existing!=null)existing.forEach(row -> seen.add(row.path("id").asText()));
    var candidates = new ArrayList<String>();
    for (var node : nodes) for (var chunk : node.chunks()) {
      if (candidates.size() < 30 && seen.add(chunk)) candidates.add(chunk);
    }
    var data = new GraphExtractionText.Graph(nodes, relations);
    if (candidates.isEmpty()) return new Found(data, existing);
    var hydrated=hydration.hydrate(caller,request,targets,candidates,existing,call);
    return new Found(data,hydrated.results(),hydrated.successful()&&(hydrated.results()==null||hydrated.results().isEmpty()));
  }
}
