package com.bproject.trust.provisioning;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ErrorHandler;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real security filter/controller/service: rejected credentials must never reach persistence. */
@SpringJUnitConfig(IdentityRevocationPermissionTest.Config.class)
@WebAppConfiguration
class IdentityRevocationPermissionTest {
  static final String TOKEN = "offline-disable-only-regression-credential";

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({IdentityApiSecurity.class, FabricIdentityService.class,
      FabricIdentityController.class, ErrorHandler.class})
  static class Config {
    @Bean JdbcTemplate db() { return mock(JdbcTemplate.class); }
    @Bean TransactionTemplate tx() { return mock(TransactionTemplate.class); }
    @Bean IdentityProviderSettings settings() {
      var settings = mock(IdentityProviderSettings.class);
      var client = new IdentityProviderSettings.Client("limited",
          Json.sha(TOKEN.getBytes(StandardCharsets.UTF_8)), "issuer", "tenant",
          Set.of("ORG1"), Set.of("read", "disable"));
      var retryClient = new IdentityProviderSettings.Client("retry-only",
          Json.sha((TOKEN + "-retry").getBytes(StandardCharsets.UTF_8)), "retry-issuer", "tenant",
          Set.of("ORG1"), Set.of("read", "retry"));
      when(settings.read()).thenReturn(new IdentityProviderSettings.Settings(
          "unused", "1.5.22", "unused", List.of(client, retryClient), Map.of()));
      return settings;
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired JdbcTemplate db;
  @Autowired TransactionTemplate tx;
  MockMvc http;

  @BeforeEach void setup() {
    reset(db, tx);
    http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test void syncDeletedAndExplicitRevokeAreForbiddenBeforeAnyDatabaseAccess() throws Exception {
    for (String user : List.of("existing-user", "new-user")) {
      for (String action : List.of("SYNC", "REVOKE")) {
        var command = new FabricIdentityService.Command(
            "issuer", "tenant", user, "ORG1", 2, "DELETED", action);
        http.perform(post("/api/v1/identities")
            .header("Authorization", "Bearer " + TOKEN)
            .header("Idempotency-Key", "restricted-request-1234")
            .contentType("application/json").content(Json.write(command)))
            .andExpect(status().isForbidden());
      }
    }
    verifyNoInteractions(db, tx);
  }

  @Test void explicitLifecycleRevokeIsForbiddenBeforeAnyDatabaseAccess() throws Exception {
    http.perform(post("/api/v1/identities/existing-id/revoke")
        .header("Authorization", "Bearer " + TOKEN)
        .header("Idempotency-Key", "restricted-request-1234")
        .header("If-Match", "1"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(db, tx);
  }

  @Test void retryRequiresRevokeForRevocationTasksButAllowsOrdinaryRecovery() throws Exception {
    doAnswer(invocation -> {
      java.util.function.Consumer<org.springframework.transaction.TransactionStatus> callback =
          invocation.getArgument(0);
      callback.accept(mock(org.springframework.transaction.TransactionStatus.class));
      return null;
    }).when(tx).executeWithoutResult(any());
    var task = new HashMap<String, Object>();
    task.put("id", "pending-id");
    task.put("issuer", "retry-issuer");
    task.put("tenant", "tenant");
    task.put("org_id", "ORG1");
    task.put("user_id", "pending-user");
    task.put("revision", 1L);
    task.put("status", "RETRY");
    when(db.queryForList(
        "SELECT id,action,desired_state FROM fabric_identity WHERE id=? FOR UPDATE", "pending-id"))
        .thenReturn(List.of(task));
    when(db.queryForList("SELECT * FROM fabric_identity WHERE id=? AND issuer=? AND tenant=?",
        "pending-id", "retry-issuer", "tenant")).thenReturn(List.of(task));
    for (var pair : List.of(List.of("REVOKE", "DISABLED"), List.of("SYNC", "DELETED"))) {
      task.put("action", pair.get(0));
      task.put("desired_state", pair.get(1));
      http.perform(post("/api/v1/identities/pending-id/retry")
          .header("Authorization", "Bearer " + TOKEN + "-retry")
          .header("Idempotency-Key", "restricted-retry-1234")
          .header("If-Match", "1")).andExpect(status().isForbidden());
    }
    verify(db, never()).update(anyString(), any(Object[].class));
    task.put("action", "SYNC");
    task.put("desired_state", "ACTIVE");
    http.perform(post("/api/v1/identities/pending-id/retry")
        .header("Authorization", "Bearer " + TOKEN + "-retry")
        .header("Idempotency-Key", "ordinary-retry-1234")
        .header("If-Match", "1")).andExpect(status().isOk());
    verify(db).update(
        "UPDATE fabric_identity SET next_attempt=CURRENT_TIMESTAMP WHERE id=? AND revision=?",
        "pending-id", 1L);
  }
}
