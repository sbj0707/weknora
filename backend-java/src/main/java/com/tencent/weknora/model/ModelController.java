package com.tencent.weknora.model;

import com.tencent.weknora.security.ApiException;
import com.tencent.weknora.security.AuthContext;
import com.tencent.weknora.security.CurrentAuth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Native read slice. Remote chat capabilities and all mutations remain fail closed. */
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {
    private final ModelRepository repository;
    public ModelController(ModelRepository repository) { this.repository = repository; }

    @GetMapping
    public Object list(HttpServletRequest request) {
        // The actual Go handler does not read type/source/page query parameters.
        return Map.of("success", true, "data", repository.list(viewer(request)));
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable String id, HttpServletRequest request) {
        AuthContext auth = viewer(request);
        id = id.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replaceAll("[\\x00-\\x1F]", "");
        if (id.isEmpty()) throw new ApiException(400, "BAD_REQUEST", "Model ID cannot be empty");
        return Map.of("success", true, "data", repository.get(auth, id));
    }

    @RequestMapping("/{id}/**")
    public Object unsupported(HttpServletRequest request) {
        viewer(request);
        throw new ApiException(503, "MODEL_ROUTE_NOT_MIGRATED", "Model route is not migrated");
    }

    private static AuthContext viewer(HttpServletRequest request) {
        AuthContext auth = CurrentAuth.get(request);
        // The filter intentionally does not authorize model routes for machine credentials yet.
        if (auth.isApiKey()) throw new ApiException(403, "FORBIDDEN", "Forbidden: API key model routes are not migrated");
        auth.requireRole("viewer");
        if (auth.tenantId() <= 0) throw new ApiException(400, "BAD_REQUEST", "Workspace ID cannot be empty");
        return auth;
    }
}
