package com.tencent.weknora.member;

import com.tencent.weknora.security.CurrentAuth;
import com.tencent.weknora.security.ApiException;
import com.tencent.weknora.security.TenantMemberService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/members")
public class TenantMemberController {
    private final TenantMemberService service;
    public TenantMemberController(TenantMemberService service){this.service=service;}
    @GetMapping public Map<String,Object> list(HttpServletRequest request,@PathVariable String tenantId,@RequestParam(required=false)String q,@RequestParam(required=false)String page,@RequestParam(name="page_size",required=false)String pageSize){return respond(request,"viewer",false,()->service.list(CurrentAuth.get(request),tenantId,q,page,pageSize));}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> add(HttpServletRequest request,@PathVariable String tenantId,@RequestBody Map<String,Object> body){return respond(request,"owner",false,()->service.add(CurrentAuth.get(request),tenantId,body));}
    @PutMapping("/{userId}") public Map<String,Object> update(HttpServletRequest request,@PathVariable String tenantId,@PathVariable String userId,@RequestBody Map<String,Object> body){return respond(request,"owner",true,()->service.update(CurrentAuth.get(request),tenantId,userId,body));}
    @DeleteMapping("/{userId}") public Map<String,Object> remove(HttpServletRequest request,@PathVariable String tenantId,@PathVariable String userId){return respond(request,"owner",true,()->service.remove(CurrentAuth.get(request),tenantId,userId));}
    private Map<String,Object> respond(HttpServletRequest request,String required,boolean item,java.util.function.Supplier<Map<String,Object>> operation){try{return operation.get();}catch(ApiException ex){if(ex.status()==403&&ex.getMessage().equals("Forbidden: insufficient workspace role")){service.auditDenied(CurrentAuth.get(request),request.getRequestURI(),request.getMethod(),required,item);ex.withResponse(Map.of("error",ex.getMessage()));}throw ex;}}
}
