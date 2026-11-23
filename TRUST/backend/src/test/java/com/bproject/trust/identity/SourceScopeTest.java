package com.bproject.trust.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.bproject.trust.ports.PlatformIdentity;
import com.bproject.trust.shared.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;

class SourceScopeTest {
  @TempDir Path root;

  @Test
  void rejectsPartialOrUnsafeScopeAndKeepsLegacyConfiguration() {
    assertNull(new IntegrationSettings.Service("s", "h", "o", "WMS", "c", "w", "o").scope());
    assertThrows(IllegalArgumentException.class, () -> new IntegrationSettings.Service(
        "s", "h", "o", "WMS", "c", "w", "o", "P", null, "TRUST"));
    assertThrows(IllegalArgumentException.class, () -> new IntegrationSettings.Service(
        "s", "h", "o", "WMS", "c", "w", "o", "P'", "R", "TRUST"));
  }

  @Test
  void authenticatedSourceScopeIsBoundAndClearedEvenWhenControllerFails() throws Exception {
    var service = new IntegrationSettings.Service("source", Json.sha("test-secret".getBytes(StandardCharsets.UTF_8)),
        "ORG", "WMS", "C", "W", "O", "PROJECT", "REGION", "TRUST");
    var file = root.resolve("runtime/secrets/integration.json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, Json.write(new IntegrationSettings.Settings("http://localhost", Map.of(), List.of(service))));
    var filter = new PlatformIdentityFilter(mock(PlatformIdentity.class), new IntegrationSettings(root.toString()));
    var request = new MockHttpServletRequest("POST", "/api/v1/integrations/wms/events");
    request.addHeader("Authorization", "Bearer test-secret");
    try {
      assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(request,
          new MockHttpServletResponse(), (req, res) -> {
            assertEquals(service.scope(), TrustCentralScope.writeTuple());
            var sql = TrustCentralScope.predicate(TrustCentralScope.Root.EVENT, "events_read");
            assertTrue(sql.contains("o.project_id = 'PROJECT'"));
            assertTrue(sql.contains("o.region_id = 'REGION'"));
            assertTrue(sql.contains("o.subsystem_code = 'TRUST'"));
            throw new jakarta.servlet.ServletException("controller failed");
          }));
      assertNull(TrustCentralScope.writeTuple());
      assertEquals("", TrustCentralScope.predicate(TrustCentralScope.Root.EVENT, "events_read"));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
