package com.bdemo.config;

import com.bproject.iam.context.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmcsCentralScopeTest {
    UserContext context(boolean readonly,Set<String> actions){
        var t=new ScopeTriple("PROJECT_B","SHEN_TIE","EMCS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
        return new UserContext(2,"subject","operator","subject","operator","L4_OPERATOR",readonly,"EMCS","PROJECT_B","SHEN_TIE","EMCS",ScopeSql.Mode.SCOPED,
            List.of(t),List.of(t),Set.of("role"),Set.of("EMCS:2014"),Map.of("EMCS:2014",actions),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    }
    @Test void readonlyExportNeedsExplicitActionAndNeverImpliesArchiveOrAdministration(){
        var reader=context(true,Set.of("VIEW","EXPORT","ADD"));
        assertTrue(EmcsCentralScope.permits(reader,"POST","/reports/preview"));
        assertTrue(EmcsCentralScope.permits(reader,"POST","/reports/export"));
        assertFalse(EmcsCentralScope.permits(reader,"POST","/reports/archives"));
        assertFalse(EmcsCentralScope.permits(context(true,Set.of("VIEW")),"POST","/reports/export"));
        assertFalse(EmcsCentralScope.permits(reader,"GET","/system/user/list"));
        assertFalse(EmcsCentralScope.permits(reader,"POST","/reports/unreviewed-command"));
    }
    @Test void predicatesUseAreaOwnershipAndRejectClientChosenSqlColumns() throws Exception {
        try(var scope=EmcsCentralScope.bind(context(false,Set.of("VIEW")))){
            var sql=EmcsCentralScope.area("eq.area_id");assertTrue(sql.contains("eq.area_id IN"));assertTrue(sql.contains("o.region_id = 'SHEN_TIE'"));
            assertThrows(IllegalArgumentException.class,()->EmcsCentralScope.area("client SQL"));
        }
        assertNull(EmcsCentralScope.current());
    }
    @Test void nativePermissionsRemainAnIndependentCeilingAndDoNotExposeUnadaptedControls(){
        var nativePermissions=Set.of("energy:cost:report","energy:analysis:profile","system:user:edit","*:*:*");
        assertEquals(Set.of("energy:cost:report"),EmcsCentralScope.nativePermissions(nativePermissions,context(true,Set.of("VIEW","EXPORT"))));
        assertTrue(EmcsCentralScope.nativePermissions(Set.of("system:user:edit"),context(false,Set.of("VIEW"))).isEmpty());
        assertTrue(EmcsCentralScope.nativePermissions(nativePermissions,context(true,Set.of("ADD"))).isEmpty());
        assertEquals(nativePermissions,EmcsCentralScope.nativePermissions(nativePermissions,null));
    }
}
