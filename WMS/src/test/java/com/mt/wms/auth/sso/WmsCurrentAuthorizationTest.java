package com.mt.wms.auth.sso;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.WmsPrincipal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class WmsCurrentAuthorizationTest {
    final ObjectMapper json=new ObjectMapper();
    final WmsPrincipal local=new WmsPrincipal(1L,1L,"test","test","admin","Test",List.of("ADMIN"),
            Set.of("inventory:read","master:write","tenant:all"),false);
    @Test void currentDowngradeRemovesPreviouslyGrantedWrites() throws Exception {
        var result=WmsCurrentAuthorization.apply(local,json.readTree("{\"readOnly\":false,\"superAdmin\":false,\"permissions\":[\"wms:inventory:read\"]}"));
        assertThat(result.permissions()).containsExactly("inventory:read");
    }
    @Test void readonlyVetoWinsOverFullAdminAndDoesNotGrantExport() throws Exception {
        var result=WmsCurrentAuthorization.apply(local,json.readTree("{\"readOnly\":true,\"superAdmin\":true,\"accessMode\":\"READONLY_AUDITOR\",\"permissions\":[\"wms:inventory:read\",\"wms:master:write\"]}"));
        assertThat(result.permissions()).containsExactly("inventory:read");
    }
    @Test void readonlyRoleDoesNotKeepAReadPermissionRemovedFromCentralAuthorization() throws Exception {
        var result=WmsCurrentAuthorization.apply(local,json.readTree("{\"readOnly\":true,\"superAdmin\":false,\"accessMode\":\"READONLY_AUDITOR\",\"permissions\":[]}"));
        assertThat(result.permissions()).isEmpty();
    }
    @Test void missingPolicyFailsClosed() throws Exception {
        assertThatThrownBy(()->WmsCurrentAuthorization.apply(local,json.readTree("{}"))).isInstanceOf(SecurityException.class);
    }
    @Test void fullCentralAdminCannotBypassLocalBusinessPermissionIntersection() throws Exception {
        var business=new WmsPrincipal(1L,1L,"test","test","user","User",List.of("MANAGER"),Set.of("inventory:read","master:write"),false);
        var result=WmsCurrentAuthorization.apply(business,json.readTree("{\"readOnly\":false,\"superAdmin\":true,\"permissions\":[\"inventory:read\"]}"));
        assertThat(result.permissions()).containsExactly("inventory:read");
    }
    @Test void applicationAdminFlagAloneDoesNotRegrantRemovedCentralPermission() throws Exception {
        var result=WmsCurrentAuthorization.apply(local,json.readTree("{\"readOnly\":false,\"applicationAdmin\":true,\"permissions\":[\"inventory:read\"]}"));
        assertThat(result.permissions()).containsExactly("inventory:read");
    }
}
