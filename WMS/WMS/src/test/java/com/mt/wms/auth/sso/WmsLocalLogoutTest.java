package com.mt.wms.auth.sso;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class WmsLocalLogoutTest {
    @BeforeEach void reset(){WmsSsoLogoutRegistry.resetForTests();}
    @AfterEach void cleanup(){WmsSsoLogoutRegistry.resetForTests();}
    @Test void expiresTheActualRuntimeCookieNamePathDomainAndSecureFlag(){
        var context=new MockServletContext();var config=context.getSessionCookieConfig();
        config.setName("LAB_WMS_SESSION");config.setPath("/warehouse");config.setDomain("wms.example.test");config.setSecure(true);
        var request=new MockHttpServletRequest(context);var response=new MockHttpServletResponse();
        request.setSession(new MockHttpSession(context));String id=request.getSession(false).getId();
        WmsSsoLogoutRegistry.bind(id,"synthetic-sid","synthetic-subject");
        assertTrue(WmsLocalLogout.clear(request,response));
        assertNull(request.getSession(false));assertTrue(WmsSsoLogoutRegistry.isSessionRevoked(id));assertNull(WmsSsoLogoutRegistry.get(id));
        String cookie=response.getHeaders("Set-Cookie").stream().filter(value->value.startsWith("LAB_WMS_SESSION=")).findFirst().orElseThrow();
        assertTrue(cookie.contains("Path=/warehouse"));assertTrue(cookie.contains("Domain=wms.example.test"));
        assertTrue(cookie.contains("Max-Age=0"));assertTrue(cookie.contains("Secure"));
        assertEquals("complete",response.getHeader("X-Wms-Local-Logout"));
    }
}
