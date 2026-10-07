package com.mt.wms.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import jakarta.servlet.http.HttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentIsolationTest {
    @Test void unverifiedModelAnswerIsNotPresentedAsBusinessFact() {
        AgentRepository repository=mock(AgentRepository.class);
        AgentModelClient model=mock(AgentModelClient.class);
        ObjectMapper mapper=new ObjectMapper();
        WarehouseAgentService service=spy(new WarehouseAgentService(repository,mock(AgentAnalytics.class),model,mapper,mock(TenantContextService.class)));
        WmsPrincipal principal=new WmsPrincipal(1L,2L,"company","Company","u","User",List.of(),Set.of("inventory:read"),false);
        HttpSession session=mock(HttpSession.class);
        doReturn(new AgentModels.Scope(2,3,4,1,false)).when(service).scope(principal,session);
        when(model.completion(anyList(),anyList())).thenReturn(mapper.createObjectNode().put("content","库存有999999捆"));
        var result=service.chat(new AgentModels.ChatRequest("查钢材",List.of()),principal,session);
        assertEquals("DEGRADED",result.state());
        assertFalse(result.answer().contains("999999"));
    }
    @Test void unavailableModelFallsBackToRealAnalyticsWithoutBreakingWms() {
        AgentRepository repository=mock(AgentRepository.class);
        AgentAnalytics analytics=mock(AgentAnalytics.class);
        AgentModelClient model=mock(AgentModelClient.class);
        WarehouseAgentService service=spy(new WarehouseAgentService(repository,analytics,model,new ObjectMapper(),mock(TenantContextService.class)));
        WmsPrincipal principal=new WmsPrincipal(1L,2L,"company","Company","u","User",List.of(),Set.of("inventory:read"),false);
        AgentModels.Scope scope=new AgentModels.Scope(2,3,4,1,false);
        HttpSession session=mock(HttpSession.class);
        doReturn(scope).when(service).scope(principal,session);
        when(repository.createTask(scope,"分析库位")).thenReturn(10L);
        when(model.completion(anyList(),anyList())).thenThrow(new IllegalStateException("模型暂时不可用，请稍后重试"));
        when(analytics.utilization(scope,false)).thenReturn(Map.of("total_locations",3,"occupied_locations",2));
        var result=service.chat(new AgentModels.ChatRequest("分析库位",List.of()),principal,session);
        assertEquals("DEGRADED",result.state());
        assertEquals("analyze_locations",result.events().get(0).tool());
        assertEquals(2,((Map<?,?>)result.events().get(0).result()).get("occupied_locations"));
        verify(repository).finishTask(eq(10L),anyString(),anyString(),eq("DEGRADED"));
    }
    @Test void receiptToolCannotBypassBusinessPermission() {
        AgentRepository repository=mock(AgentRepository.class);
        WarehouseAgentService service=new WarehouseAgentService(repository,mock(AgentAnalytics.class),mock(AgentModelClient.class),new ObjectMapper(),mock(TenantContextService.class));
        WmsPrincipal principal=new WmsPrincipal(1L,2L,"company","Company","u","User",List.of(),Set.of("inventory:read"),false);
        AgentModels.Scope scope=new AgentModels.Scope(2,3,4,1,false);
        assertThrows(AccessDeniedException.class,()->service.execute("search_receipts",Map.of("companyId",999),principal,scope));
        verifyNoInteractions(repository);
    }
    @Test void inventoryToolUsesServerScopeRatherThanModelArguments() {
        AgentRepository repository=mock(AgentRepository.class);
        WarehouseAgentService service=new WarehouseAgentService(repository,mock(AgentAnalytics.class),mock(AgentModelClient.class),new ObjectMapper(),mock(TenantContextService.class));
        WmsPrincipal principal=new WmsPrincipal(1L,2L,"company","Company","u","User",List.of(),Set.of("inventory:read"),false);
        AgentModels.Scope scope=new AgentModels.Scope(2,3,4,1,false);
        Map<String,Object> forged=Map.of("companyId",999,"warehouseId",999,"ownerId",999);
        service.execute("search_inventory",forged,principal,scope);
        verify(repository).search(scope,forged,false);
        assertThrows(IllegalArgumentException.class,()->service.execute("execute_sql",forged,principal,scope));
    }
}
