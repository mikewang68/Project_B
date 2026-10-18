package com.bproject.trust.ingress;

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
public class EvidenceIngressSecurity {
  public static final String BASE = "/api/v1/integrations/evidence/events";
  @Bean @Order(2)
  SecurityFilterChain evidenceIngress(HttpSecurity http, EvidenceIngressSettings settings) throws Exception {
    return http.securityMatcher(BASE, BASE+"/**")
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(c -> c.disable()).formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .addFilterBefore(new OncePerRequestFilter() {
          @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req,
              jakarta.servlet.http.HttpServletResponse res, jakarta.servlet.FilterChain chain)
              throws java.io.IOException, jakarta.servlet.ServletException {
            SecurityContextHolder.clearContext();
            try {
              var config = settings.read();
              if (!config.enabled()) throw new IngressError(404,"INGRESS_DISABLED","通用接入未启用");
              String auth=req.getHeader("Authorization");
              if (auth==null || !auth.startsWith("Bearer ") || auth.length()>512) throw unauthorized();
              String hash=Json.sha(auth.substring(7).getBytes(StandardCharsets.UTF_8));
              var source=config.sources().stream().filter(s -> s.enabled() && MessageDigest.isEqual(
                  hash.getBytes(StandardCharsets.US_ASCII),s.tokenSha256().getBytes(StandardCharsets.US_ASCII)))
                  .findFirst().orElseThrow(EvidenceIngressSecurity::unauthorized);
              req.setAttribute("EVIDENCE_SOURCE",source);
              SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(source,null,List.of()));
            } catch (IngressError e) { error(res,e); return;
            } catch (IllegalStateException e) { error(res,new IngressError(503,"INGRESS_CONFIGURATION_UNAVAILABLE","通用接入配置不可用")); return; }
            try { chain.doFilter(req,res); } finally { SecurityContextHolder.clearContext(); }
          }
        }, UsernamePasswordAuthenticationFilter.class).build();
  }
  private static IngressError unauthorized() { return new IngressError(401,"INVALID_SOURCE_CREDENTIAL","通用来源凭据无效"); }
  private static void error(jakarta.servlet.http.HttpServletResponse res,IngressError e) throws java.io.IOException {
    SecurityContextHolder.clearContext(); res.setStatus(e.status); res.setContentType("application/json;charset=UTF-8");
    res.getWriter().write(Json.write(e.body()));
  }
}
