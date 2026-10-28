package com.bproject.ehm.platform.security;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;

class EhmSsoLogoutTest {
  @TempDir Path directory;

  @Test
  void successfulBackchannelRevocationDuringLogoutDoesNotFailTheCaller() throws Exception {
    var bindings = directory.resolve("bindings.json"); Files.writeString(bindings, "{}");
    var session = new MockHttpSession();
    session.setAttribute(EhmSsoSessions.ACTOR, new EhmSsoSessions.Actor(
        "subject-a", "operator", "USER", "sid-a", true, Set.of("project-a")));
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/auth/sso/session/logout", request -> {
      request.getRequestBody().readAllBytes(); session.invalidate();
      byte[] body = "{\"code\":0,\"data\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      request.sendResponseHeaders(200, body.length); request.getResponseBody().write(body); request.close();
    });
    server.start();
    try {
      var sessions = new EhmSsoSessions(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(),
          "test-service-credential-at-least-32-characters", bindings.toString());
      assertDoesNotThrow(() -> sessions.logout(session)); assertTrue(session.isInvalid());
    } finally { server.stop(0); }
  }
}
