package com.mt.wms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveAccountFilterTest {
    private WmsPrincipal principal(Set<String> permissions) {
        return new WmsPrincipal(1L,2L,"c","Company","user","User",List.of("ADMIN"),permissions,false);
    }
    private void login(WmsPrincipal p) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p,null,p.getAuthorities()));
    }
    private MockHttpServletRequest request() {
        var request=new MockHttpServletRequest("GET","/api/v1/inventory/balances");
        request.setServletPath("/api/v1/inventory/balances");return request;
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void deletedAccountCannotUseAnExistingSession() throws Exception {
        AuthRepository repository=mock(AuthRepository.class);
        var p=principal(Set.of("master:write"));login(p);
        when(repository.refreshPrincipal(p)).thenReturn(Optional.empty());
        var request=request();request.getSession();
        var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        new ActiveAccountFilter(repository,new ObjectMapper()).doFilter(request,response,(req,res)->called.set(true));
        assertEquals(401,response.getStatus());assertFalse(called.get());assertNull(request.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void revokedWriteGrantIsRemovedBeforeRequestAuthorization() throws Exception {
        AuthRepository repository=mock(AuthRepository.class);
        var old=principal(Set.of("master:write","master:read"));login(old);
        when(repository.refreshPrincipal(old)).thenReturn(Optional.of(principal(Set.of("master:read"))));
        new ActiveAccountFilter(repository,new ObjectMapper()).doFilter(request(),new MockHttpServletResponse(),(req,res)->{
            var authorities=SecurityContextHolder.getContext().getAuthentication().getAuthorities();
            assertTrue(authorities.stream().anyMatch(a->a.getAuthority().equals("master:read")));
            assertFalse(authorities.stream().anyMatch(a->a.getAuthority().equals("master:write")));
        });
    }

    @Test void anonymousRequestStillReachesNormalAuthenticationRules() throws Exception {
        AuthRepository repository=mock(AuthRepository.class);var called=new AtomicBoolean();
        new ActiveAccountFilter(repository,new ObjectMapper()).doFilter(request(),new MockHttpServletResponse(),(req,res)->called.set(true));
        assertTrue(called.get());verifyNoInteractions(repository);
    }
}
