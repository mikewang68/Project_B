package com.bproject.ehm.assistant.application;

import com.bproject.ehm.asset.application.*;
import com.bproject.ehm.asset.domain.model.ConfigurationChange;
import com.bproject.ehm.alarm.application.AlarmQueryFacade;
import com.bproject.ehm.maintenance.application.WorkOrderQueryFacade;
import com.bproject.ehm.reliability.application.ReliabilityGovernanceApplicationService;
import com.bproject.ehm.workbench.application.WorkbenchApplicationService;
import com.bproject.ehm.shared.page.PageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssistantDataToolsTest {
    @Test void rejectsUnlistedToolAndOmitsConfigSnapshotsCredentialsAndPeople() {
        var a=mock(AssetQueryFacade.class);var alarms=mock(AlarmQueryFacade.class);var orders=mock(WorkOrderQueryFacade.class);
        var wb=mock(WorkbenchApplicationService.class);var gov=mock(AssetGovernanceApplicationService.class);var kb=mock(ReliabilityGovernanceApplicationService.class);
        var tools=new AssistantDataTools(a,alarms,orders,wb,gov,kb,new ObjectMapper().findAndRegisterModules());
        assertNull(tools.query("execute_sql","{}",new AssistantPrivacy(),1).source());verifyNoInteractions(a,alarms,orders,wb,gov,kb);
        var change=ConfigurationChange.create("CFG-1","GT-01","GATEWAY","GW-01","配置修改",Map.of("password","secret-before"),Map.of("token","secret-after"),"调整采样","正常","某员工","/data/backup",Instant.now());
        when(gov.changes(null,null)).thenReturn(List.of(change));
        var result=tools.query("query_ehm","{\"category\":\"changes\",\"device_code\":\"GT-01\"}",new AssistantPrivacy(),1);
        assertNotNull(result.source());for(String forbidden:List.of("secret-before","secret-after","某员工","/data/backup"))assertFalse(result.externalJson().contains(forbidden));
        verify(gov,never()).approveChange(anyString(),anyString());
    }
    @Test void emptyAndFailedQueriesHaveDifferentMeanings() {
        var a=mock(AssetQueryFacade.class);var tools=new AssistantDataTools(a,mock(AlarmQueryFacade.class),mock(WorkOrderQueryFacade.class),mock(WorkbenchApplicationService.class),mock(AssetGovernanceApplicationService.class),mock(ReliabilityGovernanceApplicationService.class),new ObjectMapper());
        when(a.list(any(),isNull(),isNull(),isNull())).thenReturn(new PageResult<>(List.of(),0,200,0,0));
        var empty=tools.query("query_ehm","{\"category\":\"devices\"}",new AssistantPrivacy(),1);assertEquals(0,empty.source().matchedRows());
        when(a.list(any(),isNull(),isNull(),isNull())).thenThrow(new RuntimeException("db password=secret"));
        var error=tools.query("query_ehm","{\"category\":\"devices\"}",new AssistantPrivacy(),1);assertNull(error.source());assertTrue(error.localData().containsKey("error"));assertFalse(error.externalJson().contains("secret"));
    }
}
