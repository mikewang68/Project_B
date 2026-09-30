package com.bproject.trust.identity;

import com.bproject.trust.ports.PlatformIdentity;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Revalidates IAM on every request; tokens and current permissions stay server-side. */
public class PlatformIdentityFilter extends OncePerRequestFilter {
  private final PlatformIdentity iam;
  private final IntegrationSettings settings;

  public PlatformIdentityFilter(PlatformIdentity iam, IntegrationSettings settings) {
    this.iam = iam;
    this.settings = settings;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String path = request.getServletPath();
    if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
    // Login-page metadata remains available even during an IAM outage.
    if (path.equals("/api/v1/identity-mode")) {
      chain.doFilter(request, response);
      return;
    }
    try {
      if (path.startsWith("/api/v1/integrations/")) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer "))
          throw new ApiError(401, "需要来源系统凭据");
        String hash = Json.sha(authorization.substring(7).getBytes(StandardCharsets.UTF_8));
        var service =
            settings.read().services().stream()
                .filter(
                    s ->
                        MessageDigest.isEqual(
                            hash.getBytes(StandardCharsets.US_ASCII),
                            s.tokenSha256().getBytes(StandardCharsets.US_ASCII)))
                .findFirst()
                .orElseThrow(() -> new ApiError(401, "来源系统凭据无效"));
        request.setAttribute("TRUST_SERVICE", service);
        SecurityContextHolder.getContext()
            .setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                    service.id(),
                    null,
                    java.util.List.of(new SimpleGrantedAuthority("trust:source:submit"))));
      } else {
        var session = request.getSession(false);
        var configuration = settings.read();
        boolean localLoginEnabled = settings.localLoginEnabled(configuration);
        boolean localLoginRequest =
            localLoginEnabled && (path.equals("/api/v1/login") || path.equals("/api/v1/dev-login"));
        if (path.startsWith("/api/v1/")
            && !path.equals("/api/v1/iam/login")
            && !path.equals("/api/v1/csrf")
            && !path.equals("/api/v1/logout")
            && configuration.iamRequired()
            && !localLoginEnabled
            && (session == null || session.getAttribute("IAM_TOKEN") == null))
          throw new ApiError(401, "此环境已接入 IAM，请使用平台账号登录");
        if (session != null
            && session.getAttribute("IAM_TOKEN") instanceof String token
            && !localLoginRequest
            && !path.equals("/api/v1/iam/login")
            && !path.equals("/api/v1/logout")) {
          var user = iam.current(token);
          String org = (String) session.getAttribute("IAM_ORG");
          if (!user.id().equals(session.getAttribute("IAM_USER"))
              || !user.orgCodes().stream()
                  .anyMatch(code -> org.equals(settings.read().orgMap().get(code))))
            throw new ApiError(403, "当前业务范围授权已失效");
          var authorities = new ArrayList<SimpleGrantedAuthority>();
          user.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
          // Legacy controllers retain role checks in addition to the granular checks below.
          if (user.permissions().contains("trust:event:submit")
              || user.permissions().contains("trust:event:correct")
              || user.permissions().contains("trust:task:retry")
              || user.permissions().contains("trust:evidence:upload"))
            authorities.add(new SimpleGrantedAuthority("ROLE_EDITOR"));
          if (user.permissions().contains("trust:audit:read"))
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
          var principal = new PlatformPrincipal(user.id(), user.username(), org);
          SecurityContextHolder.getContext()
              .setAuthentication(
                  UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
          String permission = requiredPermission(path, request.getMethod());
          if (permission != null && !user.permissions().contains(permission))
            throw new ApiError(403, "缺少权限：" + permission);
        }
      }
    } catch (ApiError e) {
      SecurityContextHolder.clearContext();
      response.setStatus(e.status);
      response.setContentType("application/json;charset=UTF-8");
      response.getWriter().write(Json.write(java.util.Map.of("message", e.getMessage())));
      return;
    } catch (IllegalStateException e) {
      SecurityContextHolder.clearContext();
      response.sendError(503, "身份接入配置不可用");
      return;
    }
    chain.doFilter(request, response);
  }

  public static String requiredPermission(String path, String method) {
    if (!path.startsWith("/api/v1/")) return null;
    if (path.startsWith("/api/v1/identity-management")) return "trust:identity:read";
    if (path.startsWith("/api/v1/wallets")) return "trust:wallet:read";
    if (path.endsWith("/export")) return "trust:evidence:export";
    if (path.endsWith("/verify")) return "trust:evidence:verify";
    if (path.endsWith("/retry")) return "trust:task:retry";
    if (path.startsWith("/api/v1/evidence"))
      return method.equals("GET") ? "trust:evidence:read" : "trust:evidence:upload";
    if (path.contains("/corrections")) return "trust:event:correct";
    if (path.startsWith("/api/v1/events") || path.startsWith("/api/v1/import"))
      return method.equals("GET") ? "trust:event:read" : "trust:event:submit";
    if (path.startsWith("/api/v1/trace")) return "trust:event:read";
    if (path.startsWith("/api/v1/tasks"))
      return method.equals("GET") ? "trust:task:read" : "trust:task:retry";
    if (path.startsWith("/api/v1/audit")) return "trust:audit:read";
    if (path.startsWith("/api/v1/status")) return "trust:operations:read";
    return null;
  }
}
