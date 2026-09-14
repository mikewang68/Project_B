package com.bproject.trust.trace;

import com.bproject.trust.audit.AuditService;
import com.bproject.trust.identity.CurrentIdentity;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TraceController {
  private final CurrentIdentity identity;
  private final TraceService trace;
  private final AuditService audit;

  public TraceController(CurrentIdentity identity, TraceService trace, AuditService audit) {
    this.identity = identity;
    this.trace = trace;
    this.audit = audit;
  }

  @GetMapping("/trace")
  public Map<String, Object> trace(
      Authentication a, @RequestParam String kind, @RequestParam String value) {
    var scope = identity.org(a);
    var result = trace.trace(scope, kind, value);
    audit.record(scope, a.getName(), "TRACE_QUERY", null, kind + ":" + value);
    return result;
  }
}
