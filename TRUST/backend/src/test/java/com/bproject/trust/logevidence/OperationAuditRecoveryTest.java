package com.bproject.trust.logevidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.identity.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class OperationAuditRecoveryTest {
  private final PlatformPrincipal principal = new PlatformPrincipal("user", "user", "ORG");

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  private LogOperations operations() {
    var ops = mock(LogOperations.class);
    when(ops.pagePrincipal(any())).thenReturn(principal);
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    return ops;
  }

  @Test void bytesReachResponseBeforeControllerReturns() throws Exception {
    var ops = operations();
    var response = new MockHttpServletResponse();
    new OperationAuditFilter(ops).doFilter(new MockHttpServletRequest("GET", "/api/v1/events/id/export"),
        response, (request, output) -> {
          byte[] chunk = new byte[8192];
          for (int i = 0; i < 128; i++) {
            output.getOutputStream().write(chunk);
            assertEquals((i + 1) * chunk.length, response.getContentAsByteArray().length);
          }
        });
    assertEquals(1048576, response.getContentAsByteArray().length);
  }

  @Test void committedResponseIsNotRewrittenWhenOutcomePersistenceFails() throws Exception {
    var ops = operations();
    doThrow(new IllegalStateException("database unavailable")).when(ops).enqueue(
        anyString(), anyString(), anyString(), anyString(), eq("SUCCESS"), anyString(),
        anyString(), anyString(), anyString(), anyString(), anyString());
    var response = new MockHttpServletResponse();
    new OperationAuditFilter(ops).doFilter(new MockHttpServletRequest("GET", "/api/v1/events/id/export"),
        response, (request, output) -> { output.getOutputStream().write(new byte[] {1,2,3}); output.flushBuffer(); });
    assertArrayEquals(new byte[] {1,2,3}, response.getContentAsByteArray());
    assertEquals(200, response.getStatus());
  }

  @Test void brokenStreamIsFailedAndCannotBecomeSuccess() throws Exception {
    var ops = operations();
    var response = new MockHttpServletResponse();
    assertThrows(IOException.class, () -> new OperationAuditFilter(ops).doFilter(
        new MockHttpServletRequest("GET", "/api/v1/events/id/export"), response, (request, output) -> {
          output.getOutputStream().write(1); output.flushBuffer(); throw new IOException("closed");
        }));
    verify(ops).enqueue(anyString(), anyString(), anyString(), anyString(), eq("FAILED"),
        anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    verify(ops, never()).enqueue(anyString(), anyString(), anyString(), anyString(), eq("SUCCESS"),
        anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  @Test void localAndSimulatedPageOwnersCannotReuseRealIdentityActions() {
    var db = mock(JdbcTemplate.class);
    var settings = mock(IntegrationSettings.class);
    var identity = mock(CurrentIdentity.class);
    var ops = new LogOperations(db, null, null);
    ReflectionTestUtils.setField(ops, "identitySettings", settings);
    ReflectionTestUtils.setField(ops, "currentIdentity", identity);
    when(settings.read()).thenReturn(new IntegrationSettings.Settings("", "simulated", true, Map.of(), List.of()));
    when(settings.localLoginEnabled(any())).thenReturn(true);
    var simulated = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    var local = UsernamePasswordAuthenticationToken.authenticated("user", null, List.of());
    when(identity.org(local)).thenReturn("ORG");
    var sp = ops.pagePrincipal(simulated);
    var lp = ops.pagePrincipal(local);
    assertNotEquals(sp.userId(), lp.userId());
    var row = Map.<String,Object>of("org_id", "ORG", "actor", sp.userId(), "identity_mode", "real");
    assertThrows(com.bproject.trust.ingress.IngressError.class, () -> ops.pageOwner(row, sp));
    assertDoesNotThrow(() -> ops.pageOwner(Map.of("org_id","ORG","actor",sp.userId(),"identity_mode","simulated"), sp));
    assertThrows(com.bproject.trust.ingress.IngressError.class, () -> ops.controls(simulated));
  }
}
