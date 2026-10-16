package com.tencent.weknora.vectorstore;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tencent.weknora.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vector-stores")
public final class VectorStorePublicController {
  private final VectorStorePublicService service;
  private final VectorStoreEnvironment environment;
  private final VectorStorePublicForms forms;

  public VectorStorePublicController(
      VectorStorePublicService service,
      VectorStoreEnvironment environment,
      VectorStorePublicForms forms) {
    this.service = service;
    this.environment = environment;
    this.forms = forms;
  }

  private long owner(HttpServletRequest request) {
    long id = CurrentAuth.get(request).tenantId();
    if (id == 0)
      throw VectorStorePublicForms.direct(401, "unauthorized: workspace context missing");
    return id;
  }

  @GetMapping
  public Map<String, Object> list(HttpServletRequest request) {
    return Map.of("success", true, "data", service.list(owner(request)));
  }

  @GetMapping("/types")
  public Map<String, Object> types() {
    return Map.of("success", true, "data", environment.types());
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(HttpServletRequest request, @PathVariable String id) {
    return Map.of(
        "success", true, "data", VectorStoreEnvironment.mask(service.get(owner(request), id)));
  }

  @PostMapping
  public ResponseEntity<?> create(HttpServletRequest request) throws IOException {
    long owner = owner(request);
    return ResponseEntity.status(201)
        .body(
            Map.of(
                "success",
                true,
                "data",
                service.create(
                    owner, forms.decode(request.getInputStream().readAllBytes(), "create"))));
  }

  @PutMapping("/{id}")
  public Map<String, Object> update(HttpServletRequest request, @PathVariable String id)
      throws IOException {
    long owner = owner(request);
    modifiable(id);
    service.get(owner, id);
    var value =
        service.update(owner, id, forms.decode(request.getInputStream().readAllBytes(), "update"));
    var response = new LinkedHashMap<String, Object>();
    response.put("success", true);
    response.put("data", value);
    return response;
  }

  @DeleteMapping("/{id}")
  public Map<String, Object> delete(HttpServletRequest request, @PathVariable String id) {
    long owner = owner(request);
    modifiable(id);
    service.get(owner, id);
    service.delete(owner, id);
    return Map.of("success", true);
  }

  @PostMapping("/test")
  public Map<String, Object> testRaw(HttpServletRequest request) throws IOException {
    owner(request);
    var body = forms.decode(request.getInputStream().readAllBytes(), "test");
    try {
      return Map.of("success", true, "version", service.testRaw(body));
    } catch (ApiException error) {
      if (error.status() == 503) throw error;
      return Map.of("success", false, "error", VectorStorePublicService.goError(error));
    }
  }

  @PostMapping("/{id}/test")
  public Map<String, Object> testStored(HttpServletRequest request, @PathVariable String id) {
    long owner = owner(request);
    var row = service.get(owner, id);
    try {
      return Map.of("success", true, "version", service.testStored(owner, row));
    } catch (ApiException error) {
      if (error.status() == 503) throw error;
      return Map.of("success", false, "error", VectorStorePublicService.goError(error));
    }
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public ResponseEntity<?> database(org.springframework.dao.DataAccessException ignored) {
    return ResponseEntity.status(500)
        .body(
            Map.of(
                "success",
                false,
                "error",
                Map.of("code", 1007, "message", "Internal server error")));
  }

  private void modifiable(String id) {
    if (id.startsWith("__env_"))
      throw VectorStorePublicForms.direct(
          400, "environment-configured vector stores cannot be modified via API");
  }
}
