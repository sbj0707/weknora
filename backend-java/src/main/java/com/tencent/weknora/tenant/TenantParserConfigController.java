package com.tencent.weknora.tenant;

import com.tencent.weknora.security.CurrentAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants/kv/parser-engine-config")
public final class TenantParserConfigController {
    private final TenantParserConfigService service;
    public TenantParserConfigController(TenantParserConfigService service) { this.service = service; }
    @GetMapping public Map<String,Object> get(HttpServletRequest request) { return service.get(CurrentAuth.get(request)); }
    @PutMapping public Map<String,Object> put(HttpServletRequest request) throws IOException {
        return service.update(CurrentAuth.get(request), request.getInputStream().readAllBytes());
    }
}
