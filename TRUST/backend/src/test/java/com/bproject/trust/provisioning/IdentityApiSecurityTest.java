package com.bproject.trust.provisioning;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.trust.shared.json.Json;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitConfig(IdentityApiSecurityTest.Config.class)
@WebAppConfiguration
class IdentityApiSecurityTest {
  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import(IdentityApiSecurity.class)
  static class Config {
    @Bean
    IdentityProviderSettings settings() {
      return mock(IdentityProviderSettings.class);
    }

    @Bean
    FabricIdentityService service() {
      return mock(FabricIdentityService.class);
    }

    @Bean
    FabricIdentityController controller(FabricIdentityService service) {
      return new FabricIdentityController(service);
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired IdentityProviderSettings settings;
  @Autowired FabricIdentityService service;
  MockMvc http;
  final String token = "offline-service-credential-for-test";

  @BeforeEach
  void setup() {
    reset(settings, service);
    var caller =
        new IdentityProviderSettings.Client(
            "iam",
            Json.sha(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "issuer",
            "tenant",
            Set.of("ORG1"),
            Set.of("read", "create"));
    when(settings.read())
        .thenReturn(
            new IdentityProviderSettings.Settings(
                "unused", "1.5.22", "unused", List.of(caller), Map.of()));
    when(service.get(any(), eq("id"))).thenReturn(Map.of("identityId", "id", "status", "PENDING"));
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void requiresIndependentBearerAndIgnoresBrowserAuthentication() throws Exception {
    http.perform(get("/api/v1/identities/id"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/json"));
    http.perform(
            get("/api/v1/identities/id")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin")
                        .roles("ADMIN")))
        .andExpect(status().isUnauthorized());
    http.perform(get("/api/v1/identities/id").header("Authorization", "Bearer wms-fixture"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  void acceptsServiceTokenWithoutSessionOrIamCallback() throws Exception {
    http.perform(get("/api/v1/identities/id").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identityId").value("id"));
    http.perform(get("/api/v1/identities/id")).andExpect(status().isUnauthorized());
  }

  @Test
  void configurationFailureIsUnavailableNotDevelopmentFallback() throws Exception {
    when(settings.read()).thenThrow(new IllegalStateException("private configuration detail"));
    http.perform(get("/api/v1/identities/id").header("Authorization", "Bearer " + token))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private configuration detail"))));
  }
}
