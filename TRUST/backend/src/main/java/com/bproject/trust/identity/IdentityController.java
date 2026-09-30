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

  private final IntegrationSettings settings;

  public IdentityController(CurrentIdentity identity, IntegrationSettings settings) {
    this.identity = identity;
    this.settings = settings;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/identity-mode")
  public Map<String, Object> mode(jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    var configuration = settings.read();
    return Map.of("simulated", configuration.simulated(), "iamRequired", configuration.iamRequired(),
        "localLoginEnabled", settings.localLoginEnabled(configuration));
  }

  @GetMapping("/me")
  public Map<String, Object> me(Authentication a) {
    return Map.of(
        "username",
        a.getPrincipal() instanceof PlatformPrincipal p ? p.username() : a.getName(),
        "orgId",
        identity.org(a),
        "roles",
        a.getAuthorities().stream().map(Object::toString).toList(),
        "identityProvider",
        a.getPrincipal() instanceof PlatformPrincipal ? "IAM" : "LOCAL",
        "simulated",
        a.getPrincipal() instanceof PlatformPrincipal && settings.read().simulated());
  }
}
