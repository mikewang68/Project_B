package com.bproject.trust.operations;

import com.bproject.trust.identity.CurrentIdentity;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class OperationsController {
  private final CurrentIdentity identity;
  private final OperationsService operations;

  public OperationsController(CurrentIdentity identity, OperationsService operations) {
    this.identity = identity;
    this.operations = operations;
  }

  @GetMapping("/status")
  public Map<String, Object> status(Authentication a) {
    return operations.status(identity.org(a));
  }
}
