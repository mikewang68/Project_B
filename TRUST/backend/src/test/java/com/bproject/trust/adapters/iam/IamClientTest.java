package com.bproject.trust.adapters.iam;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.shared.web.ApiError;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

class IamClientTest {
  HttpServer server;
  IamClient client;
  String body, type = "application/json";
  int status = 200;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/auth/me",
        exchange -> {
          byte[] b = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", type);
          exchange.sendResponseHeaders(status, b.length);
          exchange.getResponseBody().write(b);
          exchange.close();
        });
    server.start();
    var settings = mock(IntegrationSettings.class);
    when(settings.read())
        .thenReturn(
            new IntegrationSettings.Settings(
                "http://127.0.0.1:" + server.getAddress().getPort(), Map.of(), List.of()));
    client = new IamClient(settings);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void rejectsStaticPageEvenWithSuccessfulHttpStatus() {
    body = "<html>IAM</html>";
    type = "text/html";
    assertEquals(503, assertThrows(ApiError.class, () -> client.current("token")).status);
  }

  @Test
  void readsCurrentUserAndExplicitPermissions() {
    body =
        "{\"code\":0,\"data\":{\"user\":{\"id\":\"1\",\"username\":\"admin\",\"status\":\"active\",\"orgCodes\":[\"COMPANY\"]},\"permissions\":[\"trust:wallet:read\"]}}";
    var u = client.current("token");
    assertEquals(Set.of("trust:wallet:read"), u.permissions());
    assertEquals(List.of("COMPANY"), u.orgCodes());
    assertFalse(u.permissions().contains("trust:evidence:read"));
  }

  @Test
  void rejectsDisabledIdentity() {
    body =
        "{\"code\":0,\"data\":{\"user\":{\"id\":\"1\",\"status\":\"inactive\"},\"permissions\":[]}}";
    assertEquals(401, assertThrows(ApiError.class, () -> client.current("token")).status);
  }
}
