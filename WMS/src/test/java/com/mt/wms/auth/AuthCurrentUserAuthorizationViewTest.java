package com.mt.wms.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.sso.WmsCurrentAuthorization;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthCurrentUserAuthorizationViewTest {
    final AuthRepository repository=mock(AuthRepository.class);
    final TenantContextService tenant=mock(TenantContextService.class);
    final AuthService auth=new AuthService(repository,mock(PasswordEncoder.class),tenant);
    WmsPrincipal principal(String role,Set<String> permissions){return new WmsPrincipal(1L,2L,"demo","Demo","reader","Reader",List.of(role),permissions,false);}
    @Test void localReadonlyFlagIsExplicitAndVersionStableAcrossPermissionOrdering(){
        var first=auth.currentUser(principal("READONLY",Set.of("inventory:read","master:read")),new MockHttpSession());
        var second=auth.currentUser(principal("READONLY",Set.of("master:read","inventory:read")),new MockHttpSession());
        assertTrue(first.readOnly());assertEquals(first.authorizationVersion(),second.authorizationVersion());
        assertEquals(64,first.authorizationVersion().length());
    }
    @Test void effectiveCentralReadonlyAndRemovedGrantChangeTheFrontendVersion() throws Exception {
        var local=principal("MANAGER",Set.of("inventory:read","master:write"));
        var before=auth.currentUser(local,new MockHttpSession());
        var effective=WmsCurrentAuthorization.apply(local,new ObjectMapper().readTree("{\"readOnly\":true,\"permissions\":[\"inventory:read\",\"master:write\"]}"));
        var after=auth.currentUser(effective,new MockHttpSession());
        assertFalse(before.readOnly());assertTrue(after.readOnly());
        assertNotEquals(before.authorizationVersion(),after.authorizationVersion());
        assertEquals(Set.of("inventory:read"),after.permissions());
    }
}
