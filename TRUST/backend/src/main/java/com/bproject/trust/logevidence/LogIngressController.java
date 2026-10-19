package com.bproject.trust.logevidence;

import com.bproject.trust.ingress.IngressError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(LogSecurity.BASE)
public class LogIngressController {
  private final LogEvidenceService logs;

  @org.springframework.beans.factory.annotation.Autowired
  private LogEvidenceVerification verification;

  public LogIngressController(LogEvidenceService logs) {
    this.logs = logs;
  }

  @PostMapping
  public ResponseEntity<?> submit(
      @RequestAttribute("LOG_SOURCE") LogSettings.Source source, HttpServletRequest req)
      throws Exception {
    if (req.getContentType() == null
        || !req.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))
      throw new IngressError(415, "JSON_REQUIRED", "仅接收 JSON");
    var result =
        logs.submit(source, LogInput.parse(req.getInputStream().readNBytes(LogInput.LIMIT + 1)));
    return ResponseEntity.status(result.status()).body(result.receipt());
  }

  @GetMapping("/{key}")
  public Object receipt(
      @RequestAttribute("LOG_SOURCE") LogSettings.Source s, @PathVariable String key) {
    return logs.receipt(s, key);
  }

  @PostMapping("/{key}/retry")
  public ResponseEntity<?> retry(
      @RequestAttribute("LOG_SOURCE") LogSettings.Source s,
      @PathVariable String key,
      HttpServletRequest req)
      throws Exception {
    var body = req.getInputStream().readNBytes(1025);
    try {
      var node = LogInput.MAPPER.readTree(body);
      if (body.length > 1024 || node == null || !node.isObject() || !node.isEmpty())
        throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new IngressError(400, "INVALID_RETRY", "补办请求必须是空对象");
    }
    var result = logs.retry(s, key);
    return ResponseEntity.status(result.status()).body(result.receipt());
  }

  @GetMapping("/{key}/original")
  public ResponseEntity<?> original(
      @RequestAttribute("LOG_SOURCE") LogSettings.Source s,
      @PathVariable String key,
      @RequestParam(defaultValue = "true") boolean fromArchive) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(logs.original(s, key, fromArchive));
  }

  @GetMapping("/{key}/verification")
  public Object verify(
      @RequestAttribute("LOG_SOURCE") LogSettings.Source s, @PathVariable String key) {
    return verification.verify(s, key);
  }
}
