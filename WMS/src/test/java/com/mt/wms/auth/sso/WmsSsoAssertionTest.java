package com.mt.wms.auth.sso;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.AuthRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.*;

/** Real HTTP response parsing with synthetic IAM responses; not an IAM end-to-end test. */
class WmsSsoAssertionTest {
  private HttpServer server;private String response;private WmsSsoExchangeService service;
  private WmsSsoSubjectRepository subjects;private AuthRepository auth;
  @BeforeEach void setup() throws Exception {
    WmsSsoLogoutRegistry.resetForTests();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/auth/sso/verify-and-consume",exchange->{byte[] body=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    var props=mock(WmsSsoProperties.class);when(props.isUsable()).thenReturn(true);when(props.getTimeoutMs()).thenReturn(2000);
    when(props.getAudience()).thenReturn("wms");when(props.getServiceToken()).thenReturn("synthetic-service-token");
    when(props.getIamBaseUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
    subjects=mock(WmsSsoSubjectRepository.class);auth=mock(AuthRepository.class);
    service=new WmsSsoExchangeService(props,subjects,auth,new ObjectMapper());
  }
  @AfterEach void stop(){server.stop(0);}
  @Test void http200BusinessFailureCannotBecomeAnIdentity(){
    response="{\"code\":40301,\"data\":{\"subject\":\"admin\"}}";
    assertFalse(service.exchange("synthetic").ok());verifyNoInteractions(subjects,auth);
  }
  @Test void usernameAndMissingSidAreNotStableIdentity(){
    for(String data:java.util.List.of("{\"subject\":\"admin\",\"sessionId\":\"sid\"}","{\"subject\":\"0123456789abcdef0123456789abcdef\"}")){
      response="{\"code\":0,\"data\":"+data+"}";
      assertEquals("SSO_BAD_ASSERTION",service.exchange("synthetic").errorCode());
    }
    verifyNoInteractions(subjects,auth);
  }
  @Test void wrongReturnedAudienceCannotReachLocalMapping(){
    response="{\"code\":0,\"data\":{\"subject\":\"0123456789abcdef0123456789abcdef\",\"sessionId\":\"sid\",\"audience\":\"scs\"}}";
    assertEquals("SSO_BAD_ASSERTION",service.exchange("synthetic").errorCode());verifyNoInteractions(subjects,auth);
  }
}
