package com.mt.wms.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.*;
import com.mt.wms.common.config.SecurityConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real configured SecurityFilterChain, isolated MVC handlers and synthetic IAM responses. */
@WebMvcTest(controllers={AuthController.class,WmsSecurityFilterChainTest.BusinessProbe.class})
@Import({SecurityConfiguration.class,WmsSecurityFilterChainTest.BusinessProbe.class})
class WmsSecurityFilterChainTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean AuthRepository repository;
    @MockitoBean AuthService auth;
    @MockitoBean TenantContextService tenant;
    @MockitoBean WmsSsoExchangeService iam;

    @BeforeEach void setup(){WmsSsoLogoutRegistry.resetForTests();}
    @AfterEach void cleanup(){WmsSsoLogoutRegistry.resetForTests();SecurityContextHolder.clearContext();}

    WmsPrincipal principal(String role){return new WmsPrincipal(7L,3L,"demo","Demo","user","User",List.of(role),
            Set.of("inventory:read","integration:manage","master:write"),false);}
    MockHttpSession session(String role,boolean sso){
        var principal=principal(role);
        var session=new MockHttpSession();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,context);
        when(repository.refreshPrincipal(any())).thenReturn(Optional.of(principal));
        if(sso){
            session.setAttribute(WmsSsoSessionGuardFilter.ATTR_SSO_ESTABLISHED,true);
            WmsSsoLogoutRegistry.bind(session.getId(),"synthetic-sid","0123456789abcdef0123456789abcdef");
        }
        return session;
    }
    void central(boolean readOnly) throws Exception {
        var current=json.readTree("{\"readOnly\":"+readOnly+",\"permissions\":[\"inventory:read\",\"integration:manage\",\"master:write\"]}");
        when(iam.currentAuthorization(anyString(),anyString(),any(WmsPrincipal.class))).thenReturn(current);
    }
    @Test void localReadonlyHardVetoBlocksGetDownloadsEvenWithAccidentalGrant() throws Exception {
        for(String role:List.of("AUDITOR","READONLY","ROLE_READONLY","READONLY_AUDITOR","PLATFORM_AUDITOR_READONLY")) {
            mvc.perform(get("/api/v1/probe/download").session(session(role,false)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_READONLY"));
            mvc.perform(post("/api/v1/probe/write").session(session(role,false)).with(csrf()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_READONLY"));
        }
    }
    @Test void readonlyStillReadsAndCanMaintainOwnPasswordButNeedsCsrf() throws Exception {
        var session=session("READONLY",false);
        mvc.perform(get("/api/v1/probe/read").session(session)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/auth/password").session(session).contentType("application/json")
                .content("{\"currentPassword\":\"synthetic-old\",\"newPassword\":\"synthetic-new\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/auth/password").session(session).with(csrf()).contentType("application/json")
                .content("{\"currentPassword\":\"synthetic-old\",\"newPassword\":\"synthetic-new\"}"))
                .andExpect(status().isOk());
    }
    @Test void centralReadonlyHasSamePasswordExceptionAndDownloadVeto() throws Exception {
        central(true);
        mvc.perform(put("/api/v1/auth/password").session(session("ADMIN",true)).with(csrf()).contentType("application/json")
                .content("{\"currentPassword\":\"synthetic-old\",\"newPassword\":\"synthetic-new\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/probe/download").session(session("ADMIN",true)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_READONLY"));
    }
    @Test void revokedOrChangedScopeCannotReachBusinessHandler() throws Exception {
        when(iam.currentAuthorization(anyString(),anyString(),any(WmsPrincipal.class))).thenThrow(new SecurityException("scope changed"));
        var session=session("MANAGER",true);String id=session.getId();
        mvc.perform(get("/api/v1/probe/read").session(session)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_REVOKED"));
        assertTrue(session.isInvalid());assertTrue(WmsSsoLogoutRegistry.isSessionRevoked(id));
    }
    @Test void unavailableIamBlocksBusinessWithoutDestroyingSession() throws Exception {
        when(iam.currentAuthorization(anyString(),anyString(),any(WmsPrincipal.class))).thenThrow(new IllegalStateException("unavailable"));
        var session=session("MANAGER",true);
        mvc.perform(get("/api/v1/probe/read").session(session)).andExpect(status().isServiceUnavailable());
        assertFalse(session.isInvalid());
    }
    @Test void unavailableIamStillAllowsCsrfProtectedLocalLogoutAndReportsGlobalFailure() throws Exception {
        when(iam.currentAuthorization(anyString(),anyString(),any(WmsPrincipal.class))).thenThrow(new IllegalStateException("unavailable"));
        doThrow(new IllegalStateException("unavailable")).when(iam).globalLogout(anyString(),anyString());
        var session=session("MANAGER",true);String id=session.getId();
        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SSO_GLOBAL_LOGOUT_INCOMPLETE"))
                .andExpect(header().string("X-Wms-Local-Logout","complete"))
                .andExpect(result -> {
                    var cookies=result.getResponse().getHeaders("Set-Cookie");
                    assertTrue(cookies.stream().anyMatch(value->value.startsWith("JSESSIONID=")&&value.contains("Max-Age=0")));
                    assertTrue(cookies.stream().anyMatch(value->value.startsWith("XSRF-TOKEN=")&&value.contains("Max-Age=0")));
                });
        assertTrue(session.isInvalid());assertTrue(WmsSsoLogoutRegistry.isSessionRevoked(id));
        assertNull(WmsSsoLogoutRegistry.get(id));verify(iam,never()).currentAuthorization(anyString(),anyString(),any());
    }
    @Test void logoutWithoutCsrfCannotRevokeOrClearAnySession() throws Exception {
        var session=session("MANAGER",true);
        mvc.perform(post("/api/v1/auth/logout").session(session)).andExpect(status().isForbidden());
        assertFalse(session.isInvalid());verify(iam,never()).globalLogout(anyString(),anyString());
    }
    @Test void centralFullFlagDoesNotBypassLocalReadonlyRole() throws Exception {
        when(iam.currentAuthorization(anyString(),anyString(),any(WmsPrincipal.class)))
                .thenReturn(json.readTree("{\"readOnly\":false,\"superAdmin\":true,\"permissions\":[\"master:write\"]}"));
        mvc.perform(post("/api/v1/probe/write").session(session("READONLY",true)).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_READONLY"));
    }
    @Test void writableLocalAndSsoBusinessSessionsStillReachRealHandlers() throws Exception {
        mvc.perform(post("/api/v1/probe/write").session(session("MANAGER",false)).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().string("write"));
        central(false);
        mvc.perform(post("/api/v1/probe/write").session(session("MANAGER",true)).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().string("write"));
    }
    @Test void requestUriStillRefreshesDisabledAccountsWhenServletPathIsEmpty() throws Exception {
        var session=session("MANAGER",false);
        when(repository.refreshPrincipal(any())).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/probe/read").servletPath("").session(session))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        verify(repository).refreshPrincipal(any());assertTrue(session.isInvalid());
    }
    @Test void currentCentralPermissionRemovalAppliesWithEmptyServletPath() throws Exception {
        var session=session("MANAGER",true);
        when(iam.currentAuthorization(anyString(),anyString(),any(WmsPrincipal.class)))
                .thenReturn(json.readTree("{\"readOnly\":false,\"permissions\":[\"inventory:read\"]}"));
        mvc.perform(post("/api/v1/probe/write").servletPath("").session(session).with(csrf()))
                .andExpect(status().isForbidden());
        verify(repository).refreshPrincipal(any());
    }
    @Test void newlyAssignedLocalReadonlyRoleBlocksPreviouslyWritableSession() throws Exception {
        var session=session("MANAGER",false);
        when(repository.refreshPrincipal(any())).thenReturn(Optional.of(principal("READONLY")));
        mvc.perform(post("/api/v1/probe/write").servletPath("").session(session).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_READONLY"));
    }
    @RestController static class BusinessProbe {
        @GetMapping("/api/v1/probe/read") String read(){return "read";}
        @GetMapping("/api/v1/probe/download") String download(){return "download";}
        @PostMapping("/api/v1/probe/write")
        @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('master:write')")
        public String write(){return "write";}
    }
}
