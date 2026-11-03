package com.bdemo.auth;

import com.bdemo.system.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real loopback IAM HTTP responses; no live credentials or external database. */
class EmcsCurrentAuthorizationTest {
 @TempDir Path dir;
 @Test void authorizationAndLocalMappingAreCheckedAgainAfterExchange() throws Exception {
  var json=new ObjectMapper();var response=new AtomicReference<ObjectNode>(json.createObjectNode());
  var assertion=response.get();assertion.put("subject","subject-a").put("sessionId","sid-a").put("localSubject","operator")
      .put("audience","emcs").put("readOnly",false).put("superAdmin",false).put("applicationAdmin",true);
  assertion.putArray("permissions");assertion.putArray("allowedAppCodes").add("EMCS");
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/auth/sso/",exchange->{
   var bytes=json.writeValueAsBytes(Map.of("code",0,"data",response.get()));
   exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
  });server.start();
  try {
   var file=dir.resolve("bindings.json");Files.writeString(file,"{\"subject-a\":{\"enabled\":true,\"userId\":42}}");
   var users=mock(SystemUserMapper.class);
   when(users.findById(42)).thenReturn(Optional.of(new UserAccount(42,1L,"operator","Operator","","","0","0")));
   when(users.findRoleKeys(42)).thenReturn(List.of("lw_emcs_admin"));when(users.findPermissions(42)).thenReturn(List.of());
   var service=new EmcsSsoSessions(users,"http://127.0.0.1:"+server.getAddress().getPort(),"test-only-service-credential-at-least-32",file.toString(),true);
   var request=new MockHttpServletRequest();service.exchange("test-ticket",request);
   var binding=(EmcsSsoSessions.Binding)request.getSession().getAttribute("EMCS_SSO");
   assertTrue(service.current(binding).permissions().contains("EMCS_APPLICATION_ADMIN"));
   assertion.putArray("allowedAppCodes").add("WMS");denied(()->service.current(binding));
   assertion.putArray("allowedAppCodes").add("EMCS");assertion.put("sessionId","another-session");denied(()->service.current(binding));
   assertion.put("sessionId","sid-a");assertion.put("zone","AREA-B");denied(()->service.current(binding));
   assertion.remove("zone");assertion.put("readOnly",true);
   var readOnly=service.current(binding);assertFalse(readOnly.permissions().contains("EMCS_APPLICATION_ADMIN"));
   assertFalse(service.permits(binding,readOnly,"POST","/system/dict/type"));
   Files.writeString(file,"{\"subject-a\":{\"enabled\":false,\"userId\":42}}");denied(()->service.current(binding));
   Files.writeString(file,"{\"subject-a\":{\"enabled\":true,\"userId\":99}}");denied(()->service.current(binding));
   Files.delete(file);denied(()->service.current(binding));
  } finally {server.stop(0);}
 }
 private void denied(org.junit.jupiter.api.function.Executable action){assertEquals(403,assertThrows(ResponseStatusException.class,action).getStatusCode().value());}
}
