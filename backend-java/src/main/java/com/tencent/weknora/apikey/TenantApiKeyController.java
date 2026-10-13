package com.tencent.weknora.apikey;

import com.tencent.weknora.security.CurrentAuth;
import com.tencent.weknora.security.TenantApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/api-keys")
public class TenantApiKeyController {
    private final TenantApiKeyService service;
    public TenantApiKeyController(TenantApiKeyService service){this.service=service;}
    @GetMapping public Map<String,Object> list(HttpServletRequest request,@PathVariable String tenantId){return service.list(CurrentAuth.get(request),tenantId);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(HttpServletRequest request,@PathVariable String tenantId,@RequestBody Map<String,Object> body){return service.create(CurrentAuth.get(request),tenantId,body);}
    @PutMapping("/{keyId}") public Map<String,Object> update(HttpServletRequest request,@PathVariable String tenantId,@PathVariable String keyId,@RequestBody Map<String,Object> body){return service.update(CurrentAuth.get(request),tenantId,keyId,body);}
    @DeleteMapping("/{keyId}") public Map<String,Object> revoke(HttpServletRequest request,@PathVariable String tenantId,@PathVariable String keyId){return service.revoke(CurrentAuth.get(request),tenantId,keyId);}
}
