package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.retrieval.GraphQueryEvidence;
import com.tencent.weknora.security.*;
import com.tencent.weknora.sourceloc.SourceLocatorIndex;
import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;

/** SQL hydration never inherits ownership from a Neo4j chunk reference. */
public final class GraphQueryHydration implements GraphQuerySearch.Hydration {
  private final JdbcTemplate jdbc; private final ObjectMapper json; private final GraphQueryScope scopes;
  private final GoStoredStructDecoder locators; private final GraphExtractionText values;
  public GraphQueryHydration(JdbcTemplate jdbc, ObjectMapper json, GraphQueryScope scopes) {
    this.jdbc = jdbc; this.json = json; this.scopes = scopes; values = new GraphExtractionText(json, "");
    try (var in = getClass().getResourceAsStream("/knowledge-read-schema.json")) {
      locators = new GoStoredStructDecoder(json, json.readTree(Objects.requireNonNull(in)).path("locators"));
    } catch (IOException invalid) { throw new UncheckedIOException(invalid); }
  }
  @Override public GraphQuerySearch.Hydrated hydrate(AuthContext caller, GraphQuerySearch.Request request,
      List<GraphQueryScope.Target> targets, List<String> ids, List<ObjectNode> existing, CallContext call) {
    call.check(); if (ids.isEmpty()) return new GraphQuerySearch.Hydrated(existing,false);
    try {
      targets = scopes.enabled(caller, targets, request.agent(), request.source());
      if (targets.isEmpty()) return new GraphQuerySearch.Hydrated(existing,true);
      var chunks = rows("SELECT * FROM chunks WHERE deleted_at IS NULL AND id IN (" + placeholders(ids.size()) + ") ORDER BY id", ids, call);
      var docIds = chunks.stream().map(c -> string(c, "knowledge_id")).distinct().toList();
      var documents = new HashMap<String, Map<String, Object>>();
      if (!docIds.isEmpty()) for (var row : rows("SELECT * FROM knowledges WHERE deleted_at IS NULL AND id IN (" + placeholders(docIds.size()) + ")", docIds, call)) documents.put(string(row, "id"), row);
      var results = new ArrayList<ObjectNode>(); var authorizedParents = new HashMap<String, Map<String,Object>>();
      for (var chunk : chunks) {
        var doc = documents.get(string(chunk, "knowledge_id"));
        if (doc == null || !bool(chunk, "is_enabled") || !allowed(targets, chunk, doc)) continue;
        results.add(project(chunk, doc)); authorizedParents.put(string(chunk, "id"), chunk);
      }
      enrich(results, authorizedParents, call);
      // Recheck after SQL/enrichment before releasing any newly hydrated content.
      targets = scopes.enabled(caller, targets, request.agent(), request.source());
      var output = existing==null?new ArrayList<ObjectNode>():new ArrayList<ObjectNode>(existing);
      for (var result : results) {
        var chunk = authorizedParents.get(result.path("id").asText());
        var doc = documents.get(string(chunk, "knowledge_id"));
        if (allowed(targets, chunk, doc)) output.add(result);
      }
      call.check(); var unique=GraphQueryEvidence.deduplicate(output);return new GraphQuerySearch.Hydrated(unique.isEmpty()?null:unique,true);
    } catch (RuntimeException unavailable) { call.check(); return new GraphQuerySearch.Hydrated(existing,false); }
  }
  private boolean allowed(List<GraphQueryScope.Target> targets, Map<String,Object> chunk, Map<String,Object> doc) {
    var c = json.valueToTree(chunk); var d = json.valueToTree(doc);
    return targets.stream().anyMatch(t -> t.allows((ObjectNode)c, (ObjectNode)d));
  }
  // Shared projection helpers require their caller to bind authoritative SQL rows first.
  ObjectNode project(Map<String,Object> chunk, Map<String,Object> document) {
    var result = json.createObjectNode();
    for (var key : List.of("id", "content", "knowledge_id", "chunk_type", "parent_chunk_id", "image_info")) result.put(key, string(chunk, key));
    for (var key : List.of("chunk_index", "start_at", "end_at")) result.put(key, number(chunk, key));
    result.put("seq", number(chunk, "chunk_index")).put("score", 0.0).put("match_type", 6).putNull("sub_chunk_id");
    result.put("knowledge_title", string(document, "title")).put("knowledge_filename", string(document, "file_name"))
        .put("knowledge_source", string(document, "source")).put("knowledge_channel", string(document, "channel"));
    result.set("metadata", metadata(string(document, "metadata")));
    if (!string(document, "knowledge_base_id").isEmpty()) result.put("knowledge_base_id", string(document, "knowledge_base_id"));
    String raw = string(chunk, "metadata"); if (!raw.isEmpty()) result.set("chunk_metadata", parse(raw));
    raw = string(chunk, "source_locators"); if (!raw.isEmpty()) {
      var sources = locators.project(raw); if (!sources.isNull() && !sources.isEmpty()) result.set("source_locators", sources);
    }
    return result;
  }
  private JsonNode metadata(String raw) {
    var out = json.createObjectNode(); if (raw.isEmpty()) return out;
    try {
      var decoded = values.parseNumbersAsGoFloat64(raw); if (decoded == null || decoded.isNull()) return out;
      if (!decoded.isObject()) return NullNode.instance;
      decoded.fields().forEachRemaining(e -> out.put(e.getKey(), GraphExtractionText.goValue(e.getValue()))); return out;
    } catch (Exception invalid) { return NullNode.instance; }
  }
  private JsonNode parse(String raw) { try { return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw); } catch (IOException e) { throw new UncheckedIOException(e); } }
  void enrich(List<ObjectNode> results, Map<String,Map<String,Object>> parents, CallContext call) {
    var selected = new ArrayList<String>();
    for (var r : results) if (r.path("image_info").asText().isEmpty() || scanned(r.path("source_locators"))) selected.add(r.path("id").asText());
    if (selected.isEmpty()) return;
    var images = new HashMap<String, SortedMap<String,ObjectNode>>();
    var quotes = new HashMap<String,Map<Integer,String>>();
    var textParents = new HashMap<String,Map<String,Object>>();
    for (var child : children(selected, parents, call)) {
      String kind = string(child,"chunk_type");
      if (kind.equals("text")) textParents.put(string(child,"id"),child);
      else if (kind.equals("image_ocr") || kind.equals("image_caption")) addEvidence(string(child,"parent_chunk_id"),child,images,quotes);
    }
    for (var child : children(new ArrayList<>(textParents.keySet()),textParents,call)) {
      String kind = string(child,"chunk_type"); if (!kind.equals("image_ocr") && !kind.equals("image_caption")) continue;
      addEvidence(string(textParents.get(string(child,"parent_chunk_id")),"parent_chunk_id"),child,images,quotes);
    }
    for (var r : results) {
      String id = r.path("id").asText();
      if (r.path("image_info").asText().isEmpty() && images.containsKey(id)) r.put("image_info",compact(json.valueToTree(images.get(id).values())));
      if (quotes.containsKey(id) && r.path("source_locators").isArray()) for (var loc : r.path("source_locators"))
        if (loc.path("type").asText().equals("pdf") && loc.path("bbox").isEmpty() && weak(loc.path("quote").asText())) {
          var quote = quotes.get(id).get(loc.path("page").asInt()); if (quote != null) ((ObjectNode)loc).put("quote",quote);
        }
    }
  }
  private List<Map<String,Object>> children(List<String> ids, Map<String,Map<String,Object>> parents, CallContext call) {
    if (ids.isEmpty()) return List.of();
    try {
      return rows("SELECT * FROM chunks WHERE deleted_at IS NULL AND parent_chunk_id IN ("+placeholders(ids.size())+") ORDER BY id",ids,call).stream().filter(c -> {
        var p=parents.get(string(c,"parent_chunk_id")); return p!=null && bool(c,"is_enabled") && number(c,"tenant_id")==number(p,"tenant_id")
            && string(c,"knowledge_base_id").equals(string(p,"knowledge_base_id")) && string(c,"knowledge_id").equals(string(p,"knowledge_id"));
      }).toList();
    } catch (RuntimeException unavailable) {call.check();return List.of();}
  }
  private void addEvidence(String parent, Map<String,Object> child, Map<String,SortedMap<String,ObjectNode>> images,Map<String,Map<Integer,String>> quotes) {
    for (var image : GraphQueryEvidence.images(string(child,"image_info"),json)) {
      String key=image.path("url").asText();if(key.isEmpty())key=image.path("original_url").asText();if(key.isEmpty())continue;
      var map=images.computeIfAbsent(parent,k->new TreeMap<>());var prior=map.putIfAbsent(key,image);
      if(prior!=null)for(var field:List.of("ocr_text","caption"))if(!image.path(field).asText().isEmpty())prior.put(field,image.path(field).asText());
    }
    String quote=SourceLocatorIndex.cleanQuote(string(child,"content"));String raw=string(child,"source_locators");if(quote.isEmpty()||raw.isEmpty())return;
    for(var loc:locators.project(raw))if(loc.path("type").asText().equals("pdf")&&loc.path("page").asInt()>0){var pages=quotes.computeIfAbsent(parent,k->new HashMap<>());int page=loc.path("page").asInt();if(!pages.containsKey(page)||string(child,"chunk_type").equals("image_ocr"))pages.put(page,quote);}
  }
  private String compact(JsonNode value) {
    if (value.isFloatingPointNumber()) {
      double number = value.doubleValue();
      String shortest = GraphExtractionText.goFloat64(number);
      if (number == 0) return shortest; // Preserve the sign of zero decoded into Go float64.
      var decimal = new java.math.BigDecimal(shortest).stripTrailingZeros();
      int exponent = decimal.precision() - decimal.scale() - 1;
      // encoding/json uses different decimal/scientific cutoffs from fmt %v.
      if (exponent >= -6 && exponent < 21) return decimal.toPlainString();
      int split = shortest.indexOf('e');
      return shortest.substring(0, split + 1) + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
    }
    if (value.isObject()) {
      var fields = new ArrayList<String>();
      value.fields().forEachRemaining(e -> fields.add(compact(TextNode.valueOf(e.getKey())) + ":" + compact(e.getValue())));
      return "{" + String.join(",", fields) + "}";
    }
    if (value.isArray()) { var elements = new ArrayList<String>(); value.forEach(v -> elements.add(compact(v))); return "[" + String.join(",", elements) + "]"; }
    try { return json.writeValueAsString(value).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026").replace("\u2028","\\u2028").replace("\u2029","\\u2029"); }
    catch (IOException e) { throw new UncheckedIOException(e); }
  }
  private static boolean weak(String value){String v=SummaryGenerationText.trim(value);return v.isEmpty()||v.startsWith("![");}
  private static boolean scanned(JsonNode sources){if(!sources.isArray()||sources.isEmpty())return false;for(var loc:sources)if(!loc.path("type").asText().equals("pdf")||loc.path("page").asInt()<=0||!loc.path("bbox").isEmpty()||!weak(loc.path("quote").asText()))return false;return true;}
  private List<Map<String,Object>> rows(String sql,List<String> args,CallContext call){call.check();return jdbc.query(sql,p->{for(int i=0;i<args.size();i++)p.setString(i+1,args.get(i));if(call.deadline()!=null)p.setQueryTimeout((int)Math.max(1,Math.min(Integer.MAX_VALUE,Duration.between(Instant.now(),call.deadline()).toSeconds()+1)));},new ColumnMapRowMapper());}
  private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
  private static String string(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):Objects.toString(value,"");}
  private static long number(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof Number n?n.longValue():0;}
  private static boolean bool(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof Boolean b?b:value instanceof Number n?n.intValue()!=0:Boolean.parseBoolean(Objects.toString(value,""));}
}
