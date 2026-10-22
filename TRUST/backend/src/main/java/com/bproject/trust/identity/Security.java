package com.bproject.trust.identity;

import com.bproject.trust.shared.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
@EnableMethodSecurity
public class Security {
  @Bean
  BCryptPasswordEncoder encoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(JdbcTemplate db) {
    return username ->
        db
            .query(
                "SELECT * FROM trust_users WHERE username=?",
                (r, n) ->
                    User.withUsername(r.getString("username"))
                        .password(r.getString("password_hash"))
                        .roles(r.getString("role"))
                        .build(),
                username)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("账号不存在"));
  }

  @Bean
  SecurityFilterChain chain(
      HttpSecurity http,
      @Qualifier("iamClient") com.bproject.trust.ports.PlatformIdentity iamClient,
      com.bproject.trust.adapters.iam.SimulatedIamClient simulatedIam,
      IntegrationSettings settings,
      com.bproject.trust.logevidence.LogOperations logOperations)
      throws Exception {
    com.bproject.trust.ports.PlatformIdentity iam =
        settings.read().simulated() ? simulatedIam : iamClient;
    http.addFilterBefore(
        new PlatformIdentityFilter(iam, settings, logOperations),
        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
    http.addFilterAfter(
        new com.bproject.trust.logevidence.OperationAuditFilter(logOperations),
        PlatformIdentityFilter.class);
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico",
                        "/api/v1/csrf",
                        "/api/v1/identity-mode",
                        "/api/v1/login",
                        "/api/v1/iam/login",
                        "/api/v1/dev-login",
                        "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(
            c ->
                c.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .ignoringRequestMatchers("/api/v1/integrations/**"))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/v1/login")
                    .successHandler(
                        (q, s, a) -> {
                          var session = q.getSession(false);
                          if (session != null) {
                            session.removeAttribute("IAM_TOKEN");
                            session.removeAttribute("IAM_ORG");
                            session.removeAttribute("IAM_USER");
                          }
                          s.setContentType("application/json;charset=UTF-8");
                          s.getWriter().write("{\"ok\":true}");
                        })
                    .failureHandler(
                        (q, s, e) -> {
                          s.setStatus(401);
                          s.setContentType("application/json;charset=UTF-8");
                          s.getWriter().write("{\"message\":\"账号或密码错误\"}");
                        }))
        .logout(
            l ->
                l.logoutUrl("/api/v1/logout")
                    .addLogoutHandler(
                        (q, s, a) -> {
                          var session = q.getSession(false);
                          if (session != null
                              && session.getAttribute("IAM_USER") instanceof String user
                              && session.getAttribute("IAM_ORG") instanceof String org) {
                            String trace = java.util.UUID.randomUUID().toString();
                            // These values were installed by the successful IAM login, never by
                            // browser identity headers.
                            // Clearing a local session remains possible when IAM is unavailable or
                            // the token is revoked.
                            try {
                              logOperations.enqueue(
                                  org,
                                  user,
                                  "LOGOUT",
                                  "",
                                  "STARTED",
                                  "REQUEST",
                                  "",
                                  "POST",
                                  "/api/v1/logout",
                                  "Previously verified server session; no online revalidation",
                                  trace);
                            } catch (Exception unavailable) {
                              q.setAttribute("TRUST_LOGOUT_RECOVERY", true);
                              LoggerFactory.getLogger(Security.class)
                                  .warn(
                                      "AUDIT_LOGOUT_RECOVERY: local audit unavailable; clearing"
                                          + " local session");
                            }
                            q.setAttribute(
                                "TRUST_LOGOUT_PRINCIPAL", new PlatformPrincipal(user, user, org));
                            q.setAttribute("TRUST_LOGOUT_TRACE", trace);
                          }
                        })
                    .logoutSuccessHandler(
                        (q, s, a) -> {
                          if (!Boolean.TRUE.equals(q.getAttribute("TRUST_LOGOUT_RECOVERY"))
                              && q.getAttribute("TRUST_LOGOUT_PRINCIPAL")
                                  instanceof PlatformPrincipal p)
                            try {
                              logOperations.enqueue(
                                  p.orgId(),
                                  p.userId(),
                                  "LOGOUT",
                                  "",
                                  "SUCCESS",
                                  "REQUEST",
                                  "",
                                  "POST",
                                  "/api/v1/logout",
                                  "Local session cleared",
                                  (String) q.getAttribute("TRUST_LOGOUT_TRACE"));
                            } catch (Exception unavailable) {
                              org.slf4j.LoggerFactory.getLogger(Security.class)
                                  .warn("AUDIT_LOGOUT_OUTCOME_UNCONFIRMED: local session cleared");
                            }
                          s.setStatus(204);
                        }))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((q, s, x) -> failure(s, 401, "需要登录"))
                    .accessDeniedHandler(
                        (q, s, x) -> {
                          try {
                            logOperations.rejected(
                                org.springframework.security.core.context.SecurityContextHolder
                                    .getContext()
                                    .getAuthentication(),
                                q.getMethod(),
                                q.getRequestURI());
                            failure(s, 403, "请求未获授权或 CSRF 校验失败");
                          } catch (Exception unavailable) {
                            failure(s, 503, "操作留痕暂不可用");
                          }
                        }));
    return http.build();
  }

  // Do not dispatch to /error: a second security pass can replace a denial with 401.
  static void failure(jakarta.servlet.http.HttpServletResponse response, int status, String message)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(Json.write(Map.of("message", message)));
  }

  @Bean
  ApplicationRunner seedUsers(
      JdbcTemplate db, BCryptPasswordEncoder encoder, @Value("${trust.root}") String root) {
    return args -> {
      Path path = Path.of(root, "runtime/secrets/users.json");
      if (!Files.exists(path)) return;
      List<Map<String, String>> users =
          Json.MAPPER.readValue(
              Files.readString(path), new com.fasterxml.jackson.core.type.TypeReference<>() {});
      for (var u : users)
        if (db.queryForObject(
                "SELECT COUNT(*) FROM trust_users WHERE username=?", Long.class, u.get("username"))
            == 0)
          db.update(
              "INSERT INTO trust_users(username,password_hash,role,org_id) VALUES(?,?,?,?)",
              u.get("username"),
              encoder.encode(u.get("password")),
              u.get("role"),
              u.get("orgId"));
    };
  }
}
