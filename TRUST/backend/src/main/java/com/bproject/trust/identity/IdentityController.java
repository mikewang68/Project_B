package com.bproject.trust.identity;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
  private final CurrentIdentity identity;

  public IdentityController(CurrentIdentity identity) {
    this.identity = identity;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/me")
  public Map<String, Object> me(Authentication a) {
    return Map.of(
        "username",
        a.getName(),
        "orgId",
        identity.org(a),
        "roles",
        a.getAuthorities().stream().map(Object::toString).toList());
  }
}
