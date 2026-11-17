package com.bproject.ehm.platform.security;

import com.bproject.iam.context.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RestoredFeaturePermissionsTest {
    static UserContext context(String subject,String region,boolean readOnly,Map<String,Set<String>> menus){
        var t=new ScopeTriple("PROJECT_B",region,"EHMS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
        return new UserContext(2,subject,subject,subject,subject,"L4_OPERATOR",readOnly,"EHMS","PROJECT_B",region,"EHMS",ScopeSql.Mode.SCOPED,
            List.of(t),List.of(t),Set.of(),menus.keySet(),menus,Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    }
    @Test void onlyExplicitImplementedRoutesAreGranted(){
        for(var request:List.of(new String[]{"GET","/api/ehm/v1/devices/D/health-assessments/latest"},
                new String[]{"POST","/api/ehm/v1/devices/D/health-assessments/run"},
                new String[]{"GET","/api/ehm/v1/assets/D/components"},new String[]{"GET","/api/ehm/v1/assistant/status"},
                new String[]{"POST","/api/ehm/v1/assistant/chat"},new String[]{"GET","/api/ehm/v1/assistant/models"},new String[]{"GET","/api/ehm/v1/maintenance-plans"},
                new String[]{"POST","/api/ehm/v1/inspection-tasks/I/submit"},new String[]{"GET","/api/ehm/v1/spare-stock/summary"},
                new String[]{"GET","/api/ehm/v1/devices/D/health-summary"},new String[]{"GET","/api/ehm/v1/work-orders/W/repair"},
                new String[]{"POST","/api/ehm/v1/work-orders/W/repair/close"},new String[]{"POST","/api/ehm/v1/work-orders/W/case/retest-plan"},
                new String[]{"POST","/api/ehm/v1/devices/D/maintenance-reminders/scan"},new String[]{"POST","/api/ehm/v1/maintenance-reminders/R/complete"},
                new String[]{"POST","/api/ehm/v1/alarms/A/case/evidence-windows"},new String[]{"POST","/api/ehm/v1/alarms/A/case/diagnoses/I/revisions"})){
            var rule=EhmFeatureRoutes.resolve(request[0],request[1]);assertNotNull(rule);
            var c=context("U","REGION_A",false,Map.of("EHMS:"+rule.menus().get(0),Set.of(rule.action())));
            assertTrue(EhmCentralScope.permits(c,request[0],request[1]),request[1]);
            assertFalse(EhmCentralScope.permits(context("U","REGION_A",false,Map.of()),request[0],request[1]));
        }
        for(String path:List.of("/api/ehm/v1/assistant/execute","/api/ehm/v1/devices/D/health-assessments/erase",
                "/api/ehm/v1/health-assessments/H/unknown","/api/ehm/v1/system/audit-events",
                "/api/ehm/v1/work-orders/W/repair/unsafe","/api/ehm/v1/devices/D/maintenance-reminders/reset","/api/ehm/v1/devices/D/health-summary/rebuild"))
            assertFalse(EhmCentralScope.permits(context("U","REGION_A",false,Map.of("EHMS:fleet",Set.of("EDIT","VIEW"),"EHMS:workorders",Set.of("EDIT","VIEW","CONFIRM"))),"POST",path));
    }
    @Test void readOnlyAssistantUsesReadPermissionButCannotWriteBusiness(){
        var c=context("U","REGION_A",true,Map.of("EHMS:ai-assistant",Set.of("VIEW"),"EHMS:health",Set.of("VIEW","EDIT")));
        var a=new EhmSsoSessions.Actor("U","operator","USER","S",true,Set.of("PROJECT_B"),Set.of("ehealth:wb:assistant:view"),false,c);
        assertTrue(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/assistant/chat"));
        assertTrue(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/assistant/models"));
        assertFalse(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/devices/D/health-assessments/run"));
        assertFalse(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/my-tasks"));
    }
    @Test void nativeAndCentralPermissionsBothRequiredEvenForAssistant(){
        var c=context("U","REGION_A",false,Map.of("EHMS:ai-assistant",Set.of("VIEW")));
        var a=new EhmSsoSessions.Actor("U","operator","USER","S",false,Set.of("PROJECT_B"),Set.of(),false,c);
        assertFalse(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/assistant/chat"));
        assertFalse(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/assistant/models"));
    }
}
