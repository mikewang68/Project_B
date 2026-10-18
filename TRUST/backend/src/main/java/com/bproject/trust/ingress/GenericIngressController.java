package com.bproject.trust.ingress;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(EvidenceIngressSecurity.BASE)
public class GenericIngressController {
  private final GenericEventService service;
  private final String instance;
  private final boolean isolated;

  public GenericIngressController(
      GenericEventService service,
      @Value("${trust.instance:}") String instance,
      @Value("${trust.isolation-enabled:false}") boolean isolated) {
    this.service = service;
    this.instance = instance;
    this.isolated = isolated;
  }

  private EvidenceIngressSettings.Source source(HttpServletRequest request) {
    if (!(request.getAttribute("EVIDENCE_SOURCE") instanceof EvidenceIngressSettings.Source s))
      throw new IngressError(401, "INVALID_SOURCE_CREDENTIAL", "通用来源凭据无效");
    return s;
  }

  @PostMapping
  public ResponseEntity<?> submit(HttpServletRequest request) throws java.io.IOException {
    var s = source(request);
    s.require("submit");
    GenericHttpInput.mediaType(request.getContentType(), request.getHeader("Content-Encoding"));
    byte[] bytes = request.getInputStream().readNBytes(GenericEventInput.LIMIT + 1);
    var result = service.submit(s, GenericEventInput.parse(bytes));
    return ResponseEntity.status(result.status())
        .location(URI.create(EvidenceIngressSecurity.BASE + "/" + result.receipt().get("id")))
        .body(result.receipt());
  }

  @GetMapping("/capabilities")
  public Map<String, Object> capabilities(HttpServletRequest req) {
    source(req).require("read");
    return Map.of(
        "instanceId",
        instance,
        "isolationEnabled",
        isolated,
        "ingressSchemaVersions",
        java.util.List.of(1));
  }

  @GetMapping("/by-source-event/{number}")
  public Map<String, Object> byNumber(HttpServletRequest req, @PathVariable String number) {
    return service.byNumber(source(req), number);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(HttpServletRequest req, @PathVariable String id) {
    return service.receipt(source(req), id);
  }

  @PostMapping("/{id}/retry")
  public ResponseEntity<?> retry(HttpServletRequest req, @PathVariable String id)
      throws java.io.IOException {
    var s = source(req);
    s.require("retry");
    byte[] bytes = req.getInputStream().readNBytes(65);
    GenericHttpInput.retry(bytes);
    var result = service.retry(s, id);
    return ResponseEntity.status(result.status()).body(result.receipt());
  }
}
