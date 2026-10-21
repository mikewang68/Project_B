package com.bproject.trust.logevidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.trust.adapters.iam.SimulatedIamClient;
import com.bproject.trust.identity.*;
import com.bproject.trust.ports.PlatformIdentity;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitConfig(OperationSecurityChainTest.Config.class)
@WebAppConfiguration
class OperationSecurityChainTest {
  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import(Security.class)
  static class Config {
    @Bean
    IntegrationSettings settings() {
      var s = mock(IntegrationSettings.class);
      when(s.read())
          .thenReturn(
              new IntegrationSettings.Settings(
                  "", "real", false, Map.of("ORG", "TRUST_ORG"), List.of()));
      return s;
    }

    @Bean(name = "iamClient")
    PlatformIdentity iam() {
      return mock(PlatformIdentity.class);
    }

    @Bean
    SimulatedIamClient simulated() {
      return mock(SimulatedIamClient.class);
    }

    @Bean
    JdbcTemplate db() {
      return mock(JdbcTemplate.class);
    }

    @Bean
    CurrentIdentity currentIdentity(JdbcTemplate db) { return new CurrentIdentity(db); }

    @Bean
    LogOperations operations() {
      return mock(LogOperations.class);
    }

    @Bean
    Reads reads() {
      return new Reads();
    }

    @Bean
    static org.springframework.context.support.PropertySourcesPlaceholderConfigurer properties() {
      var p = new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();
      var props = new Properties();
      props.setProperty("trust.root", ".");
      p.setProperties(props);
      return p;
    }
  }

  @RestController
  static class Reads {
    @GetMapping("/api/v1/csrf")
    Object csrf(org.springframework.security.web.csrf.CsrfToken token) {
      return Map.of("token", token.getToken());
    }
    @GetMapping({
      "/api/v1/events",
      "/api/v1/status",
      "/api/v1/audit/settings",
      "/api/v1/audit/delivery"
    })
    Object read() {
      return Map.of("ok", true);
    }
  }

  @Autowired WebApplicationContext context;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("iamClient")
  PlatformIdentity iam;

  @Autowired LogOperations operations;
  @Autowired JdbcTemplate db;
  MockMvc http;

  @BeforeEach
  void setup() {
    reset(iam, operations, db);
    when(operations.pagePrincipal(any())).thenReturn(
        new com.bproject.trust.identity.PlatformPrincipal("real-user", "user", "TRUST_ORG"));
    when(iam.current("synthetic-session-token"))
        .thenReturn(
            new PlatformIdentity.User(
                "real-user",
                "user",
                List.of("ORG"),
                Set.of("trust:event:read", "trust:operations:read")));
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  MockHttpSession session() {
    var s = new MockHttpSession();
    s.setAttribute("IAM_TOKEN", "synthetic-session-token");
    s.setAttribute("IAM_USER", "real-user");
    s.setAttribute("IAM_ORG", "TRUST_ORG");
    return s;
  }

  @Test
  void directBusinessReadIsAuditedWithoutBrowserHeader() throws Exception {
    http.perform(get("/api/v1/events").session(session())).andExpect(status().isOk());
    verify(operations)
        .enqueue(
            eq("TRUST_ORG"),
            eq("real-user"),
            eq("READ"),
            eq(""),
            eq("STARTED"),
            eq("REQUEST"),
            eq(""),
            eq("GET"),
            eq("/api/v1/events"),
            eq(""),
            anyString());
    verify(operations)
        .enqueue(
            eq("TRUST_ORG"),
            eq("real-user"),
            eq("READ"),
            eq(""),
            eq("SUCCESS"),
            eq("REQUEST"),
            eq(""),
            eq("GET"),
            eq("/api/v1/events"),
            anyString(),
            anyString());
  }

  @Test
  void activeStatusSettingsAndDeliveryReadsHaveOwnedCorrelationAndRequestOutcomes()
      throws Exception {
    String pageId = UUID.randomUUID().toString();
    for (String path :
        List.of("/api/v1/status", "/api/v1/audit/settings", "/api/v1/audit/delivery")) {
      http.perform(get(path).session(session()).header("X-TRUST-Page-Action-Id", pageId))
          .andExpect(status().isOk());
      for (String outcome : List.of("STARTED", "SUCCESS")) {
        verify(operations)
            .enqueue(
                eq("TRUST_ORG"),
                eq("real-user"),
                eq("READ"),
                eq(""),
                eq(outcome),
                eq("REQUEST"),
                eq(""),
                eq("GET"),
                eq(path),
                anyString(),
                eq(pageId));
      }
    }
    reset(operations);
    doThrow(LogEvidenceService.missing()).when(operations).verifyPageAction(any(), eq(pageId));
    for (String path :
        List.of("/api/v1/status", "/api/v1/audit/settings", "/api/v1/audit/delivery")) {
      http.perform(get(path).session(session()).header("X-TRUST-Page-Action-Id", pageId))
          .andExpect(status().isNotFound());
      verify(operations).rejected(any(), eq("GET"), eq(path));
    }
    verify(operations, never())
        .enqueue(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString());
  }

  @Test
  void logoutUsesPreviouslyVerifiedSessionAndWorksDuringIamOutage() throws Exception {
    when(iam.current(anyString())).thenThrow(new IllegalStateException("offline"));
    var session = session();
    http.perform(post("/api/v1/logout").session(session).with(csrf()))
        .andExpect(status().isNoContent());
    assertTrue(session.isInvalid());
    verifyNoInteractions(iam);
    verify(operations)
        .enqueue(
            eq("TRUST_ORG"),
            eq("real-user"),
            eq("LOGOUT"),
            eq(""),
            eq("STARTED"),
            eq("REQUEST"),
            eq(""),
            eq("POST"),
            eq("/api/v1/logout"),
            anyString(),
            anyString());
    verify(operations)
        .enqueue(
            eq("TRUST_ORG"),
            eq("real-user"),
            eq("LOGOUT"),
            eq(""),
            eq("SUCCESS"),
            eq("REQUEST"),
            eq(""),
            eq("POST"),
            eq("/api/v1/logout"),
            anyString(),
            anyString());
  }

  @Test
  void freshCsrfAndLogoutWorkWhenBothIamAndAuditAreOffline() throws Exception {
    when(iam.current(anyString())).thenThrow(new IllegalStateException("offline"));
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"))
        .when(operations).enqueue(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    var session = session();
    var response = http.perform(get("/api/v1/csrf").session(session))
        .andExpect(status().isOk()).andReturn().getResponse();
    String token = com.bproject.trust.shared.json.Json.MAPPER.readTree(response.getContentAsString()).path("token").asText();
    assertFalse(token.isEmpty());
    http.perform(post("/api/v1/logout").session(session).header("X-CSRF-TOKEN", token))
        .andExpect(status().isNoContent());
    assertTrue(session.isInvalid());
    verifyNoInteractions(iam);
  }

  @Test
  void csrfDenialIsPersistedBeforeReturningForbidden() throws Exception {
    http.perform(post("/api/v1/events").session(session())).andExpect(status().isForbidden());
    verify(operations).rejected(any(), eq("POST"), eq("/api/v1/events"));
  }

  @Test
  void auditPersistenceFailurePreventsBusinessRead() throws Exception {
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"))
        .when(operations)
        .enqueue(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString());
    http.perform(get("/api/v1/events").session(session()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("AUDIT_OPERATION_UNCONFIRMED"));
  }
}
