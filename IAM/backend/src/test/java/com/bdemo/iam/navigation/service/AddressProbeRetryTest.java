package com.bdemo.iam.navigation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.log.OperationLogger;
import com.bdemo.iam.sso.SsoServiceIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class AddressProbeRetryTest {
  HttpClient http;NavigationAddressPublicationService service;
  @BeforeEach void prepare(){
    http=mock(HttpClient.class);
    service=new NavigationAddressPublicationService(mock(JdbcTemplate.class),new ObjectMapper(),mock(SsoServiceIdentity.class),mock(OperationLogger.class),"");
    ReflectionTestUtils.setField(service,"http",http);
  }
  @Test void retriesTransientConnectFailuresButStopsAfterRecovery()throws Exception{
    var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);
    doThrow(new HttpConnectTimeoutException("synthetic timeout")).doThrow(new HttpConnectTimeoutException("synthetic timeout"))
      .doReturn(response).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    assertSame(response,service.fetchProbe(URI.create("https://warehouse.example.test/"),16384));
    verify(http,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }
  @Test void certificateFailuresNeverRetryOrDowngrade()throws Exception{
    doThrow(new SSLHandshakeException("synthetic certificate failure")).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    assertThrows(SSLHandshakeException.class,()->service.fetchProbe(URI.create("https://warehouse.example.test/"),16384));
    verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }
  @Test void exhaustedTransportFailuresAreBounded()throws Exception{
    doThrow(new HttpConnectTimeoutException("synthetic timeout")).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    assertThrows(HttpConnectTimeoutException.class,()->service.fetchProbe(URI.create("https://warehouse.example.test/"),16384));
    verify(http,times(3)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }
  @Test void clientErrorsAreReturnedWithoutRetry()throws Exception{
    var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(403);
    doReturn(response).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    assertEquals(403,service.fetchProbe(URI.create("https://warehouse.example.test/"),16384).statusCode());
    verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }
}
