package com.tencent.weknora.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static com.tencent.weknora.model.CatalogRuntime.*;

/** Transactional JSON overlay compiler. Console routing/secrets remain deployment owned. */
final class CatalogOverlay {
    private static final ObjectMapper STRICT=JSON.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern ENV=Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}|\\$([A-Za-z_][A-Za-z0-9_]*)");
    static CatalogRuntime apply(CatalogRuntime base,String raw,Path directory,boolean console) {
        JsonNode root;
        try {root=(console?STRICT:JSON.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).readTree(raw);}catch(IOException ex){throw bad("invalid catalog JSON: "+ex.getMessage());}
        if(root==null||!root.isObject())throw bad("catalog must be an object with providers");
        if(console) {
            if(raw.getBytes(StandardCharsets.UTF_8).length>1024*1024)throw bad("catalog overlay exceeds 1 MiB");
            ((ObjectNode)root).remove("_comment");if(!root.path("providers").isObject())throw bad("providers must be an object");
        }
        validate(root,DATA.path("overlay_schema"),true);
        var next=new LinkedHashMap<String,Provider>();base.providers.forEach((id,p)->next.put(id,p.copy()));
        var seen=new HashSet<String>();
        var fields=root.path("providers").fields();
        while(fields.hasNext()) {
            var field=fields.next();String id=trim(field.getKey()).toLowerCase(Locale.ROOT);JsonNode patch=field.getValue();
            if(id.isEmpty()||console&&(!seen.add(id)||!patch.isObject()))throw bad("invalid or duplicate provider "+quote(field.getKey()));
            if(patch.isNull())patch=JSON.createObjectNode();
            if(console)checkConsole(id,patch);
            Provider provider=next.containsKey(id)?next.get(id).copy():custom(id);
            ObjectNode d=provider.definition();var models=provider.models();
            for(var mapping:Map.of("name","Name","names","Names","description","Description","descriptions","Descriptions","website","Website","auth","Auth").entrySet()) {
                if(!empty(patch.get(mapping.getKey())))d.set(mapping.getValue(),patch.get(mapping.getKey()).deepCopy());
            }
            String api=text(patch,"api");if(!api.isEmpty()){if(!APIS.contains(api))throw bad("unknown api "+quote(api));d.put("API",api);}
            ObjectNode urls=d.path("DefaultBaseURLs").isObject()?(ObjectNode)d.get("DefaultBaseURLs"):JSON.createObjectNode();
            if(!text(patch,"base_url").isEmpty())urls.put("KnowledgeQA",interpolate(text(patch,"base_url")));
            patch.path("base_urls").fields().forEachRemaining(e->{String type=parseType(e.getKey());if(type==null)throw bad("unknown model type "+quote(e.getKey())+" in base_urls");urls.put(type,interpolate(e.getValue().asText()));});
            d.set("DefaultBaseURLs",urls);
            if(!text(patch,"api_key").isEmpty())d.put("DefaultAPIKey",interpolate(text(patch,"api_key")));
            if(patch.path("headers").size()>0){ObjectNode headers=JSON.createObjectNode();patch.path("headers").fields().forEachRemaining(e->headers.put(e.getKey(),interpolate(e.getValue().asText(""))));d.set("Headers",headers);}
            if(patch.hasNonNull("requires_auth"))d.set("RequiresAuth",patch.get("requires_auth"));
            if(patch.path("model_types").size()>0){ArrayNode types=JSON.createArrayNode();for(JsonNode n:patch.get("model_types")){String type=parseType(n.asText(""));if(type==null)throw bad("unknown model type "+quote(n.asText()));types.add(type);}d.set("ModelTypes",types);}
            if(patch.path("url_patterns").size()>0)d.set("URLPatterns",patch.get("url_patterns").deepCopy());
            if(!text(patch,"icon").isEmpty())d.put("Icon",Base64.getEncoder().encodeToString(icon(directory,text(patch,"icon"))));
            if(patch.has("compat")) {
                String key=text(d,"API").replace('-','_');
                if(PROTOCOLS.subList(0,4).contains(key)) {
                    JsonNode schema=DATA.path("schemas").path(key);validate(patch.get("compat"),schema,true);
                    ObjectNode compat=d.path("Compat").isObject()?(ObjectNode)d.get("Compat"):JSON.createObjectNode();
                    ObjectNode existing=compat.path(key).isObject()?(ObjectNode)compat.get(key):JSON.createObjectNode();
                    compat.set(key,typedMerge(existing,patch.get("compat"),schema));d.set("Compat",compat);
                }
            }
            if(patch.path("thinking_levels").size()>0){ObjectNode levels=d.path("ThinkingLevels").isObject()?(ObjectNode)d.get("ThinkingLevels"):JSON.createObjectNode();merge(levels,patch.get("thinking_levels"));d.set("ThinkingLevels",levels);}
            for(JsonNode rawModel:patch.path("models")) {
                validate(rawModel,DATA.path("model_schema"),true);String modelId=text(rawModel,"id");if(modelId.isEmpty())throw bad("model without id");
                String type=text(rawModel,"type");List<ObjectNode> matching=models.stream().filter(m->text(m,"id").equalsIgnoreCase(modelId)).toList();
                if(type.isEmpty()&&matching.size()>1)throw bad("model "+modelId+" matches multiple types; specify type");
                int found=-1;for(int i=0;i<models.size();i++)if(text(models.get(i),"id").equalsIgnoreCase(modelId)&&(type.isEmpty()||entryType(text(models.get(i),"type")).equals(entryType(type)))){found=i;break;}
                ObjectNode merged=typedMerge(found<0?JSON.createObjectNode().put("id","").put("reasoning",false):models.get(found),rawModel,DATA.path("model_schema"));
                if(found<0)models.add(serializeModel(merged));else models.set(found,serializeModel(merged));
            }
            var overrides=patch.path("model_overrides").fields();
            while(overrides.hasNext()) {
                var e=overrides.next();String modelId=e.getKey();JsonNode p=e.getValue();var matches=models.stream().filter(m->text(m,"id").equalsIgnoreCase(modelId)).toList();
                if(matches.size()>1)throw bad("model_overrides "+modelId+" matches multiple types; use models with an explicit type");
                ObjectNode m=matches.isEmpty()?JSON.createObjectNode().put("id",modelId).put("reasoning",false):matches.getFirst();
                for(String k:List.of("name","api","input","cost"))if(!empty(p.get(k)))m.set(k,p.get(k).deepCopy());
                for(String k:List.of("reasoning","deprecated"))if(p.hasNonNull(k))m.set(k,p.get(k));
                for(String k:List.of("context_window","max_output_tokens"))if(p.path(k).asLong()>0)m.set(k,p.get(k));
                if(p.path("thinking_levels").size()>0){ObjectNode levels=m.path("thinking_levels").isObject()?(ObjectNode)m.get("thinking_levels"):JSON.createObjectNode();merge(levels,p.get("thinking_levels"));m.set("thinking_levels",levels);}
                if(p.has("compat")){ObjectNode compat=m.path("compat").isObject()?(ObjectNode)m.get("compat"):JSON.createObjectNode();merge(compat,p.get("compat"));m.set("compat",compat);}
                if(p.path("aliases").size()>0){ArrayNode aliases=m.path("aliases").isArray()?(ArrayNode)m.get("aliases"):JSON.createArrayNode();aliases.addAll((ArrayNode)p.get("aliases"));m.set("aliases",aliases);}
                if(matches.isEmpty())models.add(m);
            }
            normalize(provider);next.put(id,provider);
        }
        CatalogRuntime result=new CatalogRuntime(next);
        for(Provider p:result.list()) validateProvider(result,p);
        return result;
    }
    static void validateProvider(CatalogRuntime runtime,Provider p) {
        if(!Set.of("bearer","api-key","x-api-key","x-goog-api-key","none","signed").contains(text(p.definition(),"Auth")))throw bad("unknown auth style "+quote(text(p.definition(),"Auth")));
        for(JsonNode type:p.definition().path("ModelTypes"))try {runtime.resolve(p,new Ref(p.id(),"custom-model","",type.asText(),JSON.createObjectNode(),null,0));}catch(Unsupported ignored) { }
        for(ObjectNode m:p.models()) {
            String name=text(m,"id");if(name.isEmpty())name=text(m,"match").replace("*","snapshot");
            try{runtime.resolve(p,new Ref(p.id(),name,"",text(m,"type"),JSON.createObjectNode(),null,0));}catch(Unsupported ex){if(!m.path("deprecated").asBoolean()&&!text(m,"id").isEmpty())throw ex;}
            for(String k:List.of("context_window","max_output_tokens","dimension"))if(m.path(k).asLong()<0)throw bad("model "+name+": negative model limit");
            if(!text(m,"type").isEmpty()&&parseType(text(m,"type"))==null)throw bad("model "+name+": unsupported type "+quote(text(m,"type")));
        }
    }
    private static Provider custom(String id) {
        ObjectNode d=JSON.createObjectNode().put("ID",id).put("Name",id).put("API","openai-completions").put("Auth","bearer").put("RequiresAuth",true).put("Order",1000);
        d.set("DefaultBaseURLs",JSON.createObjectNode());d.set("ModelTypes",JSON.createArrayNode().add("KnowledgeQA"));return new Provider(d,new ArrayList<>());
    }
    private static void normalize(Provider p) {
        ObjectNode d=p.definition();if(text(d,"API").isEmpty())d.put("API","openai-completions");if(text(d,"Auth").isEmpty())d.put("Auth","bearer");
        for(String type:List.of("Embedding","Rerank","ASR"))if(contains(d.path("ModelTypes"),type)) {String key=switch(type){case "Embedding"->"EmbeddingAPI";case "Rerank"->"RerankAPI";default->"TranscriptionAPI";};if(text(d,key).isEmpty())d.put(key,switch(type){case "Embedding"->"openai-embeddings";case "Rerank"->"cohere-rerank";default->"openai-transcriptions";});}
        for(ObjectNode m:p.models()){if(text(m,"type").isEmpty())m.put("type","KnowledgeQA");if(text(m,"api").isEmpty()&&text(m,"type").equals("KnowledgeQA"))m.put("api",text(d,"API"));}
    }
    private static void checkConsole(String id,JsonNode patch) {
        patch.fieldNames().forEachRemaining(k->{if(!k.equals(k.toLowerCase(Locale.ROOT)))throw bad("provider "+id+": field names must use lowercase: "+k);if(Set.of("api_key","headers","base_url","base_urls","url_patterns","auth").contains(k))throw bad("provider "+id+": "+k+" must be managed in deployment configuration or model credentials");});
        if(patch.has("icon")){String v=text(patch,"icon");if(!patch.get("icon").isTextual()&&!patch.get("icon").isNull()||!v.isEmpty()&&!trim(v).startsWith("<svg"))throw bad("provider "+id+": console icons must be inline SVG");}
    }
    private static String interpolate(String s) {
        Matcher matcher=ENV.matcher(s);StringBuilder out=new StringBuilder();while(matcher.find()){String name=matcher.group(1)==null?matcher.group(2):matcher.group(1);matcher.appendReplacement(out,Matcher.quoteReplacement(System.getenv().getOrDefault(name,"")));}matcher.appendTail(out);return out.toString();
    }
    private static byte[] icon(Path directory,String ref) {
        try {
            byte[] bytes;
            if(looksSVG(ref))bytes=ref.getBytes(StandardCharsets.UTF_8);
            else {
                Path relative=Path.of(ref);if(relative.isAbsolute()||relative.normalize().startsWith(".."))throw bad("icon: path escapes the overlay directory");
                Path base=(directory==null?Path.of("."):directory).toRealPath();Path file=base.resolve(relative).toRealPath();
                if(!file.startsWith(base)||!Files.isRegularFile(file)||Files.size(file)>256*1024)throw bad("icon: invalid or oversized file");bytes=Files.readAllBytes(file);
            }
            if(bytes.length>256*1024||!looksSVG(new String(bytes,StandardCharsets.UTF_8)))throw bad("icon: invalid or oversized SVG");return bytes;
        }catch(IOException ex){throw bad("read icon: "+ex.getMessage());}
    }
    private static boolean looksSVG(String s) {
        if(s.startsWith("\ufeff"))s=s.substring(1);
        while(true){s=trim(s);String end=s.startsWith("<?")?"?>":s.startsWith("<!--")?"-->":s.startsWith("<!")?">":null;
            if(end==null){s=s.toLowerCase(Locale.ROOT);return s.length()>4&&s.startsWith("<svg")&&" \t\r\n>/".indexOf(s.charAt(4))>=0;}
            int at=s.indexOf(end);if(at<0)return false;s=s.substring(at+end.length());
        }
    }
}
