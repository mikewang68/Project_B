package com.bproject.trust.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

class TrustSsoSessionsTest {
  HttpServer server;
  JdbcTemplate db;
  TrustSsoSessions sessions;
  Map<String,Object> assertion;
  int status;
  volatile boolean slowLogout;
  boolean consumed;
  AtomicInteger requests;
  final String credential = "synthetic-test-service-credential-123456";
  final String ticket = "synthetic-one-use-ticket-123456";
  @BeforeEach void setup() throws Exception {
    status=200; consumed=false; requests=new AtomicInteger();
    assertion=new HashMap<>(Map.of("audience","trust","subject","u1","sessionId","s1",
        "localSubject","local1","org","org1","username","alice","readOnly",false,
        "permissions",List.of("trust:event:read","trust:task:retry")));
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/auth/sso/", e -> {
      requests.incrementAndGet();
      var body=Json.MAPPER.readTree(e.getRequestBody().readAllBytes());
      assertEquals(credential,e.getRequestHeaders().getFirst("X-SSO-Service-Token"));
      assertEquals("trust",body.path("audience").asText());
      if(slowLogout && e.getRequestURI().getPath().endsWith("session/logout")) {
        try { Thread.sleep(5500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        e.close(); return;
      }
      int code=status;
      if(e.getRequestURI().getPath().endsWith("verify-and-consume")) {
        if(consumed)code=401; consumed=true;
        assertEquals(ticket,body.path("ticket").asText());
      }
      byte[] bytes=Json.write(Map.of("code",0,"data",assertion)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      e.sendResponseHeaders(code,bytes.length); e.getResponseBody().write(bytes);e.close();
    });
    server.start();
    db=mock(JdbcTemplate.class);
    when(db.queryForList(anyString(),eq("local1"),eq("org1")))
        .thenReturn(List.of(Map.of("role","EDITOR","org_id","org1")));
    sessions=new TrustSsoSessions(db,"http://127.0.0.1:"+server.getAddress().getPort(),credential,true);
  }
  @AfterEach void stop(){server.stop(0);}
  TrustSsoSessions.Binding binding(){return new TrustSsoSessions.Binding("u1","s1","local1","org1");}
  void denied(Runnable action,int expected){assertEquals(expected,assertThrows(ApiError.class,action::run).status);}
  @Test void exchangeIsOneUseAndEveryRequestRechecksAuthorization(){
    assertEquals(binding(),sessions.exchange(ticket));
    denied(()->sessions.exchange(ticket),401);
    assertTrue(sessions.current(binding()).permissions().contains("trust:task:retry"));
    assertion.put("permissions",List.of("trust:event:read"));
    assertFalse(sessions.current(binding()).permissions().contains("trust:task:retry"));
    assertEquals(4,requests.get());
  }
  @Test void serviceIdentityAndDisabledConfigurationFailClosed(){
    denied(()->sessions.authorizeService("wrong"),403); assertEquals(0,requests.get());
    sessions.authorizeService(credential);
    var disabled=new TrustSsoSessions(db,"", "",false);
    denied(()->disabled.exchange(ticket),503);assertEquals(0,requests.get());
  }
  @Test void sessionSubjectOrganizationAndLocalMappingCannotChange(){
    for(String field:List.of("sessionId","subject","org","localSubject","audience")) {
      Object old=assertion.put(field,"different");denied(()->sessions.current(binding()),403);assertion.put(field,old);
    }
    when(db.queryForList(anyString(),eq("local1"),eq("org1"))).thenReturn(List.of());
    denied(()->sessions.current(binding()),403);
  }
  @Test void superAdminDoesNotGrantUnlistedPermissionsAndReadonlyWins(){
    assertion.put("superAdmin",true);assertion.put("permissions",List.of());
    assertTrue(sessions.current(binding()).permissions().isEmpty());
    assertion.put("permissions",List.of("trust:event:submit","trust:event:read"));
    assertion.put("readOnly",true);
    var current=sessions.current(binding());assertTrue(current.principal().readOnly());
    assertEquals(Set.of("trust:event:read"),current.permissions());
  }
  @Test void viewerAndMalformedPermissionAssertionAreRestricted(){
    when(db.queryForList(anyString(),eq("local1"),eq("org1")))
        .thenReturn(List.of(Map.of("role","VIEWER","org_id","org1")));
    assertTrue(sessions.current(binding()).principal().readOnly());
    assertion.put("permissions",List.of(123));denied(()->sessions.current(binding()),403);
    assertion.remove("readOnly");denied(()->sessions.current(binding()),403);
  }
  @Test void unavailableAndRevokedIamDoNotFallback(){
    status=503;denied(()->sessions.current(binding()),503);
    status=401;denied(()->sessions.current(binding()),401);
    status=403;denied(()->sessions.current(binding()),403);
  }
  @Test void revocationIsScopedAndIdempotent(){
    var a=new MockHttpSession();var b=new MockHttpSession();
    sessions.bind(a,binding());sessions.bind(b,new TrustSsoSessions.Binding("u2","s2","local2","org2"));
    assertEquals(0,sessions.revoke("s1","u2"));assertFalse(a.isInvalid());
    assertEquals(1,sessions.revoke("s1","u1"));assertTrue(a.isInvalid());assertFalse(b.isInvalid());
    assertEquals(0,sessions.revoke("s1","u1"));
  }
  @Test void walletPrivilegesRemainExplicitAndReadonlyDominates(){
    assertion.put("permissions",List.of("trust:wallet:read","trust:wallet:manage"));
    var current=sessions.current(binding());
    assertTrue(current.permissions().contains("trust:wallet:manage"));
    assertFalse(current.permissions().contains("trust:wallet:review"));
    assertion.put("readOnly",true);
    assertEquals(Set.of("trust:wallet:read"),sessions.current(binding()).permissions());
  }
  @Test void centralLogoutTimeoutIsUnavailableNotConfirmed(){
    slowLogout=true;
    denied(()->sessions.logout(binding()),503);
    assertEquals(1,requests.get());
  }
  @Test void malformedIdentityAndMappingOutageFailClosed(){
    assertion.put("sessionId",123);denied(()->sessions.current(binding()),403);
    assertion.put("sessionId","s1");
    when(db.queryForList(anyString(),eq("local1"),eq("org1")))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic outage"));
    denied(()->sessions.current(binding()),503);
  }
  @Test void v2ReadonlyKeepsGrantedVerificationWithoutRestoringWritePermissions(){
    var ctx=Json.MAPPER.valueToTree(new TrustCentralScopeTest().context(true,
        Set.of("VIEW","AUDIT_VERIFY","DOWNLOAD","EXPORT","CHAIN_SUBMIT","UPLOAD")));
    ((com.fasterxml.jackson.databind.node.ObjectNode)ctx).put("userId","u1");
    assertion.put("userContext",ctx);assertion.put("readOnly",true);
    var current=sessions.current(binding());assertNotNull(current.context());
    assertTrue(current.permissions().containsAll(Set.of("trust:event:read","trust:evidence:verify",
        "trust:evidence:download","trust:evidence:export")));
    assertFalse(current.permissions().contains("trust:event:submit"));
    assertFalse(current.permissions().contains("trust:evidence:upload"));
  }
}
