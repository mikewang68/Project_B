package com.bproject.trust.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.ports.PlatformIdentity;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;

class PlatformIdentityFilterTest {
  @TempDir Path root;
  IntegrationSettings settings;
  PlatformIdentity iam;
  PlatformIdentityFilter filter;

  @BeforeEach
  void setup() throws Exception {
    Files.createDirectories(root.resolve("runtime/secrets"));
    Files.writeString(
        root.resolve("runtime/secrets/integration.json"),
        Json.write(
            new IntegrationSettings.Settings(
                "http://localhost", Map.of("COMPANY", "B-PROJECT"), List.of())));
    settings = new IntegrationSettings(root.toString());
    iam = mock(PlatformIdentity.class);
    filter = new PlatformIdentityFilter(iam, settings);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  MockHttpServletRequest request(String path) {
    var r = new MockHttpServletRequest("POST", path);
    r.setServletPath(path);
    r.getSession().setAttribute("IAM_TOKEN", "token");
    r.getSession().setAttribute("IAM_USER", "u1");
    r.getSession().setAttribute("IAM_ORG", "B-PROJECT");
    return r;
  }

  @Test
  void currentPermissionsAreCheckedOnEveryRequest() throws Exception {
    when(iam.current("token"))
        .thenReturn(
            new PlatformIdentity.User(
                "u1", "alice", List.of("COMPANY"), Set.of("trust:evidence:export")))
        .thenReturn(new PlatformIdentity.User("u1", "alice", List.of("COMPANY"), Set.of()));
    var a = new MockHttpServletResponse();
    var first = new MockFilterChain();
    filter.doFilter(request("/api/v1/events/1/export"), a, first);
    assertNotNull(first.getRequest());
    var b = new MockHttpServletResponse();
    var second = new MockFilterChain();
    filter.doFilter(request("/api/v1/events/1/export"), b, second);
    assertEquals(403, b.getStatus());
    assertNull(second.getRequest());
  }

  @Test
  void changedOrganizationAndChangedIdentityAreDenied() throws Exception {
    when(iam.current("token"))
        .thenReturn(
            new PlatformIdentity.User(
                "u2", "alice", List.of("COMPANY"), Set.of("trust:event:read")));
    var r = new MockHttpServletResponse();
    filter.doFilter(request("/api/v1/me"), r, new MockFilterChain());
    assertEquals(403, r.getStatus());
    when(iam.current("token"))
        .thenReturn(
            new PlatformIdentity.User("u1", "alice", List.of("OTHER"), Set.of("trust:event:read")));
    r = new MockHttpServletResponse();
    filter.doFilter(request("/api/v1/me"), r, new MockFilterChain());
    assertEquals(403, r.getStatus());
  }

  @Test
  void cookieSessionCannotUseServiceIngress() throws Exception {
    var r = new MockHttpServletResponse();
    filter.doFilter(request("/api/v1/integrations/wms/events"), r, new MockFilterChain());
    assertEquals(401, r.getStatus());
  }

  @Test
  void localLoginRequiresExplicitSimulationOptInAndNeverBypassesRealIam() throws Exception {
    Files.writeString(root.resolve("runtime/secrets/integration.json"), Json.write(
        new IntegrationSettings.Settings("", "simulated", true, Map.of(), List.of())));
    var request = new MockHttpServletRequest("POST", "/api/v1/dev-login");
    var response = new MockHttpServletResponse();
    new PlatformIdentityFilter(iam, new IntegrationSettings(root.toString()))
        .doFilter(request, response, new MockFilterChain());
    assertEquals(401, response.getStatus());

    var chain = new MockFilterChain();
    new PlatformIdentityFilter(iam, new IntegrationSettings(root.toString(), true))
        .doFilter(new MockHttpServletRequest("POST", "/api/v1/dev-login"), new MockHttpServletResponse(), chain);
    assertNotNull(chain.getRequest());

    Files.writeString(root.resolve("runtime/secrets/integration.json"), Json.write(
        new IntegrationSettings.Settings("http://real-iam", "real", true, Map.of(), List.of())));
    response = new MockHttpServletResponse();
    new PlatformIdentityFilter(iam, new IntegrationSettings(root.toString(), true))
        .doFilter(new MockHttpServletRequest("POST", "/api/v1/login"), response, new MockFilterChain());
    assertEquals(401, response.getStatus());
    verifyNoInteractions(iam);
  }

  @Test
  void retryDoesNotInheritBusinessSubmitPermission() {
    assertEquals(
        "trust:task:retry",
        PlatformIdentityFilter.requiredPermission("/api/v1/events/id/retry", "POST"));
  }

  @Test
  void readonlyDeniesEvenWithWritePermissionsAndBrowserOverride() throws Exception {
    when(iam.current("token")).thenReturn(new PlatformIdentity.User("u1", "alice",
        List.of("COMPANY"), Set.of("trust:event:submit", "trust:event:correct",
          "trust:task:retry", "trust:evidence:upload", "trust:evidence:verify",
          "trust:evidence:export", "trust:evidence:read", "trust:wallet:manage",
          "trust:wallet:review", "trust:wallet:read", "trust:audit:read"), true));
    for (String path : List.of("/api/v1/events", "/api/v1/events/1/corrections",
        "/api/v1/events/1/retry", "/api/v1/events/1/verify", "/api/v1/events/1/export",
        "/api/v1/evidence", "/api/v1/imports", "/api/v1/wallets/requests",
        "/api/v1/wallets/requests/1/review", "/api/v1/audit/delivery/1/retry",
        "/api/v1/audit/settings", "/api/v1/a-future-write")) {
      var req = request(path);req.addParameter("readOnly", "false");req.addHeader("X-Read-Only", "false");
      var response = new MockHttpServletResponse();var chain = new MockFilterChain();
      filter.doFilter(req,response,chain);
      assertEquals(403,response.getStatus(),path);assertNull(chain.getRequest());
    }
    var req = request("/api/v1/evidence/1/download");req.setMethod("GET");
    var response = new MockHttpServletResponse();filter.doFilter(req,response,new MockFilterChain());
    assertEquals(403,response.getStatus());
  }

  @Test
  void readonlyPreservesReadsAuditAndRequiresOriginalOrganizationPermissions() throws Exception {
    when(iam.current("token")).thenReturn(new PlatformIdentity.User("u1", "alice",
        List.of("COMPANY"), Set.of("trust:event:read"), true));
    for (String path : List.of("/api/v1/events", "/api/v1/me", "/api/v1/audit/page-actions",
        "/api/v1/audit/page-actions/1234/result")) {
      var req=request(path);if (!path.contains("page-actions")) req.setMethod("GET");
      var chain=new MockFilterChain();filter.doFilter(req,new MockHttpServletResponse(),chain);
      assertNotNull(chain.getRequest(),path);
    }
    var req=request("/api/v1/tasks");req.setMethod("GET");
    var response=new MockHttpServletResponse();filter.doFilter(req,response,new MockFilterChain());
    assertEquals(403,response.getStatus());
    req=request("/api/v1/events");req.setMethod("GET");req.getSession().setAttribute("IAM_ORG","OTHER");
    response=new MockHttpServletResponse();filter.doFilter(req,response,new MockFilterChain());
    assertEquals(403,response.getStatus());
  }
}
