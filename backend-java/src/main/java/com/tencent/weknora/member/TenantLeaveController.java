package com.tencent.weknora.member;

import com.tencent.weknora.security.CurrentAuth;
import com.tencent.weknora.security.TenantMemberService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class TenantLeaveController {
    private final TenantMemberService service;
    public TenantLeaveController(TenantMemberService service){this.service=service;}
    @PostMapping("/api/v1/tenants/{tenantId}/leave")
    public Map<String,Object> leave(HttpServletRequest request,@PathVariable String tenantId){return service.leave(CurrentAuth.get(request),tenantId);}
}
