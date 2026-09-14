package com.bproject.trust.audit;

import com.bproject.trust.identity.CurrentIdentity;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuditController {
  private final CurrentIdentity identity;
  private final AuditService audit;

  public AuditController(CurrentIdentity identity, AuditService audit) {
    this.identity = identity;
    this.audit = audit;
  }

  @GetMapping("/audit")
  @PreAuthorize("hasRole('ADMIN')")
  public List<Map<String, Object>> audit(Authentication a) {
    return audit.list(identity.org(a));
  }
}
