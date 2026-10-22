package com.bproject.trust.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.ports.PlatformIdentity;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;

class IntegrationSettingsRetentionTest {
  @TempDir Path root;
  Path config;

  @BeforeEach
  void setup() throws Exception {
    config = root.resolve("runtime/secrets/integration.json");
    Files.createDirectories(config.getParent());
  }

  void write(String mode) throws Exception {
    Files.writeString(
        config,
        "{\"iamUrl\":\"http://127.0.0.1/iam\",\"iamMode\":\""
            + mode
            + "\",\"orgMap\":{},\"services\":[]}");
  }

  @Test
  void absentConfigurationNeverEnablesDevelopment() {
    var settings = new IntegrationSettings(root.toString());
    assertThrows(IllegalStateException.class, settings::initialize);
  }

  @Test
  void realInstanceCannotDowngradeAndRecoversOnlyValidMode() throws Exception {
    write("real");
    var settings = new IntegrationSettings(root.toString());
    settings.initialize();
    write("development");
    assertThrows(IllegalStateException.class, settings::read);
    Files.writeString(config, "invalid");
    assertThrows(IllegalStateException.class, settings::read);
    write("real");
    assertTrue(settings.read().iamRequired());
  }

  @Test
  void missingRuntimeConfigRejectsLocalLoginAndOldSessionButLogoutContinues() throws Exception {
    write("real");
    var settings = new IntegrationSettings(root.toString());
    settings.initialize();
    Files.delete(config);
    var filter = new PlatformIdentityFilter(mock(PlatformIdentity.class), settings);
    for (String path : java.util.List.of("/api/v1/login", "/api/v1/dev-login", "/api/v1/me")) {
      var request = new MockHttpServletRequest("POST", path);
      request.setServletPath(path);
      request.setSession(new MockHttpSession());
      org.springframework.security.core.context.SecurityContextHolder.getContext()
          .setAuthentication(
              org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                  .authenticated("old-local-admin", null, java.util.List.of()));
      var response = new MockHttpServletResponse();
      var called = new java.util.concurrent.atomic.AtomicBoolean();
      filter.doFilter(request, response, (a, b) -> called.set(true));
      assertEquals(503, response.getStatus());
      assertFalse(called.get());
      assertNull(
          org.springframework.security.core.context.SecurityContextHolder.getContext()
              .getAuthentication());
    }
    var request = new MockHttpServletRequest("POST", "/api/v1/logout");
    request.setServletPath("/api/v1/logout");
    var called = new java.util.concurrent.atomic.AtomicBoolean();
    filter.doFilter(request, new MockHttpServletResponse(), (a, b) -> called.set(true));
    assertTrue(called.get());
  }
}
