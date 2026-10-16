package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.io.*;

/** Go document-profile aggregation, bounded prompt and typed generated description. */
public final class KnowledgeBaseProfileText {
  private final ObjectMapper json;private final String prompt;
  private static final Comparator<String> GO_STRINGS=(a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
  public KnowledgeBaseProfileText(ObjectMapper json,String configured){this.json=json;try(var in=getClass().getResourceAsStream("/knowledge-base-profile-prompt.json")){prompt=SummaryGenerationText.trim(Objects.requireNonNullElse(configured,"")).isEmpty()?json.readTree(Objects.requireNonNull(in)).path("prompt").asText():configured;}catch(IOException e){throw new UncheckedIOException(e);}}
  private static String trim(String s){return SummaryGenerationText.trim(s);}private static String collapse(String s){return SummaryGenerationText.collapse(s);}private static String bound(String s,int n){return SummaryGenerationText.bound(s,n);}
  private static String lower(String s){var out=new StringBuilder();s.codePoints().forEach(c->out.appendCodePoint(Character.toLowerCase(c)));return out.toString();}
  private static String key(String s){return lower(collapse(s)).replaceAll("^[\\p{P}\\p{IsWhite_Space}]+|[\\p{P}\\p{IsWhite_Space}]+$","");}
  private static final class Bucket {String display;int count;List<String> questions=new ArrayList<>();Bucket(String d){display=d;}}
  public ObjectNode aggregate(List<ObjectNode> rows){var aggregate=json.createObjectNode();var stats=json.createObjectNode().put("document_count",0).put("profiled_count",0);aggregate.set("stats",stats);Map<String,Integer> files=new HashMap<>(),tags=new HashMap<>();Map<String,Bucket> topics=new LinkedHashMap<>(),types=new LinkedHashMap<>();Set<String> folders=new TreeSet<>(GO_STRINGS);List<String> titles=new ArrayList<>(),gists=new ArrayList<>(),extra=new ArrayList<>(),fingerprints=new ArrayList<>();String earliest=null,latest=null;int documents=0,profiled=0;
    for(var row:rows){if(row==null)continue;documents++;String ext=lower(trim(row.path("file_type").asText()));if(ext.startsWith("."))ext=ext.substring(1);if(!ext.isEmpty())files.merge(ext,1,Integer::sum);var rowTags=new ArrayList<String>();row.path("tags").forEach(t->rowTags.add(t.asText()));rowTags.sort(GO_STRINGS);for(String tag:rowTags){tag=trim(tag);if(!tag.isEmpty())tags.merge(tag,1,Integer::sum);}String folder=trim(row.path("folder_path").asText()).replaceAll("^/+|/+$","");int slash=folder.indexOf('/');if(slash>=0)folder=folder.substring(0,slash);if(!folder.isEmpty())folders.add(folder);String created=row.path("created_at").asText();if(earliest==null||created.compareTo(earliest)<0)earliest=created;if(latest==null||created.compareTo(latest)>0)latest=created;String title=trim(row.path("title").asText());if(title.isEmpty())title=trim(row.path("file_name").asText());if(!title.isEmpty())titles.add(title);String fingerprint=row.path("id").asText()+"|"+title+"|"+String.join(",",rowTags);var profile=row.path("profile");boolean nonempty=!trim(profile.path("gist").asText()).isEmpty()||profile.path("topics").size()>0||!trim(profile.path("doc_type").asText()).isEmpty()||!trim(profile.path("typical_question").asText()).isEmpty();if(nonempty){profiled++;var rawTopic=new ArrayList<String>();profile.path("topics").forEach(t->rawTopic.add(t.asText()));fingerprint+="|"+profile.path("gist").asText()+"|"+String.join(",",rawTopic)+"|"+profile.path("doc_type").asText()+"|"+profile.path("typical_question").asText();String gist=collapse(profile.path("gist").asText());if(!gist.isEmpty())gists.add(gist);String type=collapse(profile.path("doc_type").asText());if(!type.isEmpty())types.computeIfAbsent(key(type),k->new Bucket(type)).count++;String question=collapse(profile.path("typical_question").asText());boolean assigned=false;for(String t:rawTopic){String display=collapse(t),k=key(display);if(k.isEmpty())continue;var bucket=topics.computeIfAbsent(k,v->new Bucket(display));bucket.count++;if(!question.isEmpty()&&!assigned){bucket.questions.add(question);assigned=true;}}if(!question.isEmpty()&&!assigned)extra.add(question);}fingerprints.add(fingerprint);
    }
    stats.put("document_count",documents).put("profiled_count",profiled);setCounts(stats,"file_types",files,0);setCounts(stats,"tags",tags,30);if(!folders.isEmpty())stats.set("folders",json.valueToTree(folders.stream().limit(20).toList()));if(earliest!=null){stats.put("earliest_at",earliest);stats.put("latest_at",latest);}List<Bucket> ordered=new ArrayList<>(topics.values());ordered.sort(Comparator.comparingInt((Bucket b)->b.count).reversed());if(!ordered.isEmpty()){var counts=json.createArrayNode();ordered.stream().limit(30).forEach(b->counts.add(json.createObjectNode().put("name",b.display).put("count",b.count)));stats.set("raw_topics",counts);}Map<String,Integer> docCounts=new HashMap<>();types.values().forEach(b->docCounts.put(b.display,b.count));setCounts(stats,"doc_types",docCounts,10);setSample(aggregate,"sample_titles",titles,60);setSample(aggregate,"sample_gists",gists,20);List<String> questions=new ArrayList<>();Set<String> seen=new HashSet<>();outer:for(int round=0;;round++){boolean progress=false;for(var bucket:ordered)if(round<bucket.questions.size()){progress=true;String q=bucket.questions.get(round);if(seen.add(lower(q)))questions.add(q);if(questions.size()==40)break outer;}if(!progress)break;}for(String q:extra){if(questions.size()==40)break;if(seen.add(lower(q)))questions.add(q);}if(!questions.isEmpty())aggregate.set("sample_questions",json.valueToTree(questions));fingerprints.sort(GO_STRINGS);try{var hash=MessageDigest.getInstance("SHA-256");for(String f:fingerprints){hash.update(f.getBytes(StandardCharsets.UTF_8));hash.update((byte)0);}aggregate.put("hash",HexFormat.of().formatHex(hash.digest()));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}return aggregate;
  }
  private void setCounts(ObjectNode target,String field,Map<String,Integer> map,int cap){if(map.isEmpty())return;var order=new ArrayList<>(map.keySet());order.sort(Comparator.<String>comparingInt(map::get).reversed().thenComparing(GO_STRINGS));var list=json.createArrayNode();for(String k:order){list.add(json.createObjectNode().put("name",k).put("count",map.get(k)));if(cap>0&&list.size()==cap)break;}target.set(field,list);}
  private void setSample(ObjectNode target,String field,List<String> values,int cap){if(values.isEmpty())return;var out=json.createArrayNode();if(values.size()<=cap)values.forEach(out::add);else for(int i=0;i<cap;i++)out.add(values.get((int)(i*((double)values.size()/cap))));target.set(field,out);}
  public String render(JsonNode aggregate){var stats=aggregate.path("stats");var out=new StringBuilder("<aggregate>\nDocuments: ").append(stats.path("document_count").asInt()).append(" (with document profile: ").append(stats.path("profiled_count").asInt()).append(")\n");if(stats.has("earliest_at")&&stats.has("latest_at"))out.append("Added between: ").append(stats.path("earliest_at").asText(),0,10).append(" and ").append(stats.path("latest_at").asText(),0,10).append('\n');counts(out,"File types",stats.path("file_types"));if(stats.path("folders").size()>0)out.append("Top-level folders: ").append(join(stats.path("folders"),", ")).append('\n');counts(out,"Document types",stats.path("doc_types"));counts(out,"Tags (documents per tag)",stats.path("tags"));counts(out,"Raw topic keywords (documents mentioning each; merge synonyms)",stats.path("raw_topics"));samples(out,"Sample titles",aggregate.path("sample_titles"),stats.path("document_count").asInt());samples(out,"Sample one-line gists",aggregate.path("sample_gists"),stats.path("profiled_count").asInt());samples(out,"Sample questions documents answer",aggregate.path("sample_questions"),stats.path("profiled_count").asInt());return out.append("</aggregate>").toString();}
  private static String join(JsonNode list,String separator){var out=new ArrayList<String>();list.forEach(n->out.add(n.asText()));return String.join(separator,out);}
  private void counts(StringBuilder out,String label,JsonNode list){if(list.size()==0)return;var parts=new ArrayList<String>();list.forEach(n->parts.add(n.path("name").asText()+" ("+n.path("count").asInt()+")"));out.append(label).append(": ").append(String.join(", ",parts)).append('\n');}
  private void samples(StringBuilder out,String label,JsonNode list,int total){if(list.size()==0)return;out.append(label);if(total>list.size())out.append(" (showing ").append(list.size()).append(" of ").append(total).append(')');out.append(":\n");list.forEach(n->out.append("- ").append(n.asText()).append('\n'));}
  public ArrayNode messages(JsonNode base,JsonNode aggregate,String language){String custom=trim(base.path("profile_config").path("custom_instructions").asText());if(!custom.isEmpty())custom="\n## Additional instructions from the knowledge base owner\n"+custom+"\n";String localized=new SummaryGenerationText(json,prompt,8192,1024).prompt(language).replace("{{custom_instructions}}",custom);return json.createArrayNode().add(json.createObjectNode().put("role","system").put("content",localized)).add(json.createObjectNode().put("role","user").put("content","Knowledge base name: "+trim(base.path("name").asText())+"\n\n"+render(aggregate)));}
  public ObjectNode options(){return json.createObjectNode().put("temperature",.2).put("max_tokens",800).put("thinking",false);}
  public ObjectNode parse(JsonNode response){String content=trim(response==null?"":response.path("content").asText());if(content.isEmpty())throw new IllegalArgumentException("knowledge base description model returned no gist");JsonNode value=parseObject(content);if(value==null||!value.isObject())throw new IllegalArgumentException("parse knowledge base description: invalid JSON object");if(value.hasNonNull("gist")&&!value.path("gist").isTextual())throw new IllegalArgumentException("parse knowledge base description: invalid gist");for(String k:List.of("topics","typical_questions")){var list=value.path(k);if(!list.isMissingNode()&&!list.isNull()&&(!list.isArray()||java.util.stream.StreamSupport.stream(list.spliterator(),false).anyMatch(n->!n.isTextual()&&!n.isNull())))throw new IllegalArgumentException("parse knowledge base description: invalid "+k);}var out=json.createObjectNode();String gist=bound(collapse(value.path("gist").asText("")),300);if(gist.isEmpty())throw new IllegalArgumentException("knowledge base description model returned no gist");out.put("gist",gist);normalized(out,"topics",value.path("topics"),10,40,true);normalized(out,"typical_questions",value.path("typical_questions"),5,160,false);return out;}
  private JsonNode parseObject(String content){
    var candidates=new ArrayList<String>();candidates.add(content);
    var fence=java.util.regex.Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```").matcher(content);
    if(fence.find())candidates.add(trim(fence.group(1)));
    String balanced=KnowledgeAutoTagText.balanced(content);if(!balanced.isEmpty())candidates.add(balanced);
    // Go reuses the typed target across fallback attempts. Null strings do not
    // clear earlier values; duplicate and case-folded fields apply in JSON order.
    var state=json.createObjectNode();String directError=null;
    for(String candidate:candidates)try{
      GoUploadJson.valid(candidate.getBytes(StandardCharsets.UTF_8));
      JsonNode syntax=json.readTree(candidate);
      if(syntax==null)continue;
      if(syntax.isNull())return state;
      if(!syntax.isObject())continue;
      boolean invalid=false;
      try(var parser=json.getFactory().createParser(candidate)){
        parser.nextToken();
        while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT){
          String field=parser.currentName();parser.nextToken();JsonNode value=json.readTree(parser);
          String key=null;for(String known:List.of("gist","topics","typical_questions"))if(known.equalsIgnoreCase(field)){key=known;break;}
          if(key==null)continue;
          if(key.equals("gist")){if(value.isTextual())state.set(key,value);else if(!value.isNull())invalid=true;}
          else if(value.isNull())state.putNull(key);
          else if(value.isArray()){
            var strings=json.createArrayNode();
            for(var v:value){if(v.isTextual())strings.add(v);else{strings.add("");if(!v.isNull())invalid=true;}}
            state.set(key,strings);
          }else invalid=true;
        }
      }
      if(!invalid)return state;
    }catch(IOException invalid){if(directError==null)directError=invalid.getMessage();}
    throw new IllegalArgumentException("parse knowledge base description: "+Objects.requireNonNullElse(directError,"invalid JSON object"));
  }
  private void normalized(ObjectNode out,String field,JsonNode raw,int cap,int length,boolean topic){Set<String> seen=new HashSet<>();var list=json.createArrayNode();for(var n:raw){String s=bound(collapse(n.isNull()?"":n.asText()),length),key=topic?key(s):lower(s);if(!key.isEmpty()&&seen.add(key)){list.add(s);if(list.size()==cap)break;}}if(!list.isEmpty())out.set(field,list);}
}
