package com.tencent.weknora.system;
import com.fasterxml.jackson.core.*;import com.fasterxml.jackson.databind.*;
import com.tencent.weknora.docreader.*;import com.tencent.weknora.security.*;import com.tencent.weknora.model.ModelURLPolicy;import com.tencent.weknora.chunker.GoJsonFirstValue;import com.tencent.weknora.storage.StoragePaths;
import jakarta.servlet.http.HttpServletRequest;import java.io.*;import java.util.*;import java.util.function.Consumer;
import org.springframework.core.env.Environment;import org.springframework.http.ResponseEntity;import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/system")
public final class SystemParserController {
 private final SystemParserRepository repository;private final ParserEngineSettings settings;private final ParserEngineRegistry registry;private final RemoteDocumentReader reader;private final Environment environment;private final Consumer<String> validate;
 @org.springframework.beans.factory.annotation.Autowired public SystemParserController(SystemParserRepository repository,ParserEngineSettings settings,ParserEngineRegistry registry,RemoteDocumentReader reader,Environment environment,ModelURLPolicy policy){this(repository,settings,registry,reader,environment,policy::validate);}
 public SystemParserController(SystemParserRepository repository,ParserEngineSettings settings,ParserEngineRegistry registry,RemoteDocumentReader reader,Environment environment,Consumer<String> validate){this.repository=repository;this.settings=settings;this.registry=registry;this.reader=reader;this.environment=environment;this.validate=validate;}
 @GetMapping("/parser-engines") public ResponseEntity<Map<String,Object>> list(HttpServletRequest request){var tenant=tenant(request,"viewer");return result(tenant.config(),tenant.cloudAppId(),false,null);}
 @PostMapping("/parser-engines/check") public ResponseEntity<Map<String,Object>> check(HttpServletRequest request)throws IOException{
  var tenant=tenant(request,"admin");com.fasterxml.jackson.databind.JsonNode incoming;
  try{incoming=settings.request(request.getInputStream().readAllBytes());}catch(IOException|IllegalArgumentException ex){return error(400,"请求体格式错误");}
  return result(settings.merge(incoming,tenant.config()),tenant.cloudAppId(),false,null);
 }
 @PostMapping("/docreader/reconnect") public ResponseEntity<Map<String,Object>> reconnect(HttpServletRequest request)throws IOException{
  var auth=caller(request,"admin");String addr;
  try{addr=address(request.getInputStream().readAllBytes());if(addr.isEmpty())return error(400,"请提供 addr 参数");}catch(IOException|IllegalArgumentException ex){return error(400,"请提供 addr 参数");}
  addr=StoragePaths.trim(addr);if(addr.isEmpty())return error(400,"addr 不能为空");
  try{validate.accept(addr);}catch(ApiException ex){return error(400,ex.getMessage().replace("Base URL 未通过安全校验", "DocReader 地址 未通过安全校验"));}
  if(reader==null)return error(500,"document converter not initialized");
  try{reader.reconnect(addr);}catch(IOException|RuntimeException ex){return error(200,"连接失败: "+ex.getMessage());}
  var tenant=repository.tenant(auth.tenantId());return result(tenant.config(),tenant.cloudAppId(),true,addr);
 }
 private SystemParserRepository.Settings tenant(HttpServletRequest request,String role){return repository.tenant(caller(request,role).tenantId());}
 private static AuthContext caller(HttpServletRequest request,String role){var auth=CurrentAuth.get(request);auth.requireRole(role);if(auth.isApiKey()&&!auth.fullAccess()&&!auth.capabilities().contains("manage_vector_stores"))throw new ApiException(403,"FORBIDDEN","Forbidden: API key scope does not allow this operation");return auth;}
 private ResponseEntity<Map<String,Object>> result(JsonNode config,String cloudAppId,boolean reconnect,String newAddr){
  var overrides=new LinkedHashMap<String,String>();var values=ParserConfiguration.tenantOverrides(config);if(values!=null)overrides.putAll(values);if(!cloudAppId.isEmpty())overrides.put("weknoracloud_app_id",cloudAppId);
  Map<String,String> wire=overrides.isEmpty()?null:overrides;boolean connected=reconnect||reader!=null&&reader.isConnected();List<ParserEngineRegistry.Engine> remote=List.of();
  if(reader!=null&&reader.isConnected())try{var engines=reader.listEngines(wire);if(engines!=null)remote=engines.stream().map(e->new ParserEngineRegistry.Engine(e.name(),e.description(),e.fileTypesNil()?null:e.fileTypes(),e.available(),e.unavailableReason())).toList();}catch(RuntimeException ex){/* Go engine discovery fails to the static registry. */}
  var body=new LinkedHashMap<String,Object>();body.put("code",0);body.put("msg",reconnect?"连接成功":"success");body.put("data",registry.list(connected,wire,remote));body.put("docreader_addr",reconnect?newAddr:StoragePaths.trim(environment.getProperty("DOCREADER_ADDR","")));String transport=StoragePaths.trim(environment.getProperty("DOCREADER_TRANSPORT",""));body.put("docreader_transport",transport.isEmpty()?"grpc":transport.toLowerCase(java.util.Locale.ROOT));body.put("connected",connected);return ResponseEntity.ok(body);
 }
 private static ResponseEntity<Map<String,Object>> error(int status,String message){return ResponseEntity.status(status).body(Map.of("code",1,"msg",message));}
 private static String address(byte[] bytes)throws IOException{
  int length=GoJsonFirstValue.length(bytes);String result="";try(var parser=new ObjectMapper().createParser(GoUtf8.decode(Arrays.copyOf(bytes,length)))){
   var token=parser.nextToken();if(token==JsonToken.VALUE_NULL)return result;if(token!=JsonToken.START_OBJECT)throw new IllegalArgumentException();String key;while((key=parser.nextFieldName())!=null){token=parser.nextToken();if(key.equalsIgnoreCase("addr")){if(token==JsonToken.VALUE_STRING)result=parser.getText();else if(token!=JsonToken.VALUE_NULL)throw new IllegalArgumentException();}else parser.skipChildren();}return result;
  }
 }
}
