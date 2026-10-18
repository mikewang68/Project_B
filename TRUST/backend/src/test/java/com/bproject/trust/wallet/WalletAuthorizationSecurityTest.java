package com.bproject.trust.wallet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.trust.identity.CurrentIdentity;
import com.bproject.trust.identity.PlatformPrincipal;
import com.bproject.trust.shared.web.ApiError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class WalletAuthorizationSecurityTest {
  @Configuration
  @EnableMethodSecurity
  static class Config {
    @Bean WalletAuthorizationService service() { return mock(WalletAuthorizationService.class); }
    @Bean WalletAuthorizationController controller(WalletAuthorizationService service) {
      return new WalletAuthorizationController(service,new CurrentIdentity(mock(JdbcTemplate.class)));
    }
  }
  @Test void methodSecurityRequiresWalletRead() {
    try (var context=new AnnotationConfigApplicationContext(Config.class)) {
      var auth=new UsernamePasswordAuthenticationToken(new PlatformPrincipal("u","user","tenant"),null,List.of());
      SecurityContextHolder.getContext().setAuthentication(auth);
      assertThrows(AccessDeniedException.class,()->context.getBean(WalletAuthorizationController.class).status(auth,"w","SIM","SIM_EVENT"));
      verifyNoInteractions(context.getBean(WalletAuthorizationService.class));
    } finally { SecurityContextHolder.clearContext(); }
  }
  @Test void quickLoginCannotUseReadPermissionToBypassIam() {
    try (var context=new AnnotationConfigApplicationContext(Config.class)) {
      var auth=new UsernamePasswordAuthenticationToken("local-user",null,List.of(new SimpleGrantedAuthority("trust:wallet:read")));
      SecurityContextHolder.getContext().setAuthentication(auth);
      assertThrows(ApiError.class,()->context.getBean(WalletAuthorizationController.class).status(auth,"w","SIM","SIM_EVENT"));
      verifyNoInteractions(context.getBean(WalletAuthorizationService.class));
    } finally { SecurityContextHolder.clearContext(); }
  }
  @Test void organizationComesFromVerifiedPrincipal() {
    try (var context=new AnnotationConfigApplicationContext(Config.class)) {
      var auth=new UsernamePasswordAuthenticationToken(new PlatformPrincipal("u","user","tenant"),null,List.of(new SimpleGrantedAuthority("trust:wallet:read")));
      SecurityContextHolder.getContext().setAuthentication(auth);
      var service=context.getBean(WalletAuthorizationService.class);
      when(service.status("tenant","w","SIM","SIM_EVENT")).thenReturn(Map.of("status","UNKNOWN"));
      assertEquals("UNKNOWN",context.getBean(WalletAuthorizationController.class).status(auth,"w","SIM","SIM_EVENT").get("status"));
      verify(service).status("tenant","w","SIM","SIM_EVENT");
    } finally { SecurityContextHolder.clearContext(); }
  }
}
