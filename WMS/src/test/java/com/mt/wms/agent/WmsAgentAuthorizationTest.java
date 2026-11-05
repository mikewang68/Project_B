package com.mt.wms.agent;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.sso.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.*;
import static com.mt.wms.agent.AgentModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WmsAgentAuthorizationTest {
    private final ObjectMapper mapper=new ObjectMapper();
    UserContext context(Set<String> actions,boolean readonly,boolean inventory){
        var t=new ScopeTriple("PROJECT_B","SHEN_TIE","WMS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
        var operations=new HashMap<String,Set<String>>();operations.put("WMS:warehouse-agent",actions);
        if(inventory)operations.put("WMS:inventory",Set.of("VIEW"));
        return new UserContext(2,"subject","operator","subject","operator","L4_OPERATOR",readonly,"WMS",
            t.projectId(),t.regionId(),t.subsystemCode(),ScopeSql.Mode.SCOPED,List.of(t),List.of(t),Set.of("role"),
            operations.keySet(),operations,Set.of(),"v",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
    }
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @Test void explicitAgentRoutesSeparateReadCreateEditAlarmAndMail(){
        var add=context(Set.of("VIEW","ADD"),false,true);
        assertTrue(WmsCentralScope.route(add,"GET","/api/v1/agent/schedules"));
        assertTrue(WmsCentralScope.route(add,"POST","/api/v1/agent/chat"));
        assertTrue(WmsCentralScope.route(add,"POST","/api/v1/agent/schedules"));
        assertFalse(WmsCentralScope.route(add,"PUT","/api/v1/agent/schedules/1"));
        assertFalse(WmsCentralScope.route(add,"POST","/api/v1/agent/schedules/1/run"));
        var alarm=context(Set.of("VIEW","FAULT_ALARM"),false,true);
        assertTrue(WmsCentralScope.route(alarm,"POST","/api/v1/agent/schedules/1/run"));
        assertFalse(WmsCentralScope.route(alarm,"POST","/api/v1/agent/mails/1/retry"));
        var email=context(Set.of("VIEW","RECEIVE_EMAIL","FAULT_ALARM"),false,true);
        assertTrue(WmsCentralScope.route(email,"POST","/api/v1/agent/mails/1/retry"));
        assertFalse(WmsCentralScope.route(email,"POST","/api/v1/agent/unknown"));
        assertFalse(WmsCentralScope.route(email,"GET","/api/v1/agent/schedules/1/private"));
    }
    @Test void readonlyOrMissingInventoryNeverBecomesPatrolAuthority(){
        var actions=Set.of("VIEW","ADD","EDIT","FAULT_ALARM","RECEIVE_EMAIL");
        var readonly=context(actions,true,true);
        assertTrue(WmsCentralScope.route(readonly,"GET","/api/v1/agent/utilization"));
        assertFalse(WmsCentralScope.route(readonly,"POST","/api/v1/agent/chat"));
        assertFalse(WmsCentralScope.localPermission(readonly,"agent:manage"));
        assertThrows(AccessDeniedException.class,()->WmsAgentAuthorization.require(readonly,"FAULT_ALARM"));
        assertThrows(AccessDeniedException.class,()->WmsAgentAuthorization.require(context(actions,false,false),"FAULT_ALARM"));
    }
    @Test void scheduleReferenceComesOnlyFromValidatedRequest(){
        var request=new MockHttpServletRequest();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        WmsCentralScope.bind(context(Set.of("VIEW","ADD"),false,true));
        assertThrows(AccessDeniedException.class,WmsAgentAuthorization::capture);
        request.setAttribute("WMS_IAM_AUTHORIZATION",mapper.createObjectNode().put("subject","other").put("sessionId","sid"));
        assertThrows(AccessDeniedException.class,WmsAgentAuthorization::capture);
        request.setAttribute("WMS_IAM_AUTHORIZATION",mapper.createObjectNode().put("subject","subject").put("sessionId","sid"));
        assertEquals(new WmsAgentAuthorization.Reference(2,"subject","sid"),WmsAgentAuthorization.capture());
    }
    @Test void legacyCentralJobWithoutReferenceCannotFallBackToNativePermissions(){
        var jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);var iam=mock(WmsSsoExchangeService.class);var job=mock(Schedule.class);
        when(job.id()).thenReturn(7L);when(job.userId()).thenReturn(9L);
        when(jdbc.sql("SELECT scope_context_version,iam_subject,iam_session_id FROM wms_agent_schedule WHERE id=:id").param("id",7L).query().singleRow())
            .thenReturn(Map.of("scope_context_version",1));
        when(jdbc.sql("SELECT iam_subject FROM wms_sso_subject_map WHERE local_user_id=:user AND audience='wms'").param("user",9L).query(String.class).list())
            .thenReturn(List.of("subject"));
        assertThrows(AccessDeniedException.class,()->new WmsAgentAuthorization(jdbc,iam).require(job,false));
        verifyNoInteractions(iam);
    }
    @Test void currentCentralRevocationStopsWorkerBeforeAnalyticsOrNotifications(){
        var repository=mock(AgentRepository.class);var analytics=mock(AgentAnalytics.class);var authorization=mock(WmsAgentAuthorization.class);
        @SuppressWarnings("unchecked") var mail=(org.springframework.beans.factory.ObjectProvider<org.springframework.mail.javamail.JavaMailSender>)mock(org.springframework.beans.factory.ObjectProvider.class);
        var service=new AgentPatrolService(repository,analytics,mock(org.springframework.transaction.support.TransactionTemplate.class),mail,"","",true,false,authorization);
        var job=mock(Schedule.class);when(job.emails()).thenReturn("-");when(repository.claim(job,false)).thenReturn(true);when(repository.permitted(job)).thenReturn(true);
        doThrow(new AccessDeniedException("central scope revoked")).when(authorization).require(job,false);
        assertThrows(IllegalArgumentException.class,()->service.run(job,false));
        verify(repository).finishSchedule(eq(job),any(),eq("central scope revoked"));
        verifyNoInteractions(analytics,mail);
    }
    @Test void workerReintrospectsSameSavedReferenceAndRejectsChangedActions(){
        var jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);var iam=mock(WmsSsoExchangeService.class);var job=mock(Schedule.class);
        when(job.id()).thenReturn(7L);when(job.userId()).thenReturn(9L);when(job.warehouseId()).thenReturn(3L);when(job.companyId()).thenReturn(2L);
        when(jdbc.sql("SELECT scope_context_version,iam_subject,iam_session_id FROM wms_agent_schedule WHERE id=:id").param("id",7L).query().singleRow())
            .thenReturn(Map.of("scope_context_version",2,"iam_subject","subject","iam_session_id","sid"));
        when(jdbc.sql("SELECT iam_subject FROM wms_sso_subject_map WHERE local_user_id=:user AND audience='wms'").param("user",9L).query(String.class).list()).thenReturn(List.of("subject"));
        when(jdbc.sql("SELECT count(*) FROM wms_sso_subject_map WHERE iam_subject=:subject AND audience='wms' AND local_user_id=:user AND enabled=1")
            .param("subject","subject").param("user",9L).query(Integer.class).single()).thenReturn(1);
        when(jdbc.sql(startsWith("SELECT count(*) FROM wms_warehouse o"))
            .param("warehouse",3L).param("company",2L).params(anyMap()).query(Integer.class).single()).thenReturn(1);
        var active=mapper.createObjectNode().put("subject","subject").put("readOnly",false);
        active.set("userContext",mapper.valueToTree(context(Set.of("VIEW","FAULT_ALARM"),false,true)));
        var revoked=mapper.createObjectNode().put("subject","subject").put("readOnly",false);
        revoked.set("userContext",mapper.valueToTree(context(Set.of("VIEW"),false,true)));
        when(iam.currentAuthorization("subject","sid")).thenReturn(active,revoked);
        var service=new WmsAgentAuthorization(jdbc,iam);
        assertDoesNotThrow(()->service.require(job,false));
        assertThrows(AccessDeniedException.class,()->service.require(job,false));
        verify(iam,times(2)).currentAuthorization("subject","sid");
    }
    @Test void centralPollingDoesNotReenableLegacyScheduledJobs(){
        var repository=mock(AgentRepository.class);var analytics=mock(AgentAnalytics.class);var authorization=mock(WmsAgentAuthorization.class);
        @SuppressWarnings("unchecked") var mail=(org.springframework.beans.factory.ObjectProvider<org.springframework.mail.javamail.JavaMailSender>)mock(org.springframework.beans.factory.ObjectProvider.class);
        var service=new AgentPatrolService(repository,analytics,mock(org.springframework.transaction.support.TransactionTemplate.class),mail,"","",false,false,authorization);
        var job=mock(Schedule.class);when(repository.dueCentral()).thenReturn(List.of(job));when(repository.claim(job,false)).thenReturn(false);
        service.poll();
        verify(repository).dueCentral();verify(repository,never()).due();verify(repository).claim(job,false);
        verifyNoInteractions(analytics,authorization,mail);
    }
}
