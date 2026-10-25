package com.bdemo.sys.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.bdemo.sys.config.SecurityConfig;

/** Real servlet cookies exercise session rotation, concurrent reads and current authorization. */
@SpringBootTest(classes = NativeSessionHttpTest.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.config.location=optional:classpath:/native-session-test.yml",
        "server.address=127.0.0.1"})
class NativeSessionHttpTest {
  @LocalServerPort int port;
  @Autowired SysSsoSessions sessions;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach void currentUser() {
    reset(sessions);
    when(sessions.current(any())).thenReturn(new LoginUser("native-id", "native-user", "Native", true, Set.of()));
  }

  @Test void oneCookieSurvivesSequentialAndConcurrentReads() throws Exception {
    String cookie = exchange(null);
    for (int i = 0; i < 3; i++) assertStableRead(send("GET", "/native-test/read", cookie, false));
    var executor = Executors.newFixedThreadPool(8);
    try {
      var calls = new ArrayList<Callable<HttpResponse<String>>>();
      for (int i = 0; i < 32; i++) calls.add(() -> send("GET", "/native-test/read", cookie, false));
      for (var future : executor.invokeAll(calls)) assertStableRead(future.get());
    } finally { executor.shutdownNow(); }
    verify(sessions, atLeast(35)).current(any());
  }

  @Test void freshExchangeReplacesOldSessionAndCsrfRemainsRequired() throws Exception {
    String oldCookie = exchange(null);
    String cookie = exchange(oldCookie);
    assertNotEquals(oldCookie, cookie);
    assertEquals(401, send("GET", "/native-test/read", oldCookie, false).statusCode());
    assertEquals(403, send("POST", "/native-test/write", cookie, false).statusCode());
    assertEquals(200, send("POST", "/native-test/write", cookie, true).statusCode());
  }

  @Test void revokedCentralSessionCannotUsePreviouslyWorkingCookie() throws Exception {
    String cookie = exchange(null);
    assertStableRead(send("GET", "/native-test/read", cookie, false));
    when(sessions.current(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    assertEquals(401, send("GET", "/native-test/read", cookie, false).statusCode());
  }

  private void assertStableRead(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    assertEquals("native-user", response.body());
    assertTrue(response.headers().allValues("Set-Cookie").isEmpty(),
        "Authenticated reads must not rotate the already established native cookie");
  }

  private String exchange(String cookie) throws Exception {
    var response = send("POST", "/auth/sso/exchange", cookie, true);
    assertEquals(200, response.statusCode(), response.body());
    return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
  }

  private HttpResponse<String> send(String method, String path, String cookie, boolean csrf) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(java.time.Duration.ofSeconds(15)).method(method, HttpRequest.BodyPublishers.noBody());
    if (cookie != null) request.header("Cookie", cookie);
    if (csrf) request.header("X-SYS-CSRF", "test-csrf");
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
      org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class})
  @Import({SecurityConfig.class, Endpoints.class})
  static class TestApplication {
    @Bean JwtService jwt() { return mock(JwtService.class); }
    @Bean LoginUserLoader users() { return mock(LoginUserLoader.class); }
    @Bean PermInterceptor permissions() { return new PermInterceptor(); }
    @Bean SysSsoSessions sessions() { return mock(SysSsoSessions.class); }
  }

  @RestController
  static class Endpoints {
    @PostMapping("/auth/sso/exchange") String exchange(HttpServletRequest request) {
      var old = request.getSession(false);
      if (old != null) old.invalidate();
      var session = request.getSession(true);
      session.setAttribute("SYS_SSO", new SysSsoSessions.Binding("subject", "sid", "native-user"));
      session.setAttribute("SYS_CSRF", "test-csrf");
      return "created";
    }
    @GetMapping("/native-test/read") String read() { return "native-user"; }
    @PostMapping("/native-test/write") String write() { return "written"; }
  }
}
