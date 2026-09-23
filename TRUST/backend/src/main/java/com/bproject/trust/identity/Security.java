package com.bproject.trust.identity;

import com.bproject.trust.shared.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
  SecurityFilterChain chain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico",
                        "/api/v1/csrf",
                        "/api/v1/login",
                        "/actuator/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(c -> c.csrfTokenRepository(new HttpSessionCsrfTokenRepository()))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/v1/login")
                    .successHandler(
                        (q, s, a) -> {
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
            l -> l.logoutUrl("/api/v1/logout").logoutSuccessHandler((q, s, a) -> s.setStatus(204)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((q, s, x) -> s.sendError(401))
                    .accessDeniedHandler((q, s, x) -> s.sendError(403)));
    return http.build();
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
