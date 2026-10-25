package com.bdemo.sys.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class SysSsoSubjectMappingTest {
  @TempDir Path directory;

  @Test
  void usesExplicitLocalIdAndRejectsMissingDisabledOrMismatchedBindings() throws Exception {
    var path=directory.resolve("bindings.json");
    var users=mock(LoginUserLoader.class);
    when(users.load("native-user-a")).thenAnswer(call->new LoginUser("native-user-a","operator","Operator",true,Set.of("sys:dict:list","sys:dict:delete")));
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/auth/sso/session/introspect",request->{
      request.getRequestBody().readAllBytes();byte[] body="""
          {"code":0,"data":{"subject":"central-user-a","sessionId":"sid-a","localSubject":"operator",
           "audience":"sys","readOnly":true,"superAdmin":true,"permissions":["sys:dict:list"]}}
          """.getBytes(StandardCharsets.UTF_8);
      request.sendResponseHeaders(200,body.length);request.getResponseBody().write(body);request.close();
    });server.start();
    try {
      var sessions=new SysSsoSessions(users,"http://127.0.0.1:"+server.getAddress().getPort(),
          "test-service-identity-at-least-32-characters",true,path.toString());
      var binding=new SysSsoSessions.Binding("central-user-a","sid-a","operator");
      Files.writeString(path,"{\"central-user-a\":{\"enabled\":true,\"localUserId\":\"native-user-a\"}}");
      var current=sessions.current(binding);
      assertEquals("native-user-a",current.getUserId());assertTrue(current.isReadOnly());assertFalse(current.isSuperAdmin());
      assertEquals(Set.of("sys:dict:list"),current.getPermissions());verify(users,never()).load("central-user-a");
      Files.writeString(path,"{}");assertEquals(403,assertThrows(ResponseStatusException.class,()->sessions.current(binding)).getStatusCode().value());
      Files.writeString(path,"{\"central-user-a\":{\"enabled\":false,\"localUserId\":\"native-user-a\"}}");
      assertEquals(403,assertThrows(ResponseStatusException.class,()->sessions.current(binding)).getStatusCode().value());
      Files.writeString(path,"{\"central-user-a\":{\"enabled\":true,\"localUserId\":\"native-user-a\"}}");
      assertEquals(401,assertThrows(ResponseStatusException.class,()->sessions.current(new SysSsoSessions.Binding("central-user-a","sid-a","other-user"))).getStatusCode().value());
    } finally {server.stop(0);}
  }
}
