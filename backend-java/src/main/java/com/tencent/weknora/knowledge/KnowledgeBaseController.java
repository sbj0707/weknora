package com.tencent.weknora.knowledge;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Locale;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseRepository repository;
    private final KnowledgeBaseLifecycle lifecycle;private final KnowledgeBaseReadAuthorization reads;
    private KnowledgeProcessingLanguage language;
    @org.springframework.beans.factory.annotation.Autowired void processingLanguage(KnowledgeProcessingLanguage language){this.language=language;}
    public KnowledgeBaseController(KnowledgeBaseRepository repository,KnowledgeBaseLifecycle lifecycle,KnowledgeBaseReadAuthorization reads) {this.repository=repository;this.lifecycle=lifecycle;this.reads=reads;}
    @PostMapping @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String,Object> create(HttpServletRequest request,@RequestBody com.fasterxml.jackson.databind.node.ObjectNode body) {return Map.of("success",true,"data",lifecycle.create(CurrentAuth.get(request),body));}
    @PutMapping("/{id}") public Map<String,Object> update(HttpServletRequest request,@PathVariable String id,@RequestBody com.fasterxml.jackson.databind.node.ObjectNode body) {return Map.of("success",true,"data",lifecycle.update(CurrentAuth.get(request),id,body,language.resolve(request)));}
    @GetMapping public Map<String,Object> list(HttpServletRequest request, @RequestParam(defaultValue="") String creator, @RequestParam(name="agent_id",defaultValue="") String agentId) {
        var auth=CurrentAuth.get(request);auth.requireRole("viewer");auth.requireCapability("retrieve");
        if(!agentId.isBlank()) throw new ApiException(501,"NOT_MIGRATED","Shared-agent knowledge base access is not migrated");
        String filter=creator.trim().toLowerCase(Locale.ROOT);
        var data=repository.list(auth.tenantId(),auth.userId()).stream().filter(kb -> auth.allowsKnowledgeBase(kb.path("id").asText()))
            .filter(kb -> {String owner=kb.path("creator_id").asText();return switch(filter){case "mine" -> !owner.isEmpty()&&owner.equals(auth.userId());case "others" -> !owner.isEmpty()&&!owner.equals(auth.userId());default -> true;};}).toList();
        return Map.of("success",true,"data",data);
    }
    @GetMapping("/{id}") public Map<String,Object> get(HttpServletRequest request,@PathVariable String id,@RequestParam(name="agent_id",defaultValue="") String agentId,@RequestParam(name="agent_source_tenant_id",defaultValue="") String sourceTenant) {
        var auth=CurrentAuth.get(request);auth.requireRole("viewer");var grant=reads.authorizeRead(auth,id,agentId,sourceTenant);
        return Map.of("success",true,"data",repository.getForRead(grant,auth.tenantId()));
    }
    @PutMapping("/{id}/pin") public Map<String,Object> pin(HttpServletRequest request,@PathVariable String id) {
        var auth=CurrentAuth.get(request);auth.requireRole("viewer");auth.requireCapability("retrieve");auth.requireKnowledgeBase(id);
        repository.get(auth.tenantId(),id).orElseThrow(() -> new ApiException(404,"NOT_FOUND","knowledge base not found"));
        return Map.of("success",true,"data",repository.togglePin(auth.tenantId(),auth.userId(),id));
    }
    @GetMapping("/{id}/move-targets") public Map<String,Object> moveTargets(HttpServletRequest request,@PathVariable String id,
        @RequestParam(name="agent_id",defaultValue="") String agentId,@RequestParam(name="agent_source_tenant_id",defaultValue="") String sourceTenant) {
        var auth=CurrentAuth.get(request);auth.requireRole("viewer");
        reads.authorizeRead(auth,id,agentId,sourceTenant);
        return Map.of("success",true,"data",repository.moveTargets(auth.tenantId(),auth.userId(),id));
    }
}
