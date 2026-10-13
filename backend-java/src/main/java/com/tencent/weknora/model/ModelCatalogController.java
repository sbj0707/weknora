package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import static com.tencent.weknora.model.CatalogRuntime.*;

/** Catalog editor reads and pure previews. No connection, probe or inference is performed. */
@RestController
@RequestMapping("/api/v1/models")
public class ModelCatalogController {
    private final ModelCatalogRuntime catalog;
    public ModelCatalogController(ModelCatalogRuntime catalog) {this.catalog=catalog;}
    @GetMapping("/providers")
    public Object providers(HttpServletRequest request,@RequestParam(name="model_type",defaultValue="") String rawType) {
        AuthContext auth=viewer(request);String type=rawType.isEmpty()?"":parseType(rawType);
        if(type==null)throw new ApiException(400,"BAD_REQUEST","unknown model_type");
        CatalogRuntime runtime=catalog.snapshot();ArrayNode result=JSON.createArrayNode();
        for(Provider provider:runtime.list()) {
            if(!type.isEmpty()&&!contains(provider.definition().path("ModelTypes"),type))continue;
            result.add(providerDTO(runtime,provider,type,auth.isAtLeast("admin")));
        }
        return Map.of("success",true,"data",result);
    }
    @GetMapping("/catalog/resolve")
    public Object resolveGet(HttpServletRequest request) {
        Map<String,String> values=new HashMap<>();request.getParameterMap().forEach((k,v)->values.put(k,v.length==0?"":v[0]));return preview(viewer(request),values,null);
    }
    @PostMapping("/catalog/resolve")
    public Object resolvePost(HttpServletRequest request,@RequestBody JsonNode body) {
        AuthContext auth=viewer(request);if(!body.isObject()&&!body.isNull())throw new ApiException(400,"BAD_REQUEST","Invalid request parameters");
        ObjectNode spec=null;Map<String,String> values=new HashMap<>();
        var fields=body.fields();while(fields.hasNext()){var e=fields.next();
            if(e.getKey().equals("spec")) {
                try {spec=decodeSpec(e.getValue());}catch(IllegalArgumentException ex){throw new ApiException(400,"BAD_REQUEST","invalid model spec");}
            }else {if(!e.getValue().isTextual()&&!e.getValue().isNull())throw new ApiException(400,"BAD_REQUEST","invalid resolve field: "+e.getKey());values.put(e.getKey(),e.getValue().asText(""));}
        }
        return preview(auth,values,spec);
    }
    private Object preview(AuthContext auth,Map<String,String> values,ObjectNode spec) {
        CatalogRuntime runtime=catalog.snapshot();String provider=trim(values.getOrDefault("provider","")),name=trim(values.getOrDefault("model","")),base=trim(values.getOrDefault("base_url",""));
        String type=parseType(values.getOrDefault("model_type",""));if(type==null)type="KnowledgeQA";
        ObjectNode extra=JSON.createObjectNode();
        for(String key:List.of("api","thinking_control","remote_model_name")) {String v=trim(values.getOrDefault(key,""));if(!v.isEmpty())extra.put(key,v);}
        Provider declared=runtime.providers.get(provider.toLowerCase(Locale.ROOT));
        if(declared!=null)for(JsonNode field:declared.definition().path("ExtraFields")) {
            if(field.path("secret").asBoolean()||text(field,"type").equals("password"))continue;
            String key=text(field,"key"),v=trim(values.getOrDefault(key,""));if(!v.isEmpty())extra.put(key,v);
        }
        Resolved resolved;
        try {resolved=runtime.resolve(new Ref(provider,name,base,type,extra,spec,0));}catch(IllegalArgumentException ex){throw new ApiException(400,"BAD_REQUEST",ex.getMessage());}
        ObjectNode data=JSON.createObjectNode();data.put("provider",resolved.vendor().id());data.put("api",resolved.api());data.put("remote_model",resolved.remoteModel());data.put("cataloged",resolved.cataloged());data.set("model",resolved.model());data.set("capabilities",resolved.capabilities());
        if(auth.isAtLeast("admin")) {data.put("base_url",resolved.baseURL());String url=endpoint(resolved,type,extra);if(!url.isEmpty())data.put("url",url);}
        return Map.of("success",true,"data",data);
    }
    static ObjectNode providerDTO(CatalogRuntime runtime,Provider provider,String type,boolean includeURLs) {
        ObjectNode d=provider.definition(),out=JSON.createObjectNode();
        out.put("value",provider.id());out.put("label",text(d,"Name"));out.put("description",text(d,"Description"));out.put("api",text(d,"API"));out.put("auth",text(d,"Auth"));out.put("requiresAuth",d.path("RequiresAuth").asBoolean());out.put("order",d.path("Order").asLong());
        for(var e:Map.of("Names","labels","Descriptions","descriptions","Website","website","ExtraFields","extraFields","CredentialLabels","credentialLabels").entrySet())if(!empty(d.get(e.getKey())))out.set(e.getValue(),d.get(e.getKey()).deepCopy());
        if(!text(d,"Icon").isEmpty())out.put("icon","data:image/svg+xml;base64,"+text(d,"Icon"));
        if(includeURLs){ObjectNode urls=JSON.createObjectNode();d.path("DefaultBaseURLs").fields().forEachRemaining(e->urls.put(frontend(e.getKey()),e.getValue().asText()));out.set("defaultUrls",urls);}else out.putNull("defaultUrls");
        ArrayNode types=JSON.createArrayNode();d.path("ModelTypes").forEach(t->types.add(frontend(t.asText())));out.set("modelTypes",types);
        ObjectNode thinking=JSON.createObjectNode().put("format","");thinking.set("levels",JSON.createArrayNode());
        try {JsonNode caps=runtime.resolve(provider,new Ref(provider.id(),"__vendor_default__","","",JSON.createObjectNode(),null,0)).capabilities();thinking.set("format",caps.get("thinking_format"));thinking.set("levels",caps.get("thinking_levels"));}catch(IllegalArgumentException ignored){ }
        out.set("thinking",thinking);ArrayNode models=JSON.createArrayNode();Set<String> seen=new HashSet<>();
        List<String> wanted=new ArrayList<>();if(type.isEmpty())d.path("ModelTypes").forEach(t->wanted.add(t.asText()));else wanted.add(type);
        for(String kind:wanted)for(ObjectNode m:runtime.modelsByType(provider,kind)) {
            String id=text(m,"id");if(!seen.add(id))continue;
            ObjectNode entry=JSON.createObjectNode().put("id",id).put("name",text(m,"name").isEmpty()?id:text(m,"name")).put("type",frontend(text(m,"type"))).put("reasoning",m.path("reasoning").asBoolean());
            for(String key:List.of("api","input","context_window","max_output_tokens","dimension","cost","source"))if(!empty(m.get(key)))entry.set(key,m.get(key).deepCopy());
            JsonNode levels=JSON.createArrayNode();
            if(Set.of("","KnowledgeQA").contains(text(m,"type")))try {levels=runtime.resolve(provider,new Ref(provider.id(),id,"","",JSON.createObjectNode(),null,0)).capabilities().get("thinking_levels");}catch(IllegalArgumentException ignored){ }
            entry.set("thinking_levels",levels);models.add(entry);
        }
        if(!models.isEmpty())out.set("models",models);return out;
    }
    static ObjectNode decodeSpec(JsonNode raw) {
        if(raw==null||raw.isNull())return null;
        ObjectNode fields=JSON.createObjectNode();JsonNode schema=DATA.path("model_schema").path("fields");
        for(String key:List.of("api","reasoning","input","context_window","max_output_tokens","thinking_levels","compat")) {
            JsonNode field=schema.path(key).deepCopy();if(field instanceof ObjectNode n){if(key.equals("reasoning"))n.put("pointer",true);fields.set(key,n);}
        }
        var specSchema=JSON.createObjectNode().put("kind","struct");specSchema.set("fields",fields);validate(raw,specSchema,false);
        ObjectNode out=typedMerge(JSON.createObjectNode(),raw,specSchema);
        var remove=new ArrayList<String>();out.fields().forEachRemaining(e->{if(e.getValue().isNull()||!e.getKey().equals("reasoning")&&empty(e.getValue()))remove.add(e.getKey());});remove.forEach(out::remove);return out;
    }
    private static String endpoint(Resolved r,String type,ObjectNode extra) {
        if(!r.vendor().definition().path("EndpointHook").asBoolean())return "";
        String root=r.baseURL().replaceAll("/+$","");
        return switch(r.vendor().id()) {
            case "weknoracloud" -> Set.of("Embedding","Rerank","ASR").contains(type)?"":root+"/api/v1/chat/completions";
            case "azure_openai" -> {
                String path=type.equals("Embedding")?"/embeddings":type.equals("ASR")?"/audio/transcriptions":r.api().equals("openai-responses")?"/responses":"/chat/completions";
                String version=trim(text(extra,"api_version"));
                if(!version.isEmpty()){String target=root+"/openai/deployments/"+pathEscape(r.remoteModel())+path;yield target+(target.contains("?")?"&":"?")+"api-version="+version;}
                yield root+(root.endsWith("/openai/v1")?"":"/openai/v1")+path;
            }
            case "aliyun" -> {
                if(!type.equals("Embedding"))yield "";
                for(String marker:List.of("/compatible-mode","/api/v1")){int at=root.indexOf(marker);if(at>=0)root=root.substring(0,at);}
                yield root+(r.embeddingAPI().equals("dashscope-embeddings")?"/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding":"/compatible-mode/v1/embeddings");
            }
            case "volcengine" -> {
                if(!type.equals("Embedding"))yield "";
                int at=root.indexOf("/api/");if(at>=0)root=root.substring(0,at);
                yield root+(r.embeddingAPI().equals("openai-embeddings")?"/api/v3/embeddings":"/api/v3/embeddings/multimodal");
            }
            default -> "";
        };
    }
    private static String pathEscape(String s) {
        StringBuilder out=new StringBuilder();for(byte b:s.getBytes(StandardCharsets.UTF_8)){int c=b&255;if(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||"-._~:@&=+$".indexOf(c)>=0)out.append((char)c);else out.append(String.format("%%%02X",c));}return out.toString();
    }
    private static AuthContext viewer(HttpServletRequest request) {
        AuthContext auth=CurrentAuth.get(request);if(auth.isApiKey())throw new ApiException(403,"FORBIDDEN","Forbidden: API key model routes are not migrated");auth.requireRole("viewer");if(auth.tenantId()<=0)throw new ApiException(400,"BAD_REQUEST","Workspace ID cannot be empty");return auth;
    }
}
