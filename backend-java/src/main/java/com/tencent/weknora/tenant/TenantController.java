package com.tencent.weknora.tenant;

import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {
    private final TenantService service;
    public TenantController(TenantService service){this.service=service;}
    @GetMapping public Map<String,Object> current(HttpServletRequest request){return service.current(CurrentAuth.get(request));}
    @GetMapping("/all") public Map<String,Object> all(HttpServletRequest request){return service.all(CurrentAuth.get(request));}
    @GetMapping("/search") public Map<String,Object> search(HttpServletRequest request,@RequestParam(required=false)String keyword,@RequestParam(name="tenant_id",required=false)String id,@RequestParam(required=false)String page,@RequestParam(name="page_size",required=false)String pageSize){return service.search(CurrentAuth.get(request),keyword,id,page,pageSize);}
    @GetMapping("/{id}") public Map<String,Object> detail(HttpServletRequest request,@PathVariable String id){return guarded(request,"viewer","/api/v1/tenants/:id",()->service.detail(CurrentAuth.get(request),id));}
    @PutMapping("/{id}") public Map<String,Object> update(HttpServletRequest request,@PathVariable String id,@RequestBody String body){return guarded(request,"owner","/api/v1/tenants/:id",()->service.update(CurrentAuth.get(request),id,body));}
    @GetMapping({"/kv/retrieval-config","/kv/memory-config"}) public Map<String,Object> config(HttpServletRequest request){return guarded(request,"viewer","/api/v1/tenants/kv/:key",()->service.config(CurrentAuth.get(request),key(request)));}
    @PutMapping({"/kv/retrieval-config","/kv/memory-config"}) public Map<String,Object> updateConfig(HttpServletRequest request,@RequestBody String body){return guarded(request,"admin","/api/v1/tenants/kv/:key",()->service.updateConfig(CurrentAuth.get(request),key(request),body));}
    private static String key(HttpServletRequest request){return request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1);}
    private Map<String,Object> guarded(HttpServletRequest request,String role,String template,java.util.function.Supplier<Map<String,Object>> operation){try{return operation.get();}catch(ApiException ex){if(ex.status()==403&&ex.getMessage().equals("Forbidden: insufficient workspace role")){service.auditDenied(CurrentAuth.get(request),request.getRequestURI(),template,request.getMethod(),role);ex.withResponse(Map.of("error",ex.getMessage()));}throw ex;}}
}
