package com.tencent.weknora.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/models")
public class ModelMutationController {
    private final ModelMutations mutations;
    public ModelMutationController(ModelMutations mutations){this.mutations=mutations;}
    @PostMapping public ResponseEntity<?> create(@RequestBody JsonNode body,HttpServletRequest request){return ResponseEntity.status(201).body(Map.of("success",true,"data",mutations.create(auth(request,false),body)));}
    @PutMapping("/{id}") public Object update(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest request){return Map.of("success",true,"data",mutations.update(auth(request,true),id(id),body));}
    @DeleteMapping("/{id}") public Object delete(@PathVariable String id,HttpServletRequest request){mutations.delete(auth(request,false),id(id));return Map.of("success",true,"message","Model deleted");}
    @PutMapping("/{id}/credentials") public Object credentials(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest request){return Map.of("success",true,"data",mutations.credentials(auth(request,true),id,body,false,""));}
    @DeleteMapping("/{id}/credentials/{field}") public ResponseEntity<?> clear(@PathVariable String id,@PathVariable String field,HttpServletRequest request){AuthContext auth=auth(request,true);if(!field.equals("api_key")&&!field.equals("app_secret"))throw ModelParametersCodec.bad("unknown credential field: "+ModelMutations.sanitize(field));mutations.credentials(auth,id,null,true,field);return ResponseEntity.noContent().build();}
    private static String id(String id){String sanitized=ModelMutations.sanitize(id);if(sanitized.isEmpty())throw ModelParametersCodec.bad("Model ID cannot be empty");return sanitized;}
    private static AuthContext auth(HttpServletRequest request,boolean systemBypass){AuthContext auth=CurrentAuth.get(request);if(auth.isApiKey())throw new ApiException(403,"FORBIDDEN","Forbidden: API key model routes are not migrated");if(!systemBypass||!auth.systemAdmin())auth.requireRole("admin");if(auth.tenantId()<=0)throw ModelParametersCodec.bad("Workspace ID cannot be empty");return auth;}
}
