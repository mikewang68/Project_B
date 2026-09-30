package com.bproject.trust.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.bproject.trust.adapters.iam.SimulatedIamClient;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Exercises the actual simulation and request revalidation, with no mocked IAM responses. */
class SimulatedIdentityBoundaryTest {
  @TempDir Path root;
  IntegrationSettings settings;
  SimulatedIamClient iam;
  PlatformIdentityFilter filter;
  MockHttpSession session;

  @BeforeEach
  void setup() throws Exception {
    Files.createDirectories(root.resolve("runtime/secrets"));
    writeSettings("simulated", true);
    settings = new IntegrationSettings(root.toString());
    iam = new SimulatedIamClient(settings);
    filter = new PlatformIdentityFilter(iam, settings);
    var controller = new IamLoginController(iam, iam, settings);
    var request = new MockHttpServletRequest();
    controller.login(new IamLoginController.Login("wallet-applicant", "fixture-applicant", "B-PROJECT"),
        request, new MockHttpServletResponse());
    session = (MockHttpSession) request.getSession(false);
  }

  private void writeSettings(String mode, boolean isolated) throws Exception {
    Files.writeString(root.resolve("runtime/secrets/integration.json"), Json.write(
        new IntegrationSettings.Settings("", mode, isolated,
            Map.of("ORG-A", "B-PROJECT", "ORG-B", "OTHER-PROJECT"), List.of())));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private MockHttpServletResponse request(String path, MockHttpSession currentSession, boolean allowed)
      throws Exception {
    var request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    request.setSession(currentSession);
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertEquals(allowed, chain.getRequest() != null);
    return response;
  }

  @Test
  void whitelistRejectsUnknownUsersAndWrongPasswords() {
    assertEquals(401, assertThrows(ApiError.class, () -> iam.login("arbitrary", "fixture-arbitrary")).status);
    assertEquals(401, assertThrows(ApiError.class, () -> iam.login("wallet-applicant", "wrong")).status);
    assertEquals(401, assertThrows(ApiError.class, () -> iam.current("unissued-token")).status);
  }

  @Test
  void testReviewersHaveRealReviewPermissionToExerciseBusinessDenials() {
    var crossOrg = iam.current(iam.login("other-org-reviewer", "fixture-otherreview"));
    assertEquals(List.of("ORG-B"), crossOrg.orgCodes());
    assertTrue(crossOrg.permissions().contains("trust:wallet:review"));
    var selfReviewer = iam.current(iam.login("wallet-self-reviewer", "fixture-selfreview"));
    assertTrue(selfReviewer.permissions().containsAll(Set.of("trust:wallet:manage", "trust:wallet:review")));
    assertEquals(Set.of(), iam.current(iam.login("no-permission", "fixture-noperm")).permissions());
  }

  @Test
  void revokedPermissionIsDeniedInTheExistingSession() throws Exception {
    assertEquals(200, request("/api/v1/wallets", session, true).getStatus());
    iam.updatePermissions("wallet-applicant", Set.of(), List.of("ORG-A"));
    assertEquals(403, request("/api/v1/wallets", session, false).getStatus());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
  }

  @Test
  void organizationChangeInvalidatesExistingSessionScope() throws Exception {
    iam.updatePermissions("wallet-applicant", Set.of("trust:wallet:read"), List.of("ORG-B"));
    assertEquals(403, request("/api/v1/wallets", session, false).getStatus());
  }

  @Test
  void outageDoesNotReuseCachedAuthenticationAndRecoveryRevalidates() throws Exception {
    request("/api/v1/wallets", session, true);
    iam.setAvailable(false);
    assertEquals(503, request("/api/v1/wallets", session, false).getStatus());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
    assertEquals(503, assertThrows(ApiError.class, () -> iam.login("wallet-applicant", "fixture-applicant")).status);
    iam.setAvailable(true);
    assertEquals(200, request("/api/v1/wallets", session, true).getStatus());
  }

  @Test
  void emptyIamUrlDoesNotAllowLocalAuthenticationInSimulation() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("admin", null, List.of()));
    assertEquals(401, request("/api/v1/wallets", null, false).getStatus());
    assertEquals(401, request("/api/v1/login", null, false).getStatus());
    assertEquals(401, request("/api/v1/dev-login", null, false).getStatus());
  }

  @Test
  void simulationIsDisabledByDefaultAndRequiresExplicitIsolation() throws Exception {
    Files.delete(root.resolve("runtime/secrets/integration.json"));
    assertFalse(settings.read().simulated());
    assertEquals(503, assertThrows(ApiError.class, () -> iam.login("wallet-applicant", "fixture-applicant")).status);
    writeSettings("simulated", false);
    assertThrows(IllegalStateException.class, settings::read);
    writeSettings("unexpected", true);
    assertThrows(IllegalStateException.class, settings::read);
  }

  @Test
  void loginMetadataRemainsLabeledDuringAnOutage() throws Exception {
    iam.setAvailable(false);
    assertEquals(200, request("/api/v1/identity-mode", session, true).getStatus());
    var metadata = new IdentityController(null, settings).mode(new MockHttpServletResponse());
    assertEquals(true, metadata.get("simulated"));
    assertEquals(true, metadata.get("iamRequired"));
  }
}
