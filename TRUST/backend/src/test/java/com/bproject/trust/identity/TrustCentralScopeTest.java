package com.bproject.trust.identity;

import com.bproject.iam.context.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrustCentralScopeTest {
    UserContext walletContext(String level,boolean readonly,Set<String> actions){
        return globalContext(level,readonly,Map.of("TRUST:wallets",actions));
    }
    UserContext globalContext(String level,boolean readonly,Map<String,Set<String>> actions){
        var f=ScopeSql.build(ScopeSql.Mode.GLOBAL,List.of());
        return new UserContext(2,"subject","user","subject","user",level,readonly,"TRUST",null,null,null,
            ScopeSql.Mode.GLOBAL,List.of(),List.of(),Set.of("role"),actions.keySet(),actions,
            Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    }
    @Test void walletManagementRequiresGlobalL1AndExplicitIndependentActions(){
        String review="/api/v1/wallets/requests/00000000-0000-0000-0000-000000000001/review";
        var propose=walletContext("L1_SUPER",false,Set.of("VIEW","EDIT"));
        assertTrue(TrustCentralScope.permits(propose,"POST","/api/v1/wallets/requests"));
        assertFalse(TrustCentralScope.permits(propose,"POST",review));
        assertTrue(TrustCentralScope.permissions(propose,false).contains("trust:wallet:manage"));
        assertFalse(TrustCentralScope.permissions(propose,false).contains("trust:wallet:review"));
        var reviewer=walletContext("L1_SUPER",false,Set.of("VIEW","CONFIRM"));
        assertTrue(TrustCentralScope.permits(reviewer,"POST",review));
        assertFalse(TrustCentralScope.permits(reviewer,"POST","/api/v1/wallets/requests"));
        assertTrue(TrustCentralScope.permissions(reviewer,false).contains("trust:wallet:review"));
        assertFalse(TrustCentralScope.permits(walletContext("L1_SUPER",false,Set.of("EDIT","CONFIRM")),"POST",review));
        assertFalse(TrustCentralScope.permits(context(false,Map.of("TRUST:wallets",Set.of("VIEW","EDIT","CONFIRM"))),"POST",review));
        assertFalse(TrustCentralScope.permits(walletContext("L2_PROJECT",false,Set.of("VIEW","EDIT","CONFIRM")),"POST",review));
    }
    @Test void walletReadonlyAndUnregisteredRoutesFailClosed(){
        var reader=walletContext("L1_READONLY",true,Set.of("VIEW","EDIT","CONFIRM"));
        assertTrue(TrustCentralScope.permits(reader,"GET","/api/v1/wallets"));
        assertEquals(Set.of("trust:wallet:read"),TrustCentralScope.permissions(reader,true));
        assertFalse(TrustCentralScope.permits(reader,"POST","/api/v1/wallets/requests"));
        assertFalse(TrustCentralScope.permits(walletContext("L1_SUPER",false,Set.of("VIEW","EDIT","CONFIRM")),"POST","/api/v1/wallets/requests/bad/review"));
        assertFalse(TrustCentralScope.permits(reader,"GET","/api/v1/wallets/export-all"));
    }
    UserContext context(boolean readonly,Set<String> actions){
        return context(readonly,Map.of("TRUST:events",actions));
    }
    UserContext context(boolean readonly,Map<String,Set<String>> actions){
        var t=new ScopeTriple("PROJECT_B","SHEN_TIE","TRUST");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
        return new UserContext(2,"subject","user","subject","user","L4_OPERATOR",readonly,"TRUST","PROJECT_B","SHEN_TIE","TRUST",ScopeSql.Mode.SCOPED,List.of(t),List.of(t),Set.of("role"),actions.keySet(),actions,Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    }
    @Test void readonlyVerificationDoesNotGrantSubmissionOrCorrection(){
        var c=context(true,Set.of("VIEW","AUDIT_VERIFY","EXPORT","DOWNLOAD","CHAIN_SUBMIT","EDIT"));
        assertTrue(TrustCentralScope.permits(c,"POST","/api/v1/events/one/verify"));
        assertTrue(TrustCentralScope.permits(c,"POST","/api/v1/events/one/export"));
        assertFalse(TrustCentralScope.permits(c,"POST","/api/v1/events"));
        assertFalse(TrustCentralScope.permits(c,"POST","/api/v1/events/one/corrections"));
        var permissions=TrustCentralScope.permissions(c,true);
        assertTrue(permissions.contains("trust:evidence:verify"));assertFalse(permissions.contains("trust:event:submit"));
    }
    @Test void correctionNeedsBothActionsAndUnadaptedResourcesAreDenied(){
        assertFalse(TrustCentralScope.permits(context(false,Set.of("CHAIN_SUBMIT")),"POST","/api/v1/events/one/corrections"));
        assertTrue(TrustCentralScope.permits(context(false,Set.of("CHAIN_SUBMIT","EDIT")),"POST","/api/v1/events/one/corrections"));
        assertFalse(TrustCentralScope.permits(context(false,Set.of("VIEW")),"GET","/api/v1/wallets"));
    }
    @Test void pageReceiptsAllowOnlySelfCollectionAndNeverGlobalAuditReads(){
        var c=context(true,Set.of("VIEW"));
        String result="/api/v1/audit/page-actions/12345678-abcd-1234-abcd-123456789abc/result";
        assertTrue(TrustCentralScope.permits(c,"POST","/api/v1/audit/page-actions"));
        assertTrue(TrustCentralScope.permits(c,"POST",result));
        assertFalse(TrustCentralScope.permits(c,"GET","/api/v1/audit/page-actions"));
        assertFalse(TrustCentralScope.permits(c,"POST","/api/v1/audit/page-actions/bad/result"));
        assertFalse(TrustCentralScope.permits(c,"GET","/api/v1/audit/settings"));
        assertFalse(TrustCentralScope.permits(c,"POST","/api/v1/audit/delivery/one/retry"));
    }
    @Test void traceAndEventAuthoritiesRemainIndependent(){
        var c=context(true,Map.of("TRUST:trace",Set.of("VIEW")));
        var permissions=TrustCentralScope.permissions(c,true);
        assertTrue(permissions.contains("trust:trace:read"));
        assertFalse(permissions.contains("trust:event:read"));
        assertFalse(TrustCentralScope.permits(c,"GET","/api/v1/events"));
        assertTrue(TrustCentralScope.permits(c,"GET","/api/v1/trace"));
        assertTrue(TrustCentralScope.permits(context(true,Set.of("VIEW")),
            "GET","/api/v1/events/one/processing-history"));
    }
    @Test void retryAuthorizationUsesTheActualControllerRouteAndTaskAction() throws Exception {
        var handler=com.bproject.trust.archiving.TaskController.class.getMethod("retry",
            org.springframework.security.core.Authentication.class,String.class);
        var mapping=handler.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        String actual="/api/v1"+mapping.value()[0].replace("{id}","one");
        assertEquals("/api/v1/events/one/retry",actual);
        var c=context(false,Map.of("TRUST:tasks",Set.of("CHAIN_SUBMIT")));
        assertTrue(TrustCentralScope.permits(c,"POST",actual));
        assertTrue(TrustCentralScope.permissions(c,false).contains("trust:task:retry"));
        assertFalse(TrustCentralScope.permits(c,"POST","/api/v1/tasks/one/retry"));
        assertFalse(TrustCentralScope.permits(context(true,Map.of("TRUST:tasks",Set.of("CHAIN_SUBMIT"))),"POST",actual));
    }
    @Test void baseOwnershipFiltersPreserveRetentionViewsAndScopeIsNotInherited() throws Exception {
        try(var scope=TrustCentralScope.bind(context(false,Set.of("VIEW")))){
            String sql=TrustCentralScope.predicate(TrustCentralScope.Root.EVENT,"events_read");
            assertTrue(sql.contains("o.id=events_read.id"));assertTrue(sql.contains("o.region_id = 'SHEN_TIE'"));
            assertThrows(IllegalArgumentException.class,()->TrustCentralScope.predicate(TrustCentralScope.Root.EVENT,"browserSQL"));
            var inherited=new java.util.concurrent.atomic.AtomicReference<UserContext>();var thread=new Thread(()->inherited.set(TrustCentralScope.current()));thread.start();thread.join();assertNull(inherited.get());
        }
        assertNull(TrustCentralScope.current());
    }
    @Test void organizationIdentityAndStatusRequireExplicitGlobalL1View(){
        var grants=Map.of("TRUST:identities",Set.of("VIEW"),"TRUST:status",Set.of("VIEW","EDIT"));
        var reader=globalContext("L1_READONLY",true,grants);var regional=context(false,grants);
        String id="00000000-0000-0000-0000-000000000001";
        for(String path:List.of("/api/v1/status","/api/v1/audit","/api/v1/audit/settings","/api/v1/audit/settings/history","/api/v1/audit/delivery",
                "/api/v1/identity-management","/api/v1/identity-management/"+id+"/history","/api/v1/identity-management/"+id+"/certificates/history","/api/v1/identity-management/"+id+"/certificates/1/history")){
            assertTrue(TrustCentralScope.permits(reader,"GET",path),path);assertFalse(TrustCentralScope.permits(regional,"GET",path),path);
        }
        assertTrue(TrustCentralScope.permissions(reader,true).containsAll(Set.of("trust:identity:read","trust:operations:read","trust:audit:read")));
        assertFalse(TrustCentralScope.permits(reader,"PATCH","/api/v1/audit/settings"));
        assertFalse(TrustCentralScope.permits(reader,"POST","/api/v1/identities"));
        assertFalse(TrustCentralScope.permits(reader,"GET","/api/v1/identity-management/"+id+"/private-key"));
        assertFalse(TrustCentralScope.permits(globalContext("L2_PROJECT",false,grants),"GET","/api/v1/status"));
    }
    @Test void globalSettingsEditDoesNotFollowFromViewOrLegacyAdministratorRole(){
        var read=globalContext("L1_SUPER",false,Map.of("TRUST:status",Set.of("VIEW")));
        var edit=globalContext("L1_SUPER",false,Map.of("TRUST:status",Set.of("VIEW","EDIT")));
        assertFalse(TrustCentralScope.permits(read,"PATCH","/api/v1/audit/settings"));
        assertTrue(TrustCentralScope.permits(edit,"PATCH","/api/v1/audit/settings"));
        assertFalse(TrustCentralScope.permits(edit,"DELETE","/api/v1/audit/settings"));
        assertFalse(TrustCentralScope.permits(edit,"POST","/api/v1/audit/delivery/bad/retry"));
    }
    @Test void walletHistoryUsesSameGlobalReadBoundary(){
        var reader=walletContext("L1_READONLY",true,Set.of("VIEW"));String id="00000000-0000-0000-0000-000000000001";
        for(String path:List.of("/api/v1/wallets/"+id+"/history","/api/v1/wallets/requests/"+id+"/history","/api/v1/wallets/bindings/LW-MEETING/history"))assertTrue(TrustCentralScope.permits(reader,"GET",path));
        assertFalse(TrustCentralScope.permits(reader,"GET","/api/v1/wallets/bindings/../history"));
    }
}
