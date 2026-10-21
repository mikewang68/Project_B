package com.bproject.trust.logevidence;

import com.bproject.trust.ingress.IngressError;
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

@Configuration
public class LogSecurity {
  public static final String BASE = "/api/v1/integrations/audit/events";

  @Bean
  @Order(3)
  SecurityFilterChain logIngress(HttpSecurity http, LogSettings settings) throws Exception {
    return http.securityMatcher(BASE, BASE + "/**")
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
                SecurityContextHolder.clearContext();
                try {
                  var cfg = settings.read();
                  if (!cfg.enabled()) throw new IngressError(404, "LOG_DISABLED", "日志接入未启用");
                  String auth = req.getHeader("Authorization");
                  if (auth == null || !auth.startsWith("Bearer ") || auth.length() > 512)
                    throw unauthorized();
                  String hash = LogInput.hash(auth.substring(7));
                  var source =
                      cfg.sources().stream()
                          .filter(
                              s ->
                                  MessageDigest.isEqual(
                                      hash.getBytes(StandardCharsets.US_ASCII),
                                      s.tokenSha256().getBytes(StandardCharsets.US_ASCII)))
                          .findFirst()
                          .orElseThrow(LogSecurity::unauthorized);
                  req.setAttribute("LOG_SOURCE", source);
                  SecurityContextHolder.getContext()
                      .setAuthentication(
                          UsernamePasswordAuthenticationToken.authenticated(
                              source, null, List.of()));
                  if (req.getContentLengthLong() > LogInput.LIMIT)
                    throw new IngressError(413, "LOG_TOO_LARGE", "日志请求超过大小限制");
                } catch (IngressError e) {
                  res.setStatus(e.status);
                  res.setContentType("application/json;charset=UTF-8");
                  res.getWriter().write(Json.write(e.body()));
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

  private static IngressError unauthorized() {
    return new IngressError(401, "INVALID_LOG_CREDENTIAL", "日志来源凭据无效");
  }
}
