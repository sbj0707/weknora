package com.tencent.weknora.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Immutable Go-compatible catalog generation. Contains metadata and pure resolution only. */
final class CatalogRuntime {
    static final ObjectMapper JSON = new ObjectMapper();
    static final JsonNode DATA = loadData();
    static final List<String> LEVELS = List.of("off", "auto", "minimal", "low", "medium", "high", "xhigh", "max");
    static final Set<String> APIS = Set.of("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai", "ollama");
    static final List<String> PROTOCOLS = List.of("openai_completions", "openai_responses", "anthropic_messages", "google_generative_ai", "rerank", "embeddings", "transcriptions");
    final Map<String, Provider> providers;
    record Provider(ObjectNode definition, List<ObjectNode> models) {
        Provider copy() { return new Provider(definition.deepCopy(), models.stream().map(ObjectNode::deepCopy).collect(java.util.stream.Collectors.toCollection(ArrayList::new))); }
        String id() { return text(definition,"ID"); }
        String api() { return text(definition,"API"); }
        String defaultURL(String type) { var urls=definition.path("DefaultBaseURLs"); return urls.has(type)?urls.path(type).asText():urls.path("KnowledgeQA").asText(""); }
    }
    record Ref(String provider, String model, String baseURL, String type, ObjectNode extra, ObjectNode spec, long truncate) { }
    record Resolved(Provider vendor, ObjectNode model, boolean cataloged, String api, String baseURL, String remoteModel,
                    ObjectNode thinkingLevels, ObjectNode settings, String embeddingAPI, String rerankAPI, String transcriptionAPI) {
        ObjectNode capabilities() {
            ObjectNode caps=JSON.createObjectNode();
            caps.put("provider",vendor.id()); caps.put("api",api); caps.put("cataloged",cataloged); caps.put("reasoning",model.path("reasoning").asBoolean());
            for(String key:List.of("input","context_window","max_output_tokens")) if(model.has(key)&&!empty(model.get(key))) caps.set(key,model.get(key).deepCopy());
            String format="",max="";
            boolean none=false,onlySwitches=false;
            switch(api) {
                case "openai-completions":
                    format=settings.path("openai_completions").path("thinking_format").asText();
                    max=settings.path("openai_completions").path("max_tokens_field").asText();
                    none=format.equals("none");
                    onlySwitches=format.equals("chat-template-kwargs")&&!settings.path("openai_completions").path("supports_reasoning_effort").asBoolean();break;
                case "openai-responses":format="reasoning.effort";max="max_output_tokens";break;
                case "anthropic-messages":format="thinking."+settings.path("anthropic_messages").path("thinking_mode").asText();max="max_tokens";break;
                case "google-generative-ai":format="thinkingConfig."+settings.path("google_generative_ai").path("thinking_mode").asText();max="maxOutputTokens";none=settings.path("google_generative_ai").path("thinking_mode").asText().equals("none");break;
                case "ollama":format="think";max="num_predict";break;
            }
            caps.put("thinking_format",format);if(!max.isEmpty())caps.put("max_tokens_field",max);
            var levels=JSON.createArrayNode();
            if(!none) for(String level:LEVELS) {
                boolean supported=thinkingLevels.has(level)?!thinkingLevels.get(level).isNull():!Set.of("xhigh","max").contains(level);
                if(supported&&(!onlySwitches||Set.of("off","auto").contains(level))) levels.add(level);
            }
            caps.set("thinking_levels",levels);return caps;
        }
        ObjectNode fixture() {
            var out=JSON.createObjectNode();out.put("provider",vendor.id());out.put("api",api);out.put("base_url",baseURL);out.put("remote_model",remoteModel);out.put("cataloged",cataloged);
            out.set("model",model);out.set("capabilities",capabilities());out.set("settings",settings);
            out.put("embedding_api",embeddingAPI);out.put("rerank_api",rerankAPI);out.put("transcription_api",transcriptionAPI);return out;
        }
    }
    CatalogRuntime(Map<String,Provider> providers) { this.providers=Collections.unmodifiableMap(new LinkedHashMap<>(providers)); }
    static CatalogRuntime builtins() {
        var providers=new LinkedHashMap<String,Provider>();
        for(JsonNode p:DATA.path("providers")) {
            ObjectNode d=(ObjectNode)p.get("definition").deepCopy();var models=new ArrayList<ObjectNode>();p.path("models").forEach(m->models.add((ObjectNode)m.deepCopy()));
            providers.put(text(d,"ID"),new Provider(d,models));
        }
        return new CatalogRuntime(providers);
    }
    List<Provider> list() { return providers.values().stream().sorted(Comparator.comparingLong((Provider p)->p.definition.path("Order").asLong()).thenComparing(Provider::id)).toList(); }
    Provider select(String provider,String url) {
        String id=trim(provider).toLowerCase(Locale.ROOT);if(id.isEmpty())id=detect(url);
        return providers.getOrDefault(id,providers.get("generic"));
    }
    String detect(String url) {
        String lower=url.toLowerCase(Locale.ROOT),id="generic";int len=0;long order=Long.MAX_VALUE;
        for(Provider p:list()) for(JsonNode pattern:p.definition.path("URLPatterns")) {
            String s=pattern.asText();int size=s.getBytes(StandardCharsets.UTF_8).length;long candidateOrder=p.definition.path("Order").asLong();
            if(!s.isEmpty()&&lower.contains(s.toLowerCase(Locale.ROOT))&&(size>len||size==len&&(candidateOrder<order||candidateOrder==order&&p.id().compareTo(id)<0))) {id=p.id();len=size;order=candidateOrder;}
        }
        return id;
    }
    List<ObjectNode> modelsByType(Provider p,String type) {
        return p.models.stream().filter(m->!m.path("deprecated").asBoolean()&&!text(m,"id").isEmpty()).filter(m->type.equals("VLLM")?entryType(text(m,"type")).equals("KnowledgeQA")&&contains(m.path("input"),"image"):entryType(text(m,"type")).equals(type)).toList();
    }
    ObjectNode find(Provider p,String model,String type) {
        String needle=trim(model).toLowerCase(Locale.ROOT);if(needle.isEmpty())return null;
        var candidates=p.models.stream().filter(m->entryType(text(m,"type")).equals(entryType(type))).toList();
        for(ObjectNode m:candidates)if(text(m,"id").toLowerCase(Locale.ROOT).equals(needle))return m.deepCopy();
        for(ObjectNode m:candidates)for(JsonNode a:m.path("aliases"))if(a.asText().toLowerCase(Locale.ROOT).equals(needle))return m.deepCopy();
        ObjectNode best=null;int bestScore=-1;
        for(ObjectNode m:candidates) {
            String pattern=text(m,"match");if(pattern.isEmpty()||!glob(pattern.toLowerCase(Locale.ROOT),needle))continue;
            int idx=pattern.indexOf('*');int score=(idx<0?pattern:pattern.substring(0,idx)).getBytes(StandardCharsets.UTF_8).length;
            if(score>bestScore){best=m;bestScore=score;}
        }
        return best==null?null:best.deepCopy();
    }
    Resolved resolve(Ref ref) { return resolve(select(ref.provider,ref.baseURL),ref); }
    Resolved resolve(Provider vendor,Ref ref) {
        String type=ref.type.isEmpty()?"KnowledgeQA":ref.type;
        String base=trim(ref.baseURL).replaceAll("/+$","");if(base.isEmpty())base=vendor.defaultURL(type).replaceAll("/+$","");
        ObjectNode model=find(vendor,ref.model,type);boolean cataloged=model!=null;
        if(model==null)model=JSON.createObjectNode().put("id",ref.model).put("type",entryType(type)).put("reasoning",false);
        if(text(model,"api").isEmpty())model.put("api",vendor.api());
        String catalogAPI=text(model,"api");ObjectNode spec=ref.spec;
        if(spec!=null) {
            if(!text(spec,"api").isEmpty())model.set("api",spec.get("api"));
            if(spec.hasNonNull("reasoning"))model.set("reasoning",spec.get("reasoning"));
            if(spec.path("input").size()>0)model.set("input",spec.get("input"));
            for(String key:List.of("context_window","max_output_tokens"))if(spec.path(key).asLong()>0)model.set(key,spec.get(key));
        }
        ObjectNode settings=zeroSettings();ObjectNode levels=JSON.createObjectNode();
        String remote=trim(text(ref.extra,"remote_model_name"));if(remote.isEmpty())remote=ref.model;
        String api="",embeddingAPI="",rerankAPI="",transcriptionAPI="";
        if(Set.of("Rerank","Embedding","ASR").contains(type)) {
            String key=switch(type){case "Rerank"->"rerank";case "Embedding"->"embeddings";default->"transcriptions";};
            ObjectNode config=(ObjectNode)DATA.path("defaults").path(key).deepCopy();
            applyCompat(config,vendor.definition.path("Compat").path(key),key);
            String compatPrefix=type.equals("Embedding")?"embeddings":type.equals("ASR")?"transcriptions":"rerank";
            applyRaw(config,model.get("compat"),key,compatPrefix+" compat: ");
            applyRaw(config,spec==null?null:spec.get("compat"),key,compatPrefix+" compat: ");
            if(type.equals("Rerank")) {
                rerankAPI=text(vendor.definition,"RerankAPI");if(rerankAPI.isEmpty())rerankAPI="cohere-rerank";
                if(!Set.of("cohere-rerank","dashscope-rerank","nim-rerank","tencent-lkeap","volcengine-knowledge").contains(rerankAPI))throw bad("catalog: unknown rerank api "+quote(rerankAPI)+" for provider "+vendor.id());
                String scale=trim(text(ref.extra,"score_scale"));if(!scale.isEmpty()) {scale=scale.toLowerCase(Locale.ROOT);if(!Set.of("probability","logit").contains(scale))throw bad("catalog: invalid score_scale in extra_config: "+quote(scale)+" (expected \"probability\" or \"logit\")");config.put("score_scale",scale);}
                String trunc=trim(text(ref.extra,"truncate_prompt_tokens"));if(!trunc.isEmpty()) {
                    if(!config.path("accepts_truncate_prompt_tokens").asBoolean())throw bad("catalog: truncate_prompt_tokens is a vLLM extension and "+vendor.id()+" does not implement it; remove it from extra_config (it is accepted by self-hosted runtimes only)");
                    try {long n=Long.parseLong(trunc);if(n<=0)throw new NumberFormatException();config.put("truncate_prompt_tokens",n);}catch(NumberFormatException ex){throw bad("catalog: invalid truncate_prompt_tokens in extra_config: "+quote(trunc));}
                }
            } else if(type.equals("Embedding")) {
                embeddingAPI=text(vendor.definition,"EmbeddingAPI");if(embeddingAPI.isEmpty())embeddingAPI="openai-embeddings";
                if(config.path("accepts_truncate_prompt_tokens").asBoolean())config.put("truncate_prompt_tokens",ref.truncate>0?ref.truncate:511);
                if(!text(config,"api").isEmpty())embeddingAPI=text(config,"api");
                if(!Set.of("openai-embeddings","dashscope-embeddings","google-embeddings","ark-embeddings").contains(embeddingAPI))throw bad("catalog: unknown embedding api "+quote(embeddingAPI)+" on "+vendor.id()+"/"+text(model,"id"));
                config.put("api",embeddingAPI);
            } else {
                transcriptionAPI=text(vendor.definition,"TranscriptionAPI");if(transcriptionAPI.isEmpty())transcriptionAPI="openai-transcriptions";
                if(!text(config,"api").isEmpty())transcriptionAPI=text(config,"api");
                if(!Set.of("openai-transcriptions","openai-chat-audio").contains(transcriptionAPI))throw bad("catalog: unknown transcription api "+quote(transcriptionAPI)+" for "+vendor.id()+"/"+text(model,"id"));
                config.put("api",transcriptionAPI);
                if(!Set.of("","form","header","asr_options").contains(text(config,"language_param")))throw bad("catalog: unknown language_param "+quote(text(config,"language_param"))+" for "+vendor.id()+"/"+text(model,"id"));
            }
            if(!text(config,"unsupported_reason").isEmpty())throw new Unsupported("catalog: "+vendor.id()+" does not serve "+quote(text(model,"id"))+" through a protocol this build implements: "+text(config,"unsupported_reason"));
            settings.set(key,config);
        } else {
            api=text(model,"api");String forced=trim(text(ref.extra,"api"));
            if(!forced.isEmpty())api=forced;
            else if(spec==null||text(spec,"api").isEmpty()) {
                String lower=base.toLowerCase(Locale.ROOT);if(lower.endsWith("/anthropic")||lower.endsWith("/anthropic/v1"))api="anthropic-messages";
                else if(api.equals("google-generative-ai")&&(lower.endsWith("/openai")||lower.contains("/openai/")))api="openai-completions";
                if(vendor.definition.path("PreferAPIHook").asBoolean()&&Set.of("","KnowledgeQA").contains(text(model,"type"))) {
                    try {URI url=URI.create(base);if("api.openai.com".equalsIgnoreCase(url.getHost()))api="openai-responses";}catch(IllegalArgumentException ignored) { }
                }
            }
            if(!APIS.contains(api))throw bad("catalog: unknown api "+quote(api)+" for provider "+vendor.id());
            merge(levels,vendor.definition.path("ThinkingLevels"));merge(levels,model.path("thinking_levels"));if(spec!=null)merge(levels,spec.path("thinking_levels"));
            for(String protocol:PROTOCOLS.subList(0,4)) {
                ObjectNode config=(ObjectNode)DATA.path("defaults").path(protocol).deepCopy();
                applyCompat(config,vendor.definition.path("Compat").path(protocol),protocol);
                String target=protocol.replace('_','-');
                if(target.equals(catalogAPI))applyRaw(config,model.get("compat"),protocol,target+" compat: ");
                if(target.equals(api))applyRaw(config,spec==null?null:spec.get("compat"),protocol,target+" compat: ");
                settings.set(protocol,config);
            }
            String legacy=trim(text(ref.extra,"thinking_control")).toLowerCase(Locale.ROOT);
            ObjectNode completions=(ObjectNode)settings.get("openai_completions");
            if(!legacy.isEmpty())completions.put("thinking_format",switch(legacy){case "none"->"none";case "enable_thinking"->"enable-thinking";case "thinking_type"->"thinking-type";default->"chat-template-kwargs";});
            if(legacy.isEmpty()&&cataloged&&!model.path("reasoning").asBoolean()) {
                completions.put("thinking_format","none");completions.put("thinking_budget_field","");completions.put("supports_reasoning_effort",false);
                ((ObjectNode)settings.get("google_generative_ai")).put("thinking_mode","none");
                levels=JSON.createObjectNode();for(String level:LEVELS)if(!level.equals("off"))levels.putNull(level);
            }
        }
        return new Resolved(vendor,model,cataloged,api,base,remote,levels,settings,embeddingAPI,rerankAPI,transcriptionAPI);
    }
    private static ObjectNode zeroSettings() {
        ObjectNode out=JSON.createObjectNode();
        for(String protocol:PROTOCOLS) {
            ObjectNode values=JSON.createObjectNode();var fields=DATA.path("schemas").path(protocol).path("fields");
            fields.fields().forEachRemaining(e->{JsonNode s=e.getValue();String kind=s.path("kind").asText();JsonNode zero=s.path("pointer").asBoolean()?zero(kind):NullNode.instance;values.set(e.getKey(),zero);});
            // Settings pointers stay pointers only for FixedTemperature.
            if(protocol.equals("openai_completions"))values.putNull("fixed_temperature");
            if(Set.of("rerank","embeddings").contains(protocol))values.put("truncate_prompt_tokens",0);
            if(protocol.equals("embeddings")||protocol.equals("transcriptions"))values.put("api","");
            out.set(protocol,values);
        }
        return out;
    }
    private static JsonNode zero(String kind) {
        return switch(kind){case "string"->TextNode.valueOf("");case "bool"->BooleanNode.FALSE;case "int","int64","uint64"->IntNode.valueOf(0);case "float64"->DoubleNode.valueOf(0);default->NullNode.instance;};
    }
    static void applyCompat(ObjectNode settings,JsonNode overlay,String protocol) {
        if(overlay==null||overlay.isNull()||overlay.isMissingNode())return;
        JsonNode schema=DATA.path("schemas").path(protocol);validate(overlay,schema,true);
        overlay.fields().forEachRemaining(e->{JsonNode value=e.getValue();if(value.isNull()||value.isContainerNode()&&value.isEmpty())return;
            if(value.isObject()){ObjectNode dest=settings.path(e.getKey()).isObject()?(ObjectNode)settings.get(e.getKey()):JSON.createObjectNode();merge(dest,value);settings.set(e.getKey(),dest);}else settings.set(e.getKey(),value.deepCopy());
        });
    }
    private static void applyRaw(ObjectNode settings,JsonNode raw,String protocol,String prefix) {
        try {applyCompat(settings,raw,protocol);}catch(IllegalArgumentException ex){throw bad(prefix+"decode compat: "+ex.getMessage());}
    }
    static String parseType(String s) {
        return switch(trim(s).toLowerCase(Locale.ROOT)){case "chat"->"KnowledgeQA";case "embedding"->"Embedding";case "rerank"->"Rerank";case "vlm","vllm"->"VLLM";case "asr"->"ASR";default->s.equals("KnowledgeQA")?s:null;};
    }
    static String frontend(String type) {return switch(type){case "KnowledgeQA"->"chat";case "Embedding"->"embedding";case "Rerank"->"rerank";case "VLLM"->"vllm";case "ASR"->"asr";default->type;};}
    static String entryType(String type) {return type.isEmpty()||type.equals("VLLM")?"KnowledgeQA":type;}
    static String trim(String s) {return s.replaceAll("(?U)^\\s+|\\s+$","");}
    static String text(JsonNode n,String key) {return n==null?"":n.path(key).asText("");}
    static void merge(ObjectNode target,JsonNode src) {if(src!=null&&src.isObject())src.fields().forEachRemaining(e->target.set(e.getKey(),e.getValue().deepCopy()));}
    static boolean contains(JsonNode list,String s) {for(JsonNode n:list)if(n.asText().equals(s))return true;return false;}
    static boolean empty(JsonNode n) {return n==null||n.isNull()||n.isContainerNode()&&n.isEmpty()||n.isTextual()&&n.asText().isEmpty()||n.isNumber()&&n.asDouble()==0||n.isBoolean()&&!n.asBoolean();}
    private static boolean glob(String pattern,String s) {
        String[] parts=pattern.split("\\*",-1);if(parts.length==1)return pattern.equals(s);if(!s.startsWith(parts[0]))return false;s=s.substring(parts[0].length());
        for(int i=1;i<parts.length-1;i++){int at=s.indexOf(parts[i]);if(at<0)return false;s=s.substring(at+parts[i].length());}return s.endsWith(parts[parts.length-1]);
    }
    static void validate(JsonNode value,JsonNode schema,boolean strict) {
        if(value==null||value.isNull())return;
        String kind=schema.path("kind").asText();
        switch(kind) {
            case "string":if(!value.isTextual())throw bad("invalid string field");break;
            case "bool":if(!value.isBoolean())throw bad("invalid boolean field");break;
            case "int","int64","uint64":if(!value.isIntegralNumber()||!value.canConvertToLong())throw bad("invalid integer field");break;
            case "float64":if(!value.isNumber())throw bad("invalid number field");break;
            case "slice","array":if(!value.isArray())throw bad("invalid array field");value.forEach(v->validate(v,schema.path("items"),strict));break;
            case "map":if(!value.isObject())throw bad("invalid object field");value.forEach(v->validate(v,schema.path("items"),strict));break;
            case "struct":
                if(!value.isObject())throw bad("invalid object field");value.fields().forEachRemaining(e->{JsonNode field=schema.path("fields").get(e.getKey());
                    if(field==null){if(strict)throw bad("json: unknown field "+quote(e.getKey()));return;}
                    // RawMessage is decoded by its protocol and is not a byte-array JSON field.
                    if(e.getKey().equals("compat"))return;
                    if(e.getKey().equals("models")&&schema.path("fields").has("model_overrides")){if(!e.getValue().isNull()&&!e.getValue().isArray())throw bad("invalid models");return;}
                    validate(e.getValue(),field,strict);
                });break;
        }
    }
    static ObjectNode typedMerge(ObjectNode base,JsonNode patch,JsonNode schema) {
        ObjectNode out=base.deepCopy();if(patch==null||patch.isNull())return out;
        patch.fields().forEachRemaining(e->{String key=e.getKey();JsonNode s=schema.path("fields").get(key);if(s==null)return;JsonNode v=e.getValue();String kind=s.path("kind").asText();
            if(key.equals("compat")){out.set(key,v.deepCopy());return;}
            if(v.isNull()){if(s.path("pointer").asBoolean()||Set.of("map","slice","array").contains(kind))out.putNull(key);return;}
            if(kind.equals("map")&&out.path(key).isObject()){ObjectNode dest=(ObjectNode)out.get(key);merge(dest,v);}else if(kind.equals("struct")){out.set(key,typedMerge(out.path(key).isObject()?(ObjectNode)out.get(key):JSON.createObjectNode(),v,s));}else out.set(key,v.deepCopy());
        });return out;
    }
    static ObjectNode serializeModel(ObjectNode model) {
        ObjectNode out=model.deepCopy();var schema=DATA.path("model_schema").path("fields");
        var keys=new ArrayList<String>();out.fieldNames().forEachRemaining(keys::add);
        for(String key:keys) {
            // RawMessage("null") is nonempty bytes, and a nonnil Cost pointer serializes
            // required input/output zeros even when the operator supplied {}.
            if(key.equals("compat"))continue;
            if(key.equals("cost")&&out.path(key).isObject()) {
                ObjectNode cost=(ObjectNode)out.get(key);for(String required:List.of("input","output"))if(!cost.hasNonNull(required))cost.put(required,0);
                for(String optional:List.of("cache_read","cache_write","currency"))if(empty(cost.get(optional)))cost.remove(optional);
                continue;
            }
            if(schema.path(key).path("omit").asBoolean()&&empty(out.get(key)))out.remove(key);
        }
        if(!out.has("id"))out.put("id","");if(!out.hasNonNull("reasoning"))out.put("reasoning",false);return out;
    }
    static IllegalArgumentException bad(String message) {return new IllegalArgumentException(message);}
    static final class Unsupported extends IllegalArgumentException {Unsupported(String message){super(message);}}
    static String quote(String s) {try{return JSON.writeValueAsString(s);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static JsonNode loadData() {
        try(var in=new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(BuiltinCatalogData.encoded())))){return JSON.readTree(in);}catch(IOException ex){throw new ExceptionInInitializerError(ex);}
    }
}
