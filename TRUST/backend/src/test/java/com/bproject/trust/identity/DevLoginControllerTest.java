package com.bproject.trust.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class DevLoginControllerTest {
  @TempDir Path root;

  @Test
  void disabledByDefault() {
    new ApplicationContextRunner().withUserConfiguration(DevLoginController.class)
        .run(context -> assertEquals(0, context.getBeansOfType(DevLoginController.class).size()));
  }

  @Test
  void listsOnlyAllowedAccountsWithoutPasswordsAndAuthenticatesOnServer() throws Exception {
    Files.createDirectories(root.resolve("runtime/secrets"));
    Files.writeString(root.resolve("runtime/secrets/users.json"), """
        [{"username":"viewer","password":"test-private-secret","role":"VIEWER","orgId":"B-PROJECT"},
         {"username":"unexpected","password":"other-secret","role":"ADMIN","orgId":"B-PROJECT"}]
        """);
    var configuration = mock(AuthenticationConfiguration.class);
    var manager = mock(AuthenticationManager.class);
    when(configuration.getAuthenticationManager()).thenReturn(manager);
    when(manager.authenticate(any())).thenAnswer(invocation -> {
      var token = invocation.getArgument(0, UsernamePasswordAuthenticationToken.class);
      assertEquals("viewer", token.getName());
      assertEquals("test-private-secret", token.getCredentials());
      return UsernamePasswordAuthenticationToken.authenticated("viewer", null, List.of(new SimpleGrantedAuthority("ROLE_VIEWER")));
    });
    var controller = new DevLoginController(root.toString(), configuration);
    var response = new MockHttpServletResponse();
    var listing = controller.list(response).toString();
    assertFalse(listing.contains("password"));
    assertFalse(listing.contains("secret"));
    assertFalse(listing.contains("unexpected"));
    assertEquals("no-store", response.getHeader("Cache-Control"));
    assertThrows(ResponseStatusException.class, () -> controller.login(Map.of("username", "unexpected"), new MockHttpServletRequest(), response));
    verifyNoInteractions(manager);
    var request = new MockHttpServletRequest();
    String previousSession = request.getSession().getId();
    try {
      controller.login(Map.of("username", "viewer"), request, response);
      assertNotEquals(previousSession, request.getSession().getId());
      assertNotNull(request.getSession().getAttribute("SPRING_SECURITY_CONTEXT"));
      assertEquals("viewer", SecurityContextHolder.getContext().getAuthentication().getName());
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
