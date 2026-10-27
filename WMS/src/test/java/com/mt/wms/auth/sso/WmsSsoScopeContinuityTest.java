package com.mt.wms.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mt.wms.auth.AuthModels;
import com.mt.wms.auth.AuthRepository;
import com.mt.wms.auth.WmsPrincipal;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic HTTP IAM only; no production identities, database or IAM credentials. */
class WmsSsoScopeContinuityTest {
    static final String SUBJECT="0123456789abcdef0123456789abcdef";
    final ObjectMapper json=new ObjectMapper();
    final WmsSsoSubjectRepository subjects=mock(WmsSsoSubjectRepository.class);
    final AuthRepository auth=mock(AuthRepository.class);
    final WmsPrincipal established=new WmsPrincipal(7L,3L,"demo","demo","reader","reader",
            List.of("MANAGER"),Set.of("inventory:read","master:write"),false);
    HttpServer server;
    ObjectNode data;
    WmsSsoExchangeService service;

    @BeforeEach void start() throws Exception {
        WmsSsoLogoutRegistry.resetForTests();
        data=json.createObjectNode().put("subject",SUBJECT).put("sessionId","synthetic-sid")
                .put("audience","wms").put("localSubject","reader").put("company","demo")
                .put("readOnly",false).put("active",true);
        data.putArray("permissions").add("inventory:read");
        data.putArray("allowedAppCodes").add("WMS");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/auth/sso/",exchange->{
            byte[] bytes=json.createObjectNode().put("code",0).set("data",data).toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var properties=mock(WmsSsoProperties.class);
        when(properties.isUsable()).thenReturn(true);when(properties.getAudience()).thenReturn("wms");
        when(properties.getTimeoutMs()).thenReturn(2000);when(properties.getServiceToken()).thenReturn("synthetic-token");
        when(properties.getIamBaseUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
        when(subjects.findActive(SUBJECT)).thenReturn(Optional.of(new WmsSsoSubjectRepository.Binding(7L,"reader","demo",null,false)));
        when(auth.findCompanyId("demo")).thenReturn(Optional.of(3L));
        when(auth.findRoles(7L)).thenReturn(List.of("MANAGER"));
        when(auth.findPermissions(7L)).thenReturn(established.permissions());
        when(auth.findWarehouses(7L,3L,false)).thenReturn(List.of(new AuthModels.TenantOption(1L,"W","Warehouse")));
        when(auth.findOwners(7L,3L,false)).thenReturn(List.of(new AuthModels.TenantOption(2L,"O","Owner")));
        service=new WmsSsoExchangeService(properties,subjects,auth,json);
    }
    @AfterEach void stop(){server.stop(0);WmsSsoLogoutRegistry.resetForTests();}

    @Test void firstExchangeAlreadyUsesCurrentCentralIntersection(){
        var result=service.exchange("synthetic");
        assertTrue(result.ok());assertEquals(Set.of("inventory:read"),result.principal().permissions());
    }
    @Test void exchangeRequiresExplicitCompanyInsteadOfTreatingMissingAsGlobal(){
        data.remove("company");assertEquals("SSO_SCOPE_MISMATCH",service.exchange("synthetic").errorCode());
    }
    @Test void everyUnsupportedScopeIsRejectedAtExchangeAndLaterIntrospection(){
        for(String field:List.of("org","domain","zone")) {
            data.put(field,"scope-added-after-login");
            assertFalse(service.exchange("synthetic").ok(),field);
            assertThrows(SecurityException.class,()->service.currentAuthorization(SUBJECT,"synthetic-sid",established),field);
            data.remove(field);
        }
    }
    @Test void localMappingZoneAddedLaterCannotSilentlyExpandScope(){
        when(subjects.findActive(SUBJECT)).thenReturn(Optional.of(new WmsSsoSubjectRepository.Binding(7L,"reader","demo","zone-added",false)));
        assertThrows(SecurityException.class,()->service.currentAuthorization(SUBJECT,"synthetic-sid",established));
    }
    @Test void unsupportedProjectRestrictionCannotBecomeCompanyWideAccess(){
        data.putArray("projectIds").add("project-only");
        assertFalse(service.exchange("synthetic").ok());
        assertThrows(SecurityException.class,()->service.currentAuthorization(SUBJECT,"synthetic-sid",established));
        data.put("projectIds","invalid-shape");
        assertThrows(SecurityException.class,()->service.currentAuthorization(SUBJECT,"synthetic-sid",established));
        data.putArray("projectIds");
        assertDoesNotThrow(()->service.currentAuthorization(SUBJECT,"synthetic-sid",established));
    }
    @Test void remappingSameUsernameToAnotherLocalUserCannotReuseOldSession(){
        when(subjects.findActive(SUBJECT)).thenReturn(Optional.of(new WmsSsoSubjectRepository.Binding(8L,"reader","demo",null,false)));
        assertThrows(SecurityException.class,()->service.currentAuthorization(SUBJECT,"synthetic-sid",established));
    }
    @Test void changedCompanyOrSubjectOrSidCannotReuseSession(){
        for(String field:List.of("company","subject","sessionId","audience","localSubject")) {
            var previous=data.get(field);data.put(field,"changed");
            assertThrows(SecurityException.class,()->service.currentAuthorization(SUBJECT,"synthetic-sid",established),field);
            data.set(field,previous);
        }
    }
    @Test void validCurrentIdentityIsAllowed(){assertDoesNotThrow(()->service.currentAuthorization(SUBJECT,"synthetic-sid",established));}
}
