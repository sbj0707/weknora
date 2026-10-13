package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.model.client.*;
import com.tencent.weknora.security.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Tenant-scoped native SPI factory; has no public HTTP routes and never caches credentials. */
@Service
public final class ModelClientFactory {
    private static final Set<String> RESERVED=Set.of("authorization","api-key","x-api-key","x-goog-api-key","content-type","content-length","accept-encoding","host","connection","transfer-encoding");
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final GoSecretCodec secrets;private final ModelCatalogRuntime catalog;private final SafeModelHttp http;private final Environment env;private volatile OllamaRuntime ollama;
    public ModelClientFactory(JdbcTemplate jdbc,ObjectMapper json,GoSecretCodec secrets,ModelCatalogRuntime catalog,ModelEgressPolicy egress,Environment env){this.jdbc=jdbc;this.json=json;this.secrets=secrets;this.catalog=catalog;this.http=new SafeModelHttp(egress);this.env=env;}
    @PreDestroy public void close(){http.close();}
    public EmbeddingModel embedding(AuthContext auth,String id){var c=load(auth,id,"Embedding");if(c.local)return new OllamaEmbedding(ollama(),localEndpoint(c),c.params.path("embedding_parameters").path("dimension").asInt(),c.params.path("embedding_parameters").path("truncate_prompt_tokens").asInt(),c.params.path("embedding_parameters").path("supports_dimension_override").asBoolean());if(!c.resolved.embeddingAPI().equals("openai-embeddings"))throw unsupported("embedding",c.resolved.embeddingAPI());ModelEndpoint endpoint=endpoint(c,"Embedding","embeddings","/embeddings");return new OpenAIEmbedding(http,endpoint,c.params.path("embedding_parameters").path("dimension").asInt(),c.params.path("embedding_parameters").path("supports_dimension_override").asBoolean());}
    public RerankModel rerank(AuthContext auth,String id){var c=load(auth,id,"Rerank");if(c.local)throw unsupported("source","local rerank");if(!c.resolved.rerankAPI().equals("cohere-rerank"))throw unsupported("rerank",c.resolved.rerankAPI());return new CohereRerank(http,endpoint(c,"Rerank","rerank","/rerank"));}
    public ChatModel chat(AuthContext auth,String id){var c=load(auth,id,"KnowledgeQA");if(c.local)return new OllamaChat(ollama(),localEndpoint(c));Duration chat=timeout("WEKNORA_LLM_CHAT_TIMEOUT_SECONDS",300),stream=timeout("WEKNORA_LLM_STREAM_TIMEOUT_SECONDS",600);return switch(c.resolved.api()){case "openai-completions"->new OpenAIChat(http,endpoint(c,"KnowledgeQA","openai_completions","/chat/completions"),chat,stream);case "anthropic-messages"->new AnthropicMessages(http,endpoint(c,"KnowledgeQA","anthropic_messages","/messages"),chat,stream);case "openai-responses"->new OpenAIResponses(http,endpoint(c,"KnowledgeQA","openai_responses","/responses"),chat,stream);default->throw unsupported("chat",c.resolved.api());};}
    private Duration timeout(String key,long fallback){try{long value=Long.parseLong(env.getProperty(key,""));return Duration.ofSeconds(value>0?value:fallback);}catch(Exception ex){return Duration.ofSeconds(fallback);}}
    private Config load(AuthContext auth,String id,String kind){auth.requireRole("viewer");if(auth.tenantId()<=0)throw new ApiException(400,"BAD_REQUEST","Workspace ID cannot be empty");var rows=jdbc.queryForList("SELECT * FROM models WHERE id=? AND (tenant_id=? OR is_builtin=true) AND deleted_at IS NULL",id,auth.tenantId());if(rows.isEmpty())throw new ApiException(404,"NOT_FOUND","Model not found");var row=rows.getFirst();String status=String.valueOf(row.get("status"));if(!status.equals("active"))throw new ApiException(500,"INTERNAL_SERVER_ERROR",status.equals("downloading")?"model is currently downloading":status.equals("download_failed")?"model download failed":"abnormal model status");
        String source=String.valueOf(row.get("source"));boolean local=source.equalsIgnoreCase("local");if(!local&&!source.equalsIgnoreCase("remote"))throw unsupported("source",source);String type=String.valueOf(row.get("type"));if(!type.equals(kind)&&!(kind.equals("KnowledgeQA")&&type.equals("VLLM")))throw new ApiException(400,"BAD_REQUEST","Model type does not match requested capability");
        ObjectNode params;try{Object stored=row.get("parameters");JsonNode node=stored instanceof byte[] bytes?json.readTree(bytes):json.readTree(stored.toString());if(node==null||node.isNull())node=json.createObjectNode();if(!node.isObject())throw new IllegalArgumentException();params=(ObjectNode)node;}catch(Exception ex){throw new ApiException(500,"INTERNAL_SERVER_ERROR","Invalid persisted model parameters");}
        // Execution is strict: an unavailable encrypted secret must never be sent as ciphertext.
        String key=secrets.decryptStrict(params.path("api_key").asText()),appSecret=secrets.decryptStrict(params.path("app_secret").asText());params=params.deepCopy();params.put("api_key",key);params.put("app_secret",appSecret);
        if(local)return new Config(row.get("id").toString(),row.get("name").toString(),params,null,true);
        CatalogRuntime runtime=catalog.snapshot();ObjectNode extra=params.hasNonNull("extra_config")?(ObjectNode)params.get("extra_config"):json.createObjectNode(),spec=params.hasNonNull("spec")?(ObjectNode)params.get("spec"):null;CatalogRuntime.Resolved resolved;
        try{resolved=runtime.resolve(new CatalogRuntime.Ref(params.path("provider").asText(),row.get("name").toString(),params.path("base_url").asText(),kind,extra,spec,params.path("embedding_parameters").path("truncate_prompt_tokens").asLong()));}catch(IllegalArgumentException ex){throw new ApiException(400,"BAD_REQUEST","Model parameters cannot resolve against the active catalog");}
        return new Config(row.get("id").toString(),row.get("name").toString(),params,resolved,false);
    }
    private record Config(String id,String name,ObjectNode params,CatalogRuntime.Resolved resolved,boolean local){}
    private synchronized OllamaRuntime ollama(){if(ollama==null)ollama=new OllamaRuntime(http,env.getProperty("OLLAMA_BASE_URL","http://localhost:11434"),env.getProperty("OLLAMA_OPTIONAL","").equals("true"),OllamaRuntime.authRequested(env.getProperty("OLLAMA_AUTH","")));return ollama;}
    private ModelEndpoint localEndpoint(Config c){return new ModelEndpoint(c.id,c.name,c.name,"",json.createObjectNode(),json.createObjectNode(),false,Map.of(),List.of(c.params.path("api_key").asText(),c.params.path("app_secret").asText()));}
    private ModelEndpoint endpoint(Config c,String kind,String settingsName,String defaultPath){CatalogRuntime.Resolved r=c.resolved;ObjectNode definition=r.vendor().definition();String protocol=kind.equals("KnowledgeQA")?r.api():definition.path("API").asText(),auth=definition.path("AuthByAPI").path(protocol).asText(definition.path("Auth").asText());if(auth.equals("signed"))throw unsupported("authentication",auth);
        String key=c.params.path("api_key").asText();if(key.isEmpty())key=definition.path("DefaultAPIKey").asText();Map<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);for(JsonNode input:List.of(definition.path("Headers"),c.params.path("custom_headers")))input.fields().forEachRemaining(e->{String name=CatalogRuntime.trim(e.getKey());if(!name.isEmpty()&&!RESERVED.contains(name.toLowerCase(Locale.ROOT)))headers.put(name,e.getValue().isNull()?"":e.getValue().asText());});
        if(!key.isEmpty())switch(auth){case "none":break;case "api-key":headers.put("api-key",key);break;case "x-api-key":headers.put("x-api-key",key);break;case "x-goog-api-key":headers.put("x-goog-api-key",key);break;default:headers.put("Authorization","Bearer "+key);}
        ObjectNode settings=(ObjectNode)r.settings().path(settingsName).deepCopy();String root=r.baseURL().replaceAll("/+$",""),path=settings.path("path").asText(defaultPath);if(path.isEmpty())path=defaultPath;String target=root+(kind.equals("KnowledgeQA")||!root.endsWith(path)?path:"");ObjectNode extra=c.params.hasNonNull("extra_config")?(ObjectNode)c.params.get("extra_config"):json.createObjectNode();
        if(kind.equals("KnowledgeQA")&&r.api().equals("anthropic-messages")){String parsedPath="";try{parsedPath=Objects.toString(java.net.URI.create(root).getPath(),"");}catch(IllegalArgumentException ignored){}target=parsedPath.endsWith("/messages")?root:root+(parsedPath.endsWith("/v1")||parsedPath.endsWith("/v1beta")?"/messages":"/v1/messages");}
        if(definition.path("EndpointHook").asBoolean())switch(r.vendor().id()){
            case "azure_openai":{String version=CatalogRuntime.trim(extra.path("api_version").asText()),suffix=kind.equals("Embedding")?"/embeddings":r.api().equals("openai-responses")?"/responses":"/chat/completions";if(version.isEmpty())target=root+(root.endsWith("/openai/v1")?"":"/openai/v1")+suffix;else{target=root+"/openai/deployments/"+pathEscape(r.remoteModel())+suffix;target+=(target.contains("?")?"&":"?")+"api-version="+version;}break;}
            case "aliyun":if(kind.equals("Embedding")){for(String marker:List.of("/compatible-mode","/api/v1")){int at=root.indexOf(marker);if(at>=0)root=root.substring(0,at);}target=root+"/compatible-mode/v1/embeddings";}break;
            case "volcengine":if(kind.equals("Embedding")){int at=root.indexOf("/api/");if(at>=0)root=root.substring(0,at);target=root+"/api/v3/embeddings";}break;
            default:break;
        }
        return new ModelEndpoint(c.id,c.name,r.remoteModel(),target,settings,r.thinkingLevels(),r.model().path("reasoning").asBoolean(),headers,List.of(key,c.params.path("app_secret").asText(),c.params.path("extra_config").path("secret_key").asText()));
    }
    private static String pathEscape(String text){StringBuilder out=new StringBuilder();for(byte b:text.getBytes(StandardCharsets.UTF_8)){int c=b&255;if(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||"-._~:@&=+$".indexOf(c)>=0)out.append((char)c);else out.append(String.format("%%%02X",c));}return out.toString();}
    private static ApiException unsupported(String kind,String api){return new ApiException(503,"MODEL_PROTOCOL_NOT_MIGRATED","Native model "+kind+" protocol is not migrated: "+api);}
}
