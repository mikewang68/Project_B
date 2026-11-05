package com.mt.wms.auth;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantContextAuthorizationTest {
    @Test void readonlyMaySelectOnlyAnApprovedWarehouseAndOwnerInOwnSession() {
        var repository=mock(AuthRepository.class);
        var principal=new WmsPrincipal(7L,3L,"demo","Demo","reader","Reader",
                List.of("READONLY"),Set.of("stockin:read"),false);
        var first=new AuthModels.TenantOption(11L,"first","First");
        var second=new AuthModels.TenantOption(12L,"second","Second");
        var owner=new AuthModels.TenantOption(21L,"owner","Owner");
        when(repository.findWarehouses(7L,3L,false)).thenReturn(List.of(first,second));
        when(repository.findOwners(7L,3L,false)).thenReturn(List.of(owner));
        var service=new TenantContextService(repository);
        var session=new MockHttpSession();var other=new MockHttpSession();
        service.current(principal,other);
        assertEquals(second,service.switchTo(principal,session,"second","owner").currentWarehouse());
        assertEquals(11L,other.getAttribute(TenantContextService.WAREHOUSE_ID));
        assertThrows(AccessDeniedException.class,()->service.switchTo(principal,session,"unapproved","owner"));
        assertThrows(AccessDeniedException.class,()->service.switchTo(principal,session,"second","unapproved"));
        assertEquals(12L,session.getAttribute(TenantContextService.WAREHOUSE_ID));
        assertEquals(21L,session.getAttribute(TenantContextService.OWNER_ID));
        verify(repository,never()).findWarehouses(7L,3L,true);
        verify(repository,never()).findOwners(7L,3L,true);
    }
    @Test void readonlyPolicyExceptionMatchesOnlyExactTenantMethodAndPath() {
        assertFalse(WmsReadOnlyPolicy.denies("PUT","/api/v1/auth/tenant"));
        assertTrue(WmsReadOnlyPolicy.denies("POST","/api/v1/auth/tenant"));
        assertTrue(WmsReadOnlyPolicy.denies("PUT","/api/v1/auth/tenant/other"));
        assertTrue(WmsReadOnlyPolicy.denies("PUT","/api/v1/stockin/1"));
        assertTrue(WmsReadOnlyPolicy.denies("POST","/api/v1/integration/tasks/export/STOCKIN"));
        assertTrue(WmsReadOnlyPolicy.denies("GET","/api/v1/integration/tasks/1/download"));
    }
}
