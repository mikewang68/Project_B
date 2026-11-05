package com.mt.wms.auth.sso;

import com.bproject.iam.context.SharedDispatchClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedDispatchClientTest {
  private static final String SUBJECT="0123456789abcdef0123456789abcdef";
  @Test void rejectsInsecureDestinationsScopePathsAndInvalidResourceInputs() {
    for(String url:new String[]{"http://example.test","https://user@example.test","https://example.test/other",
        "https://example.test?target=other","https://example.test#fragment","ftp://example.test"})
      assertThrows(IllegalArgumentException.class,()->new SharedDispatchClient(url,"wms","x".repeat(40),true));
    assertThrows(IllegalArgumentException.class,()->new SharedDispatchClient("http://127.0.0.1","wms","x".repeat(40),false));
    var client=new SharedDispatchClient("https://example.test","wms","x".repeat(40),false);
    assertEquals(400,assertThrows(SharedDispatchClient.Rejected.class,()->client.read(SUBJECT,"sid","users",null,1,10)).status());
    assertThrows(SharedDispatchClient.Rejected.class,()->client.read(SUBJECT,"sid","plans","x/../users",1,10));
    assertThrows(SharedDispatchClient.Rejected.class,()->client.read(SUBJECT,"sid\ninjected","plans",null,1,10));
    assertThrows(SharedDispatchClient.Rejected.class,()->client.read(SUBJECT,"sid","plans",null,1,101));
  }
  @Test void sendsOnlyVerifiedBindingAndNeverFollowsCredentialRedirects() throws Exception {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    var redirects=new AtomicInteger();var reads=new AtomicInteger();
    server.createContext("/api/shared/dispatch/plans",exchange->{
      assertEquals("GET",exchange.getRequestMethod());
      assertEquals(SUBJECT,exchange.getRequestHeaders().getFirst("X-Shared-Subject"));
      assertEquals("trusted_sid",exchange.getRequestHeaders().getFirst("X-Shared-Session-Id"));
      assertEquals("wms",exchange.getRequestHeaders().getFirst("X-Shared-Audience"));
      assertEquals("x".repeat(40),exchange.getRequestHeaders().getFirst("X-SSO-Service-Token"));
      assertEquals("page=2&pageSize=5",exchange.getRequestURI().getQuery());
      reads.incrementAndGet();byte[] body="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
    });
    server.createContext("/api/shared/dispatch/work-orders",exchange->{
      exchange.getResponseHeaders().add("Location","/leak");exchange.sendResponseHeaders(302,-1);exchange.close();
    });
    server.createContext("/leak",exchange->{redirects.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
    server.start();
    try {
      var client=new SharedDispatchClient("http://127.0.0.1:"+server.getAddress().getPort(),"wms","x".repeat(40),true);
      assertEquals("{\"ok\":true}",client.read(SUBJECT,"trusted_sid","plans",null,2,5));
      assertEquals(503,assertThrows(SharedDispatchClient.Rejected.class,
          ()->client.read(SUBJECT,"trusted_sid","work-orders",null,1,10)).status());
      assertEquals(1,reads.get());assertEquals(0,redirects.get());
    }finally{server.stop(0);}
  }
}
