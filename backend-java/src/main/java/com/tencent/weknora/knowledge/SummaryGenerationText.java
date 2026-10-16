package com.tencent.weknora.knowledge;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.AuthPreferences;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Production Go summary reconstruction, sampling and small profile contract. */
public final class SummaryGenerationText {
  public record Result(String summary,ObjectNode profile) {}
  private final ObjectMapper json;
  private final String prompt;
  private final int maximum,tokens;
  public SummaryGenerationText(ObjectMapper json) {this(json,null,8192,1024);}
  public SummaryGenerationText(ObjectMapper json,String prompt,int maximum,int tokens) {
    this.json=json;this.maximum=maximum>0?maximum:8192;this.tokens=tokens>0?tokens:1024;
    try(var in=getClass().getResourceAsStream("/summary-generation-prompt.json")) {
      this.prompt=prompt==null?json.readTree(Objects.requireNonNull(in)).path("prompt").asText():prompt;
    }catch(IOException e){throw new UncheckedIOException(e);}
  }
  public String prompt(String language) {
    String locale=trim(language);if(locale.isEmpty())locale=trim(System.getenv().getOrDefault("WEKNORA_LANGUAGE","zh-CN"));if(locale.isEmpty())locale="zh-CN";
    String name=switch(locale){case "zh-CN","zh","zh-Hans"->"Chinese (Simplified)";case "zh-TW","zh-HK","zh-Hant"->"Chinese (Traditional)";case "en-US","en","en-GB"->"English";case "ru-RU","ru"->"Russian";case "ko-KR","ko"->"Korean";case "ja-JP","ja"->"Japanese";case "fr-FR","fr"->"French";case "es-ES","es"->"Spanish";case "pt-BR","pt"->"Portuguese";case "de-DE","de"->"German";default->locale;};
    return prompt.replace("{{language}}",name);
  }
  public ObjectNode options(){return json.createObjectNode().put("temperature",.3).put("max_tokens",tokens).put("thinking",false);}
  public String input(List<ObjectNode> chunks,JsonNode metadata,List<ObjectNode> images) {
    var sorted=new ArrayList<>(chunks);boolean edited=sorted.stream().anyMatch(c->c.path("content_revision").asLong()>0);
    String content;
    if(edited){sorted.sort(Comparator.<ObjectNode>comparingLong(c->c.path("chunk_index").asLong()).thenComparing(c->c.path("id").asText()));content=String.join("\n\n",sorted.stream().filter(c->c.path("is_enabled").asBoolean()&&!trim(c.path("content").asText()).isEmpty()).map(c->c.path("content").asText()).toList());}
    else {sorted.sort(Comparator.<ObjectNode>comparingLong(c->c.path("start_at").asLong()).thenComparingLong(c->c.path("chunk_index").asLong()));content="";long end=-1;
      for(var c:sorted){String next=c.path("content").asText();if(next.isEmpty())continue;long start=c.path("start_at").asLong(),finish=c.path("end_at").asLong();
        if(content.isEmpty()){content=next;if(finish>0)end=finish;continue;}
        if(start>end||finish==0){content+=next;if(finish>0)end=finish;}
        else if(finish>end){content=overlap(content,next,end-start);end=finish;}
      }
    }
    content=enrich(content,images);content=sample(content,maximum);
    if(realTextLength(content)<10)throw new InsufficientContent();
    String custom=metadata(metadata);if(!custom.isEmpty())content="Document metadata:\n"+custom+"\n\nDocument content:\n"+content;
    return sample(content,maximum);
  }
  static String overlap(String left,String right,long span) {
    if(span<=0)return left+right;int[] a=left.codePoints().toArray(),b=right.codePoints().toArray();int max=(int)Math.min(Math.min(a.length,b.length),Math.max(span*3,400)),slack=(int)Math.min(Integer.MAX_VALUE,Math.max(span*2,320));
    for(int k=max;k>=12;k--)for(int p=0;p<=Math.min(b.length-k,slack);p++)if(Arrays.equals(a,a.length-k,a.length,b,p,p+k))return left+new String(b,p+k,b.length-p-k);
    return left+right;
  }
  public Result parse(JsonNode response) {
    String content=trim(response==null?"":response.path("content").asText());if(content.isEmpty())throw new IllegalArgumentException("summary model returned empty output");
    JsonNode value=parseObject(content);if(value==null)return new Result(content,null);
    // Go typed string/slice fields reject mismatches instead of silently coercing them.
    for(String field:List.of("summary","gist","doc_type","typical_question"))if(value.hasNonNull(field)&&!value.get(field).isTextual())return new Result(content,null);
    if(value.hasNonNull("topics")&&(!value.path("topics").isArray()||java.util.stream.StreamSupport.stream(value.path("topics").spliterator(),false).anyMatch(n->!n.isTextual()&&!n.isNull())))return new Result(content,null);
    ObjectNode p=json.createObjectNode();for(var field:Map.of("gist",120,"doc_type",40,"typical_question",160).entrySet()){String s=bound(collapse(value.path(field.getKey()).asText()),field.getValue());if(!s.isEmpty())p.put(field.getKey(),s);}
    var topics=json.createArrayNode();var seen=new HashSet<String>();for(var topic:value.path("topics")){String s=bound(collapse(topic.asText()),40),key=s.toLowerCase(Locale.ROOT).replaceAll("^[\\p{P}\\p{IsWhite_Space}]+|[\\p{P}\\p{IsWhite_Space}]+$","");if(!key.isEmpty()&&seen.add(key)){topics.add(s);if(topics.size()==5)break;}}if(!topics.isEmpty())p.set("topics",topics);
    String summary=trim(value.path("summary").asText());if(summary.isEmpty())summary=p.path("gist").asText();if(summary.isEmpty())return new Result(content,null);
    return new Result(summary,p.isEmpty()?null:p);
  }
  private JsonNode parseObject(String s) {
    try {JsonNode n=json.readTree(s);if(n==null||n.isNull())return json.createObjectNode();if(n.isObject())return n;}catch(IOException ignored){}
    Matcher fence=Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```").matcher(s);if(fence.find())try{JsonNode n=json.readTree(trim(fence.group(1)));if(n.isObject())return n;}catch(IOException ignored){}
    int start=-1,depth=0;boolean quoted=false,escape=false;char opener=0;
    for(int i=0;i<s.length();i++){char c=s.charAt(i);if(start<0){if(c=='{'||c=='['){start=i;opener=c;depth=1;}continue;}if(escape){escape=false;continue;}if(quoted&&c=='\\'){escape=true;continue;}if(c=='"'){quoted=!quoted;continue;}if(!quoted){if(c=='{'||c=='[')depth++;if(c=='}'||c==']')depth--;if(depth==0){try{JsonNode n=json.readTree(s.substring(start,i+1));return opener=='{'&&n.isObject()?n:null;}catch(IOException e){return null;}}}}
    return null;
  }
  static String summaryChunk(Result result){String summary=trim(result.summary()),gist=result.profile()==null?"":trim(result.profile().path("gist").asText());return "# Summary\n"+(!gist.isEmpty()&&!gist.equalsIgnoreCase(summary)?gist+"\n\n":"")+summary;}
  static String fallback(List<ObjectNode> chunks){return chunks.isEmpty()?"":bound(trim(chunks.getFirst().path("content").asText()),500);}
  static String sample(String content,int limit){int[] r=content.codePoints().toArray();if(r.length<=limit)return content;String mark="\n\n[...content omitted...]\n\n";int usable=limit-2*mark.length();if(usable<100)return new String(r,0,limit);int head=usable*60/100,tail=usable*20/100,mid=usable-head-tail,start=Math.max(head,r.length/2-mid/2);if(start+mid>r.length-tail)start=Math.max(head,r.length-tail-mid);return new String(r,0,head)+mark+new String(r,start,mid)+mark+new String(r,r.length-tail,tail);}
  static int realTextLength(String content){String s=content.replaceAll("(?is)<image_original\\b[^>]*>.*?</image_original>","").replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)","").replaceAll("(?i)<img\\b[^>]*/?>","").replaceAll("(?i)</?image[a-z_]*\\b[^>]*/?>","");return trim(s).codePointCount(0,trim(s).length());}
  static String enrich(String content,List<ObjectNode> images){boolean ocr=realTextLength(content)<200;var byUrl=new HashMap<String,ObjectNode>();for(var image:images)for(String key:List.of("url","original_url"))if(!image.path(key).asText().isEmpty())byUrl.put(image.path(key).asText(),image);var processed=new HashSet<String>();Matcher m=Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)").matcher(content);var out=new StringBuffer();while(m.find()){String url=m.group(2);processed.add(url);String block=block(byUrl.get(url),ocr);m.appendReplacement(out,Matcher.quoteReplacement(m.group()+(block.isEmpty()?"":"\n"+block)));}m.appendTail(out);var extras=new ArrayList<String>();for(var image:images)if(!processed.contains(image.path("url").asText())&&!processed.contains(image.path("original_url").asText())){String b=block(image,ocr);if(!b.isEmpty())extras.add(b);}if(!extras.isEmpty()){if(!out.isEmpty())out.append('\n');out.append(String.join("\n",extras));}return out.toString();}
  private static String block(JsonNode image,boolean ocr){if(image==null)return "";var parts=new ArrayList<String>();if(!image.path("caption").asText().isEmpty())parts.add("<image_caption>"+image.path("caption").asText()+"</image_caption>");if(ocr&&!image.path("ocr_text").asText().isEmpty())parts.add("<image_ocr>"+image.path("ocr_text").asText()+"</image_ocr>");return String.join("\n",parts);}
  static String metadata(JsonNode value){if(!value.isObject())return "";var keys=new ArrayList<String>();value.fieldNames().forEachRemaining(keys::add);keys.sort(Comparator.naturalOrder());var lines=new ArrayList<String>();for(String k:keys){JsonNode n=value.get(k);String v=trim(goValue(n));if(!n.isNull()&&!trim(k).isEmpty()&&!v.isEmpty())lines.add(trim(k)+": "+v);}return String.join("\n",lines);}
  private static String goValue(JsonNode n){if(n.isNull())return "<nil>";if(n.isTextual())return n.textValue();if(n.isNumber())return n.decimalValue().stripTrailingZeros().toString();if(n.isBoolean())return n.asText();if(n.isArray()){var values=new ArrayList<String>();n.forEach(v->values.add(goValue(v)));return "["+String.join(" ",values)+"]";}var values=new TreeMap<String,String>();n.fields().forEachRemaining(e->values.put(e.getKey(),goValue(e.getValue())));return "map["+String.join(" ",values.entrySet().stream().map(e->e.getKey()+":"+e.getValue()).toList())+"]";}
  static String trim(String s){return AuthPreferences.trimGoSpace(s);}
  static String collapse(String s){return trim(s).replaceAll("[\\p{IsWhite_Space}]+"," ");}
  static String bound(String s,int max){int[] p=s.codePoints().toArray();return p.length<=max?s:trim(new String(p,0,max));}
  public static final class InsufficientContent extends IllegalArgumentException {InsufficientContent(){super("insufficient text content for summary generation");}}
}
