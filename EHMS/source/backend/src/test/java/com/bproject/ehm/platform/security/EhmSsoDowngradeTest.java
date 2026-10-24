package com.bproject.ehm.platform.security;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

class EhmSsoDowngradeTest {
  @TempDir Path directory;

  @Test
  void existingAdminSessionBecomesReadonlyWithoutLosingItsGrantedProject() throws Exception {
    var bindings=directory.resolve("bindings.json");
    Files.writeString(bindings,"""
        {"subject-a":{"enabled":true,"localSubject":"operator","role":"SUPER_ADMIN","projectIds":["project-a","project-b"]}}
        """);
    var assertion=new AtomicReference<>("""
        {"code":0,"data":{"subject":"subject-a","sessionId":"sid-a","localSubject":"operator",
        "audience":"ehms","readOnly":false,"superAdmin":true,"permissions":[],"projectIds":["project-a"]}}
        """);
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/auth/sso/session/introspect",request->{
      request.getRequestBody().readAllBytes();
      byte[] body=assertion.get().getBytes(StandardCharsets.UTF_8);
      request.sendResponseHeaders(200,body.length);request.getResponseBody().write(body);request.close();
    });server.start();
    try {
      var sessions=new EhmSsoSessions(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),
          "test-service-identity-at-least-32-characters",bindings.toString());
      var session=new MockHttpSession();
      session.setAttribute(EhmSsoSessions.ACTOR,new EhmSsoSessions.Actor("subject-a","operator","SUPER_ADMIN","sid-a",false,Set.of("project-a")));
      assertTrue(sessions.actor(session).admin());
      assertion.set(assertion.get().replace("\"readOnly\":false","\"readOnly\":true").replace("\"superAdmin\":true","\"superAdmin\":false"));
      var downgraded=sessions.actor(session);
      assertTrue(downgraded.readOnly());assertFalse(downgraded.admin());
      assertEquals(Set.of("project-a"),downgraded.projects());
      assertion.set(assertion.get().replace("\"readOnly\":true","\"readOnly\":false"));
      assertEquals(403,assertThrows(ResponseStatusException.class,()->sessions.actor(session)).getStatusCode().value());
    } finally {server.stop(0);}
  }
}
