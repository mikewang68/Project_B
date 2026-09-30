package com.bproject.trust.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.trust.adapters.iam.SimulatedIamClient;
import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.ports.LedgerGateway;
import com.bproject.trust.shared.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Real Spring security/controller/service/DB integration; only IPFS and Fabric are mocked. */
@org.springframework.context.annotation.Import(com.bproject.trust.support.NoBackgroundScheduling.class)
@SpringBootTest(
    properties = {
      "trust.isolation-enabled=false",
      "spring.liquibase.enabled=false",
      "trust.archiving.enabled=false",
      "trust.fabric-channel=trust-iam-dev-test",
      "server.port=28184",
      "trust.dev-quick-login.enabled=true"
    })
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TRUST_DB_INTEGRATION", matches = "1")
class SimulatedIamBackendIntegrationTest {
  static final String LOCAL_USER = "local-" + java.util.UUID.randomUUID();
  @MockitoBean EvidenceStorage storage;
  @MockitoBean LedgerGateway ledger;
  @Autowired SimulatedIamClient iam;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) throws Exception {
    String url = System.getenv("TRUST_TEST_DB_URL");
    if (url == null || !url.matches("jdbc:opengauss://127\\.0\\.0\\.1:25432/trust_iam_dev_test"))
      throw new IllegalStateException(
          "Identity integration tests require the dedicated trust_iam_dev_test database");
    Path root = Path.of(System.getenv("TRUST_ROOT"), "runtime/identity-integration");
    Files.createDirectories(root.resolve("runtime/secrets"));
    Files.writeString(
        root.resolve("runtime/secrets/integration.json"),
        Json.write(
            new IntegrationSettings.Settings(
                "",
                "simulated",
                true,
                Map.of("ORG-A", "B-PROJECT", "ORG-B", "OTHER-PROJECT"),
                List.of())));
    Files.writeString(
        root.resolve("runtime/secrets/users.json"),
        Json.write(
            List.of(
                Map.of(
                    "username",
                    LOCAL_USER,
                    "password",
                    "fixture-local-admin",
                    "role",
                    "ADMIN",
                    "orgId",
                    "B-PROJECT"))));
    properties.add("trust.root", () -> root.toString());
    properties.add("spring.datasource.url", () -> url);
  }

  @BeforeEach
  void resetSimulation() throws Exception {
    try (var connection = db.getDataSource().getConnection()) {
      assertTrue(connection.getMetaData().getURL().endsWith("/trust_iam_dev_test"));
    }
    iam.setAvailable(true);
    iam.updatePermissions(
        "wallet-applicant", Set.of("trust:wallet:read", "trust:wallet:manage"), List.of("ORG-A"));
  }

  private MockHttpSession login(String username, String password) throws Exception {
    return (MockHttpSession)
        mvc.perform(
                post("/api/v1/iam/login")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        Json.write(
                            Map.of(
                                "username", username, "password", password, "orgId", "B-PROJECT"))))
            .andExpect(status().isOk())
            .andReturn()
            .getRequest()
            .getSession(false);
  }

  @Test
  void loginUsesRealSecurityChainAndDedicatedWalletDatabase() throws Exception {
    var session = login("wallet-applicant", "fixture-applicant");
    mvc.perform(get("/api/v1/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("wallet-applicant"))
        .andExpect(jsonPath("$.identityProvider").value("IAM"))
        .andExpect(jsonPath("$.simulated").value(true))
        .andExpect(jsonPath("$.orgId").value("B-PROJECT"));
    mvc.perform(get("/api/v1/wallets").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.wallets").isArray());
  }

  @Test
  void revocationAppliesToExistingAuthenticatedSession() throws Exception {
    var session = login("wallet-applicant", "fixture-applicant");
    mvc.perform(get("/api/v1/wallets").session(session)).andExpect(status().isOk());
    iam.updatePermissions("wallet-applicant", Set.of(), List.of("ORG-A"));
    mvc.perform(get("/api/v1/wallets").session(session)).andExpect(status().isForbidden());
  }

  @Test
  void changedOrganizationIsDeniedWithoutReauthentication() throws Exception {
    var session = login("wallet-applicant", "fixture-applicant");
    iam.updatePermissions("wallet-applicant", Set.of("trust:wallet:read"), List.of("ORG-B"));
    mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/wallets").session(session)).andExpect(status().isForbidden());
  }

  @Test
  void outageDeniesExistingSessionsAndNeverDowngradesToLocalAdmin() throws Exception {
    var session = login("wallet-applicant", "fixture-applicant");
    iam.setAvailable(false);
    mvc.perform(get("/api/v1/wallets").session(session)).andExpect(status().isServiceUnavailable());
    mvc.perform(get("/api/v1/identity-mode").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.simulated").value(true));
    mvc.perform(get("/api/v1/wallets").with(user(LOCAL_USER).roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/login")
                .with(csrf())
                .param("username", LOCAL_USER)
                .param("password", "unused"))
        .andExpect(status().isUnauthorized());
    iam.setAvailable(true);
    mvc.perform(get("/api/v1/wallets").session(session)).andExpect(status().isOk());
  }

  @Test
  @org.springframework.transaction.annotation.Transactional
  void explicitLocalLoginSupportsLegacyAccountsWithoutInheritingIamSessionOrWalletRights()
      throws Exception {
    // Reserve only an unused legacy slot inside this test transaction; rollback removes our row.
    // Never update a pre-existing account to make its password fit the fixture.
    String legacy = List.of("admin", "editor", "viewer", "external-viewer").stream()
        .filter(name -> db.queryForObject("SELECT COUNT(*) FROM trust_users WHERE username=?", Long.class, name) == 0)
        .findFirst().orElseThrow(() -> new IllegalStateException("No unused legacy fixture slot; existing accounts are protected"));
    db.update("INSERT INTO trust_users(username,password_hash,role,org_id) VALUES(?,?,'ADMIN','B-PROJECT')",
        legacy, new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("fixture-local-admin"));
    Files.writeString(Path.of(System.getenv("TRUST_ROOT"), "runtime/identity-integration/runtime/secrets/users.json"),
        Json.write(List.of(Map.of("username", legacy, "password", "fixture-local-admin", "role", "ADMIN", "orgId", "B-PROJECT"))));
    mvc.perform(get("/api/v1/identity-mode"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.localLoginEnabled").value(true));
    mvc.perform(get("/api/v1/dev-login"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accounts[0].username").value(legacy))
        .andExpect(jsonPath("$.accounts[0].password").doesNotExist());
    var iamSession = login("wallet-applicant", "fixture-applicant");
    iam.setAvailable(false);
    var local =
        (MockHttpSession)
            mvc.perform(
                    post("/api/v1/dev-login")
                        .session(iamSession)
                        .with(csrf())
                        .contentType("application/json")
                        .content(Json.write(Map.of("username", legacy))))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    assertNull(local.getAttribute("IAM_TOKEN"));
    mvc.perform(get("/api/v1/me").session(local))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value(legacy))
        .andExpect(jsonPath("$.identityProvider").value("LOCAL"))
        .andExpect(jsonPath("$.simulated").value(false));
    mvc.perform(get("/api/v1/events").session(local)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/wallets").session(local)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/wallets/requests")
                .session(local)
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());

    iam.setAvailable(true);
    iamSession = login("wallet-applicant", "fixture-applicant");
    iam.setAvailable(false);
    var passwordSession =
        (MockHttpSession)
            mvc.perform(
                    post("/api/v1/login")
                        .session(iamSession)
                        .with(csrf())
                        .param("username", legacy)
                        .param("password", "fixture-local-admin"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);
    assertNull(passwordSession.getAttribute("IAM_TOKEN"));
    mvc.perform(get("/api/v1/me").session(passwordSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identityProvider").value("LOCAL"));
    mvc.perform(get("/api/v1/wallets").session(passwordSession)).andExpect(status().isForbidden());
  }

  @Test
  void unknownAndUnprivilegedAccountsCannotAccessWallets() throws Exception {
    mvc.perform(
            post("/api/v1/iam/login")
                .with(csrf())
                .contentType("application/json")
                .content(
                    Json.write(
                        Map.of(
                            "username",
                            "unregistered",
                            "password",
                            "fixture-unregistered",
                            "orgId",
                            "B-PROJECT"))))
        .andExpect(status().isUnauthorized());
    var session = login("no-permission", "fixture-noperm");
    mvc.perform(get("/api/v1/wallets").session(session)).andExpect(status().isForbidden());
  }
}
