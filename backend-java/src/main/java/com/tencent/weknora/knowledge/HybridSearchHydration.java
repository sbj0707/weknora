package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.CallContext;
import com.tencent.weknora.retrieval.RetrievalHit;
import com.tencent.weknora.security.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Hydrates physical-index candidates and context with exact persisted owner/KB/document binding. */
public final class HybridSearchHydration {
  private final JdbcTemplate jdbc;private final ObjectMapper json;private final GraphQueryHydration projection;private final GraphExtractionText values;private final GoStoredStructDecoder faqMetadata;
  private static final Set<String> SEARCHABLE=Set.of("text","summary","table_column","table_summary","faq","image_ocr","image_caption");
  public HybridSearchHydration(JdbcTemplate jdbc,ObjectMapper json,GraphQueryHydration projection){this.jdbc=jdbc;this.json=json;this.projection=projection;values=new GraphExtractionText(json,"");var fields=json.createObjectNode();for(String key:List.of("standard_question","answer_strategy","source"))fields.set(key,json.createObjectNode().put("kind","string"));fields.set("version",json.createObjectNode().put("kind","int"));for(String key:List.of("similar_questions","negative_questions","answers"))fields.set(key,json.createObjectNode().put("kind","slice").set("items",json.createObjectNode().put("kind","string")));faqMetadata=new GoStoredStructDecoder(json,json.createObjectNode().put("kind","struct").set("fields",fields));}
  public List<ObjectNode> hydrate(List<RetrievalHit> hits,Map<String,KnowledgeBaseReadAuthorization.ReadAccess> grants,boolean skip,CallContext call){
    call.check();if(hits.isEmpty())return null;
    var indexed=new LinkedHashMap<String,RetrievalHit>();var docIds=new LinkedHashSet<String>();for(var h:hits){indexed.put(h.chunkId(),h);docIds.add(h.knowledgeId());}
    var documents=new LinkedHashMap<String,Map<String,Object>>();for(var row:rows("knowledges",new ArrayList<>(docIds)))documents.put(text(row,"id"),row);
    var chunks=new LinkedHashMap<String,Map<String,Object>>();
    for(var c:rows("chunks",new ArrayList<>(indexed.keySet()))){var h=indexed.get(text(c,"id"));var d=documents.get(text(c,"knowledge_id"));if(bound(c,d,grants)&&h!=null&&h.knowledgeId().equals(text(c,"knowledge_id"))&&h.knowledgeBaseId().equals(text(c,"knowledge_base_id")))chunks.put(text(c,"id"),c);}
    var primary=new ArrayList<>(chunks.values());var score=new HashMap<String,Double>();var type=new HashMap<String,Integer>();var matched=new HashMap<String,String>();var processed=new HashSet<>(chunks.keySet());
    for(var h:hits){score.put(h.chunkId(),h.score());type.put(h.chunkId(),h.matchType());matched.put(h.chunkId(),h.content());}
    if(!skip){var extra=expand(primary,processed,score,type,false);var added=fetchContext(extra,documents,grants);chunks.putAll(added);if(primary.stream().anyMatch(c->Set.of("image_ocr","image_caption").contains(text(c,"chunk_type"))))chunks.putAll(fetchContext(expand(new ArrayList<>(added.values()),processed,score,type,true),documents,grants));}
    var output=new ArrayList<ObjectNode>();var emitted=new HashSet<String>();
    for(var hit:hits){var c=chunks.get(hit.chunkId());if(c!=null&&searchable(c)&&emitted.add(hit.chunkId()))output.add(project(c,documents.get(text(c,"knowledge_id")),score.getOrDefault(hit.chunkId(),0d),type.getOrDefault(hit.chunkId(),0),matched.getOrDefault(hit.chunkId(),"")));}
    if(!skip)for(var id:chunks.keySet().stream().sorted(HybridSearchText::compare).toList()){var c=chunks.get(id);if(!emitted.contains(id)&&searchable(c)&&type.containsKey(id))output.add(project(c,documents.get(text(c,"knowledge_id")),Math.max(0,score.getOrDefault(id,0d)),type.get(id),matched.getOrDefault(id,"")));}
    projection.enrich(output,chunks,call);call.check();return output.isEmpty()?null:output;
  }
  public List<RetrievalHit> negativeQuestions(List<RetrievalHit> hits,Set<String> faq,String query){
    String normalized=HybridSearchText.normalized(query);if(normalized.isEmpty()||faq.isEmpty())return hits;
    var selected=hits.stream().filter(h->h.knowledgeBaseId().isEmpty()||faq.contains(h.knowledgeBaseId())).map(RetrievalHit::chunkId).distinct().toList();var rejected=new HashSet<String>();
    for(var c:rows("chunks",selected))if(text(c,"chunk_type").equals("faq"))try{var m=faqMetadata.decode(text(c,"metadata"));if(m!=null&&m.isObject())for(var n:m.path("negative_questions"))if(n.isTextual()&&!SummaryGenerationText.trim(n.asText()).isEmpty()&&HybridSearchText.normalized(n.asText()).equals(normalized))rejected.add(text(c,"id"));}catch(RuntimeException ignored){}
    return hits.stream().filter(h->!rejected.contains(h.chunkId())).toList();
  }
  private Map<String,Set<String>> expand(List<Map<String,Object>> roots,Set<String> processed,Map<String,Double> scores,Map<String,Integer> types,boolean parentsOnly){
    var allowed=new LinkedHashMap<String,Set<String>>();for(var c:roots){var ids=new ArrayList<String>();String parent=text(c,"parent_chunk_id");if(!parent.isEmpty()&&!processed.contains(parent)){ids.add(parent);processed.add(parent);scores.put(parent,scores.getOrDefault(text(c,"id"),0d));types.put(parent,4);}
      if(!parentsOnly){try{var relation=json.readTree(text(c,"relation_chunks"));if(relation!=null&&relation.isArray()&&allStrings(relation))for(var v:relation)if(!v.isNull()&&processed.add(v.asText())){ids.add(v.asText());types.put(v.asText(),5);}}catch(IOException ignored){}if(text(c,"chunk_type").equals("text"))for(var field:List.of("next_chunk_id","pre_chunk_id")){String id=text(c,field);if(!id.isEmpty()&&processed.add(id)){ids.add(id);types.put(id,2);}}}
      for(var id:ids)allowed.computeIfAbsent(id,k->new LinkedHashSet<>()).add(binding(c));}
    return allowed;
  }
  private Map<String,Map<String,Object>> fetchContext(Map<String,Set<String>> allowed,Map<String,Map<String,Object>> documents,Map<String,KnowledgeBaseReadAuthorization.ReadAccess> grants){var result=new LinkedHashMap<String,Map<String,Object>>();for(var c:rows("chunks",new ArrayList<>(allowed.keySet())))if(allowed.getOrDefault(text(c,"id"),Set.of()).contains(binding(c))&&bound(c,documents.get(text(c,"knowledge_id")),grants))result.put(text(c,"id"),c);return result;}
  private static String binding(Map<String,Object> c){return number(c,"tenant_id")+"\u0000"+text(c,"knowledge_base_id")+"\u0000"+text(c,"knowledge_id");}
  private static boolean bound(Map<String,Object> c,Map<String,Object> d,Map<String,KnowledgeBaseReadAuthorization.ReadAccess> grants){if(d==null)return false;var g=grants.get(text(d,"knowledge_base_id"));return g!=null&&g.effectiveTenantId()==number(d,"tenant_id")&&g.effectiveTenantId()==number(c,"tenant_id")&&text(c,"knowledge_base_id").equals(g.knowledgeBaseId())&&text(c,"knowledge_id").equals(text(d,"id"));}
  private ObjectNode project(Map<String,Object> c,Map<String,Object> d,double score,int match,String content){var r=projection.project(c,d);r.put("score",score).put("match_type",match);if(!content.isEmpty())r.put("matched_content",content);if(!text(d,"description").isEmpty())r.put("knowledge_description",text(d,"description"));String custom=custom(text(d,"custom_metadata"));if(!custom.isEmpty())r.put("knowledge_custom_metadata",custom);r.put("chunk_metadata_raw",text(c,"metadata"));r.put("context_header",text(c,"context_header")).put("content_revision",number(c,"content_revision"));return r;}
  private String custom(String raw){try{var map=values.parseNumbersAsGoFloat64(raw);if(map==null||!map.isObject())return "";var sorted=new TreeMap<String,String>(HybridSearchText::compare);map.fields().forEachRemaining(e->{String key=SummaryGenerationText.trim(e.getKey()),v=SummaryGenerationText.trim(GraphExtractionText.goValue(e.getValue()));if(!key.isEmpty()&&!v.isEmpty()&&!e.getValue().isNull())sorted.put(e.getKey(),key+": "+v);});return String.join("\n",sorted.values());}catch(IOException|RuntimeException invalid){return "";}}
  private List<Map<String,Object>> rows(String table,List<String> ids){if(ids.isEmpty())return List.of();return jdbc.queryForList("SELECT * FROM "+table+" WHERE deleted_at IS NULL AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY id",ids.toArray());}
  private static boolean searchable(Map<String,Object> c){Object enabled=c.get("is_enabled");boolean yes=enabled instanceof Boolean b?b:enabled instanceof Number n&&n.intValue()!=0;return yes&&SEARCHABLE.contains(text(c,"chunk_type"))&&!Set.of("processing","failed").contains(text(c,"index_status"));}
  private static boolean allStrings(JsonNode array){for(var v:array)if(!v.isTextual()&&!v.isNull())return false;return true;}
  static String text(Map<String,Object> row,String key){Object v=row.get(key);return v instanceof byte[] bytes?new String(bytes,StandardCharsets.UTF_8):Objects.toString(v,"");}
  static long number(Map<String,Object> row,String key){return row.get(key)instanceof Number n?n.longValue():0;}
}
