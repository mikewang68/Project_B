package com.bproject.ehm.platform.audit.adapter.in.web;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class IamAuditIdentityFilterTest {
    private void response(String body,String method,String path,int expected) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/me",e->{byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try{
            var f=new IamAuditIdentityFilter(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/me",true);
            var r=new MockHttpServletRequest(method,path);r.addHeader("Authorization","Bearer TEST-ONLY");
            r.addHeader("X-Read-Only","false");r.setParameter("readOnly","false");var s=new MockHttpServletResponse();
            new com.bproject.ehm.shared.web.TraceIdFilter().doFilter(r,s,(a,b)->f.doFilter(a,b,(x,y)->{assertEquals("u1",((jakarta.servlet.http.HttpServletRequest)x).getUserPrincipal().getName());}));
            assertEquals(expected,s.getStatus());assertNotNull(s.getHeader("X-Trace-Id"));assertEquals(s.getHeader("X-Trace-Id"),r.getAttribute("auditTraceId"));
        }finally{server.stop(0);}
    }
    @ParameterizedTest @ValueSource(ints={0,200}) void acceptsExplicitIamSuccessCodes(int code) throws Exception {
        response("{\"code\":"+code+",\"data\":{\"user\":{\"id\":\"u1\",\"status\":\"active\"}}}","GET","/api/ehm/v1/devices",200);
    }
    @ParameterizedTest @ValueSource(strings={"null","\"0\"","401","4294967296"}) void rejectsCoercedOrFailedSuccessCodes(String code) throws Exception {
        response("{\"code\":"+code+",\"data\":{\"user\":{\"id\":\"u1\"}}}","GET","/api/ehm/v1/devices",401);
    }
    @Test void rejectsMissingCode() throws Exception {response("{\"data\":{\"user\":{\"id\":\"u1\"}}}","GET","/api/ehm/v1/devices",401);}
    @Test void trustedReadonlyVetoWinsOverAdministrator() throws Exception {
        String body="{\"code\":0,\"data\":{\"user\":{\"id\":\"u1\"},\"roles\":[\"SUPER_ADMIN\",\"READONLY_AUDITOR\"]}}";
        response(body,"POST","/api/ehm/v1/devices",403);
        response(body,"GET","/api/ehm/v1/system/audit-evidence/export",403);
        response(body,"GET","/api/ehm/v1/devices",200);
    }
    @Test void trustedBooleanReadonlyIsNotCoercedFromText() throws Exception {
        response("{\"code\":0,\"data\":{\"user\":{\"id\":\"u1\"},\"readOnly\":true}}","DELETE","/api/ehm/v1/devices/D1",403);
        response("{\"code\":0,\"data\":{\"user\":{\"id\":\"u1\"},\"readOnly\":\"false\"}}","POST","/api/ehm/v1/devices",200);
    }
    @Test void requiredIdentityRejectsMissingBearer() throws Exception {
        var f=new IamAuditIdentityFilter(new ObjectMapper(),"http://127.0.0.1:1/me",true);var r=new MockHttpServletRequest("GET","/api/ehm/v1/devices");var s=new MockHttpServletResponse();f.doFilter(r,s,(a,b)->fail("must not execute"));assertEquals(401,s.getStatus());
    }
    @Test void identityComesFromValidatedIamResponseNotHeaders() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/me",e->{byte[] bytes="{\"code\":200,\"data\":{\"user\":{\"id\":\"verified-user\",\"status\":\"active\"}}}".getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try{var f=new IamAuditIdentityFilter(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/me",true);var r=new MockHttpServletRequest("GET","/api/ehm/v1/devices");r.addHeader("Authorization","Bearer TEST-ONLY");r.addHeader("X-EHM-Operator","forged-user");f.doFilter(r,new MockHttpServletResponse(),(a,b)->assertEquals("verified-user",((jakarta.servlet.http.HttpServletRequest)a).getUserPrincipal().getName()));assertEquals("verified-user",IamAuditIdentityFilter.actor(r).getName());}finally{server.stop(0);}
    }
}
