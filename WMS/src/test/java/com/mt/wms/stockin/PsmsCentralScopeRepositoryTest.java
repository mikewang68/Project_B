package com.mt.wms.stockin;

import com.bproject.iam.context.*;
import com.mt.wms.auth.sso.WmsCentralScope;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.ArgumentCaptor;

class PsmsCentralScopeRepositoryTest {
    final JdbcClient jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);
    final PsmsInboundRepository repository=new PsmsInboundRepository(jdbc);
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    void bind(ScopeSql.Mode mode,List<ScopeTriple> tuples){
        var f=ScopeSql.build(mode,tuples);var t=tuples.isEmpty()?new ScopeTriple("PROJECT","REGION","WMS"):tuples.get(0);
        var context=new UserContext(2,"subject","operator","subject","Operator","L4_OPERATOR",false,"WMS",
            t.projectId(),t.regionId(),t.subsystemCode(),mode,tuples,tuples,Set.of(),Set.of("WMS:stockin"),
            Map.of("WMS:stockin",Set.of("VIEW")),Set.of(),"1",f.my_info(),f.my_project_zone_info(),
            f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));WmsCentralScope.bind(context);
    }
    @Test void pendingTaskListIncludesCentralTupleBeforeAnyWarehouseHasBeenAssigned(){
        bind(ScopeSql.Mode.SCOPED,List.of(new ScopeTriple("P1","R1","WMS"),new ScopeTriple("P2","R2","WMS")));
        repository.list(10,20,30);
        var sql=ArgumentCaptor.forClass(String.class);verify(jdbc).sql(sql.capture());
        assertTrue(sql.getValue().contains("warehouse_id IS NULL"));
        assertTrue(sql.getValue().contains("project_id = :p0 AND region_id = :r0 AND subsystem_code = :s0"));
        assertTrue(sql.getValue().contains("project_id = :p1 AND region_id = :r1 AND subsystem_code = :s1"));
        assertFalse(sql.getValue().contains("'P1'"));
        verify(jdbc.sql(sql.getValue()).param("c",10L).param("o",20L).param("w",30L))
            .params(Map.of("p0","P1","r0","R1","s0","WMS","p1","P2","r1","R2","s1","WMS"));
    }
    @Test void emptyOrDeniedContextCannotReadOrLockAnyPendingTask(){
        bind(ScopeSql.Mode.DENY,List.of());repository.find("TASK",10,20,true);
        var sql=ArgumentCaptor.forClass(String.class);verify(jdbc).sql(sql.capture());
        assertTrue(sql.getValue().contains(" AND 1=0 "));assertTrue(sql.getValue().endsWith(" FOR UPDATE"));
    }
    @Test void backgroundDeliveryIsBoundToTheSameProjectAndRegionWithoutHoldingTaskLocks(){
        repository.claim("lease","C","O","P","R");
        var sql=ArgumentCaptor.forClass(String.class);verify(jdbc).sql(sql.capture());
        assertTrue(sql.getValue().contains("t.project_id=:project AND t.region_id=:region"));
        assertTrue(sql.getValue().contains("FOR UPDATE OF x"));
    }
}
