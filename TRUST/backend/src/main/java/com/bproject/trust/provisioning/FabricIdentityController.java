package com.bproject.trust.provisioning;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identities")
public class FabricIdentityController {
  private final FabricIdentityService service;

  public FabricIdentityController(FabricIdentityService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(
      @RequestAttribute("IDENTITY_CALLER") IdentityProviderSettings.Client caller,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody FabricIdentityService.Command body) {
    var result = service.provision(caller, key, body);
    return ResponseEntity.status("READY".equals(result.get("status")) ? 200 : 202).body(result);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(
      @RequestAttribute("IDENTITY_CALLER") IdentityProviderSettings.Client caller,
      @PathVariable String id) {
    return service.get(caller, id);
  }

  @PostMapping("/{id}/{operation}")
  public Map<String, Object> lifecycle(
      @RequestAttribute("IDENTITY_CALLER") IdentityProviderSettings.Client caller,
      @PathVariable String id,
      @PathVariable String operation,
      @RequestHeader("Idempotency-Key") String key,
      @RequestHeader("If-Match") long revision) {
    return service.lifecycle(caller, id, key, revision, operation);
  }
}
