package com.bproject.trust.identity;

import com.bproject.trust.shared.json.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/dev-login")
@ConditionalOnProperty(name = "trust.dev-quick-login.enabled", havingValue = "true")
public class DevLoginController {
  private static final Set<String> ALLOWED = Set.of("admin", "editor", "viewer", "external-viewer");
  private final Path accounts;
  private final AuthenticationConfiguration authentication;

  public DevLoginController(@Value("${trust.root}") String root, AuthenticationConfiguration authentication) {
    this.accounts = Path.of(root, "runtime/secrets/users.json");
    this.authentication = authentication;
  }

  private List<Map<String, String>> accounts() throws Exception {
    List<Map<String, String>> values = Json.MAPPER.readValue(Files.readString(accounts), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    return values.stream().filter(v -> ALLOWED.contains(v.get("username"))).toList();
  }

  @GetMapping
  public Map<String, Object> list(HttpServletResponse response) throws Exception {
    response.setHeader("Cache-Control", "no-store");
    return Map.of("accounts", accounts().stream().map(v -> Map.of("username", v.get("username"), "role", v.get("role"), "orgId", v.get("orgId"))).toList());
  }

  @PostMapping
  public Map<String, Boolean> login(@RequestBody Map<String, String> body, HttpServletRequest request, HttpServletResponse response) throws Exception {
    var account = accounts().stream().filter(v -> v.get("username").equals(body.get("username"))).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    var auth = authentication.getAuthenticationManager().authenticate(
        UsernamePasswordAuthenticationToken.unauthenticated(account.get("username"), account.get("password")));
    new ChangeSessionIdAuthenticationStrategy().onAuthentication(auth, request, response);
    new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository()).onAuthentication(auth, request, response);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(auth);
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, request, response);
    response.setHeader("Cache-Control", "no-store");
    return Map.of("ok", true);
  }
}
