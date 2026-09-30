package com.bproject.trust.provisioning;

import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Separate machine API: no IAM callback, browser session, WMS credential or CSRF cookie. */
@Configuration
public class IdentityApiSecurity {
  @Bean
  @Order(1)
  SecurityFilterChain identities(HttpSecurity http, IdentityProviderSettings settings)
      throws Exception {
    return http.securityMatcher("/api/v1/identities", "/api/v1/identities/**")
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable())
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable())
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  jakarta.servlet.http.HttpServletRequest req,
                  jakarta.servlet.http.HttpServletResponse res,
                  jakarta.servlet.FilterChain chain)
                  throws java.io.IOException, jakarta.servlet.ServletException {
                try {
                  String auth = req.getHeader("Authorization");
                  if (auth == null || !auth.startsWith("Bearer ") || auth.length() > 512)
                    throw new SecurityException();
                  String hash = Json.sha(auth.substring(7).getBytes(StandardCharsets.UTF_8));
                  var caller =
                      settings.read().clients().stream()
                          .filter(
                              c ->
                                  MessageDigest.isEqual(
                                      hash.getBytes(StandardCharsets.US_ASCII),
                                      c.tokenSha256().getBytes(StandardCharsets.US_ASCII)))
                          .findFirst()
                          .orElseThrow(SecurityException::new);
                  req.setAttribute("IDENTITY_CALLER", caller);
                  SecurityContextHolder.getContext()
                      .setAuthentication(
                          UsernamePasswordAuthenticationToken.authenticated(
                              caller, null, List.of()));
                } catch (SecurityException e) {
                  error(res, 401, "身份服务凭据无效");
                  return;
                } catch (IllegalStateException e) {
                  error(res, 503, "身份供给配置不可用");
                  return;
                }
                try {
                  chain.doFilter(req, res);
                } finally {
                  SecurityContextHolder.clearContext();
                }
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private static void error(
      jakarta.servlet.http.HttpServletResponse res, int status, String message)
      throws java.io.IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(Json.write(java.util.Map.of("message", message)));
  }
}
