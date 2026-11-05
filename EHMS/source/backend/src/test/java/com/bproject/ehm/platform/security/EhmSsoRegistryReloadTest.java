package com.bproject.ehm.platform.security;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

class EhmSsoRegistryReloadTest {
  @TempDir Path directory;
  @Test void newPersonAndRevocationAreReadWithoutRestart()throws Exception {
    String subject="0123456789abcdef0123456789abcdef";Path bindings=directory.resolve("bindings.json");Files.writeString(bindings,"{}");
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/auth/sso/",request->{
      request.getRequestBody().readAllBytes();String body="""
        {"code":0,"data":{"subject":"0123456789abcdef0123456789abcdef","sessionId":"test-sid",
        "localSubject":"new-person","audience":"ehms","readOnly":true,"superAdmin":false,
        "permissions":[],"projectIds":["PROJECT_TEST"]}}
        """;byte[] bytes=body.getBytes(StandardCharsets.UTF_8);request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);request.close();
    });server.start();
    try{
      var sessions=new EhmSsoSessions(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-service-identity-at-least-32-characters",bindings.toString());
      Files.writeString(bindings,"{\""+subject+"\":{\"enabled\":true,\"localSubject\":\"new-person\",\"role\":\"USER\",\"projectIds\":[\"PROJECT_TEST\"]}}");
      var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");
      assertEquals(subject,sessions.exchange("test-ticket-contents",request).subject());
      var session=(MockHttpSession)request.getSession();assertNotNull(sessions.actor(session));
      Files.writeString(bindings,Files.readString(bindings).replace("\"enabled\":true","\"enabled\":false"));
      assertEquals(403,assertThrows(ResponseStatusException.class,()->sessions.actor(session)).getStatusCode().value());
      Files.writeString(bindings,"{");
      assertEquals(503,assertThrows(ResponseStatusException.class,()->sessions.actor(session)).getStatusCode().value());
    }finally{server.stop(0);}
  }
}
