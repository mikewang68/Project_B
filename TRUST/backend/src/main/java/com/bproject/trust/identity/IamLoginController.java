package com.bproject.trust.identity;

import com.bproject.trust.ports.PlatformIdentity;
import com.bproject.trust.shared.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/iam")
public class IamLoginController {
  private final PlatformIdentity iam;
  private final IntegrationSettings settings;

  public IamLoginController(
      @Qualifier("iamClient") PlatformIdentity iamClient,
      com.bproject.trust.adapters.iam.SimulatedIamClient simulatedIam,
      IntegrationSettings settings) {
    this.settings = settings;
    this.iam = settings.read().simulated() ? simulatedIam : iamClient;
  }

  public record Login(
      @NotBlank @Size(max = 80) String username,
      @NotBlank @Size(max = 256) String password,
      @NotBlank @Size(max = 80) String orgId) {}

  @PostMapping("/login")
  public Map<String, Boolean> login(
      @Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
    String token = iam.login(body.username(), body.password());
    var user = iam.current(token);
    if (!user.orgCodes().stream()
        .anyMatch(code -> body.orgId().equals(settings.read().orgMap().get(code))))
      throw new ApiError(403, "当前用户没有该业务范围的授权映射");
    var old = request.getSession(false);
    if (old != null) old.invalidate();
    SecurityContextHolder.clearContext();
    var session = request.getSession(true);
    session.setAttribute("IAM_TOKEN", token);
    session.setAttribute("IAM_ORG", body.orgId());
    session.setAttribute("IAM_USER", user.id());
    new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
    response.setHeader("Cache-Control", "no-store");
    return Map.of("ok", true);
  }
}
