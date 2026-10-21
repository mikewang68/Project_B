package com.bproject.ehm.platform.audit.application;
import com.bproject.audit.client.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AuditConsoleServiceTest {
    JdbcTemplate db;ObjectMapper mapper;JdbcLogOutbox outbox;AuditConsoleService console;
    @BeforeEach void setup(){
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE SCHEMA ehm");mapper=new ObjectMapper().findAndRegisterModules();outbox=new JdbcLogOutbox(db,mapper,"ehm.audit_outbox");outbox.initialize();
        console=new AuditConsoleService(db,mapper,"ehm","admin","","","test-domain","zone-a","audit_outbox");
        add("E1","u1","CREATE","SUCCESS");add("E2","u2","DELETE","DENIED");add("E3","u_1%","READ","SUCCESS");
    }
    void add(String id,String user,String action,String outcome){outbox.enqueue(new LogEvent(1,id,Instant.now(),"ehealth","test-domain","zone-a",user,"AUTHENTICATED","asset-tree",action,"D1",outcome,"POST","/api/ehm/v1/devices","t1",1,"BUSINESS","metadata only"));}
    MockHttpServletRequest request(String user){var r=new MockHttpServletRequest();if(user!=null)r.setUserPrincipal(()->user);return r;}
    Map<String,Object> query(String user,String filter){return console.query(request(user),0,2,filter,null,null,null,null,null,null,null);}
    @Test void anonymousDeniedAndForgedHeaderDoesNotAuthorize(){
        var r=request(null);r.addHeader("X-User-Id","admin");
        assertEquals(401,assertThrows(ResponseStatusException.class,()->console.detail(r,"E1")).getStatusCode().value());
    }
    @Test void ownScopeAndExplicitModuleReaderAreDifferent(){
        assertEquals(1L,query("u1",null).get("total"));assertEquals(3L,query("admin",null).get("total"));
        assertEquals(2,((List<?>)query("admin",null).get("items")).size());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->console.detail(request("u1"),"E2")).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->console.query(request("u1"),0,20,null,null,null,"u2",null,null,null,null)).getStatusCode().value());
    }
    @Test void filtersAreParameterizedAndWildcardUserIdsAreLiteral(){
        assertEquals(1L,query("admin","DELETE").get("total"));
        assertEquals(0L,query("admin","' OR 1=1 --").get("total"));
        assertEquals(1L,query("u_1%",null).get("total"));
        assertEquals(0L,query("u",null).get("total"));
    }
    @Test void rejectsInvalidPagingAndTimeRange(){
        assertThrows(ResponseStatusException.class,()->console.query(request("admin"),-1,20,null,null,null,null,null,null,null,null));
        assertThrows(ResponseStatusException.class,()->console.query(request("admin"),0,101,null,null,null,null,null,null,null,null));
        assertThrows(ResponseStatusException.class,()->console.query(request("admin"),0,20,null,null,null,null,null,null,"not-time",null));
    }
    @Test void noConfigDoesNotClaimChainSuccess(){var r=console.receipt(request("u1"),"E1");assertEquals("NOT_CONFIGURED",r.get("lookupState"));assertEquals("UNKNOWN",r.get("chainState"));}
    @Test void detailsExposeMetadataAndHashNotStoredCredentials(){
        var result=console.detail(request("u1"),"E1");assertEquals("E1",result.get("eventId"));assertEquals(64,result.get("payloadHash").toString().length());
        assertNotNull(result.get("enqueuedAt"));assertFalse(result.containsKey("receipt"));assertFalse(result.containsKey("payload"));
    }
    @Test void publicReceiptMustMatchOriginalEventAndHash() throws Exception {
        String hash=console.detail(request("u1"),"E1").get("payloadHash").toString();
        var body=new java.util.concurrent.atomic.AtomicReference<>("{\"eventId\":\"E1\",\"appCode\":\"ehealth\",\"stored\":true,\"payloadHash\":\""+hash+"\",\"chainState\":\"COMMITTED\",\"transactionId\":\"tx-test\"}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/logs/v1/events/E1/receipt",e->{byte[] bytes=body.get().getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        Path token=Files.createTempFile("audit-test-",".token");Files.writeString(token,"synthetic-credential-for-local-unit-test");
        try{
            var configured=new AuditConsoleService(db,mapper,"ehm","admin","http://127.0.0.1:"+server.getAddress().getPort()+"/api/logs/v1/events",token.toString(),"test-domain","zone-a","audit_outbox");
            assertEquals("COMMITTED",configured.receipt(request("u1"),"E1").get("chainState"));
            body.set(body.get().replace(hash,"a".repeat(64)));assertEquals(502,assertThrows(ResponseStatusException.class,()->configured.receipt(request("u1"),"E1")).getStatusCode().value());
        }finally{server.stop(0);Files.delete(token);}
    }
}
