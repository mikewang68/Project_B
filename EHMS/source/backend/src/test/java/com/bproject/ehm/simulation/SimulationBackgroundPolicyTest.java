package com.bproject.ehm.simulation;

import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.platform.security.EhmCentralScope;
import com.bproject.ehm.platform.security.BusinessPermissionFilter;
import com.bproject.ehm.platform.security.EhmSsoSessions;
import com.bproject.ehm.platform.security.ProjectContext;
import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import com.bproject.iam.context.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimulationBackgroundPolicyTest {
    @Test void unchangedLegacyDefaultsAndUnassignedRegionAreAccepted() {
        assertDoesNotThrow(()->SimulationBackgroundPolicy.check("LEGACY_ONLY","","","",false));
        assertDoesNotThrow(()->SimulationBackgroundPolicy.check("LEGACY_ONLY","UNASSIGNED","","",false));
    }
    @Test void unknownModesCannotFallBack() {
        for(String mode:new String[]{null,"","iam_v2","unexpected"})
            assertEquals("BACKGROUND_AUTH_MODE_INVALID",assertThrows(IllegalStateException.class,
                    ()->SimulationBackgroundPolicy.check(mode,"","","",false)).getMessage());
    }
    @Test void configuredIdentityDoesNotMakeAnUnadaptedWorkerV2() {
        assertEquals("BACKGROUND_V2_NOT_ADAPTED",assertThrows(IllegalStateException.class,
                ()->SimulationBackgroundPolicy.check("IAM_V2","SHEN_TIE","subject","session",false)).getMessage());
    }
    @Test void legacyRegionSubjectAndSessionClaimsAreRefused() {
        for(String[] values:new String[][]{{"SHEN_TIE","",""},{"","subject",""},{"","","session"}})
            assertEquals("BACKGROUND_LEGACY_SCOPE_CONFLICT",assertThrows(IllegalStateException.class,
                    ()->SimulationBackgroundPolicy.check("LEGACY_ONLY",values[0],values[1],values[2],false)).getMessage());
    }
    private SimulationService service(OpenGaussJsonStore store,TransactionTemplate tx) {
        return new SimulationService(store,tx,null,null,null,new ObjectMapper(),"SIM_GUARD","UNASSIGNED",
                true,"sample","",false,"isolated",false,"openGauss");
    }
    @Test void postConstructRejectsBeforeTransactionOrControllerWrite() {
        var store=mock(OpenGaussJsonStore.class);var tx=mock(TransactionTemplate.class);var service=service(store,tx);
        ReflectionTestUtils.setField(service,"backgroundAuthMode","IAM_V2");
        assertEquals("BACKGROUND_V2_NOT_ADAPTED",assertThrows(IllegalStateException.class,service::initializeController).getMessage());
        verifyNoInteractions(store,tx);
    }
    @Test void scheduledTickCannotInheritARequestGrant() throws Exception {
        var store=mock(OpenGaussJsonStore.class);var tx=mock(TransactionTemplate.class);var service=service(store,tx);
        try(var ignored=EhmCentralScope.bind(mock(UserContext.class))) {
            var failure=assertThrows(ResponseStatusException.class,service::scheduledTick);
            assertEquals(403,failure.getStatusCode().value());assertEquals("BACKGROUND_REQUEST_CONTEXT_REJECTED",failure.getReason());
        }
        assertNull(EhmCentralScope.current());verifyNoInteractions(store,tx);
    }
    @Test void readOnlyStatusKeepsExistingScopedReadSemantics() throws Exception {
        var store=mock(OpenGaussJsonStore.class);var tx=mock(TransactionTemplate.class);var service=service(store,tx);
        when(store.find("simulation-control","control",SimulationService.Control.class)).thenReturn(Optional.of(
                new SimulationService.Control(false,false,"run",0,0,SimulationService.Settings.daily(),null,null)));
        var context=mock(UserContext.class);
        try(var ignored=EhmCentralScope.bind(context)) {
            assertEquals(true,service.status().get("enabled"));assertSame(context,EhmCentralScope.current());
        }
        verifyNoInteractions(tx);
    }
    private EhmSsoSessions.Actor actor(UserContext context) {
        return new EhmSsoSessions.Actor("subject","reader","USER","sid",false,Set.of("SIM_GUARD"),
                Set.of("ehealth:wb:cockpit:view"),false,context);
    }
    @Test void actualHttpFilterPreservesLegacyStatusReadAndExistingV2Denial() throws Exception {
        for(boolean v2:new boolean[]{false,true}) {
            var request=new MockHttpServletRequest("GET","/api/ehm/v1/system/simulation");
            request.setAttribute(IamAuditIdentityFilter.VERIFIED_ACTOR,actor(v2?mock(UserContext.class):null));
            var response=new MockHttpServletResponse();var called=new java.util.concurrent.atomic.AtomicBoolean();
            new BusinessPermissionFilter(new ObjectMapper(),"iam").doFilter(request,response,(a,b)->called.set(true));
            assertEquals(v2?403:200,response.getStatus());assertEquals(!v2,called.get());
        }
    }
    @SuppressWarnings("unchecked")
    @Test void controllerExecutionGuardMapsTo403InsteadOf500() throws Exception {
        var store=mock(OpenGaussJsonStore.class);var tx=mock(TransactionTemplate.class);var service=service(store,tx);
        var provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(service);
        var mvc=MockMvcBuilders.standaloneSetup(new SimulationController(provider)).build();
        try(var p=ProjectContext.bind("SIM_GUARD");var c=EhmCentralScope.bind(mock(UserContext.class))) {
            mvc.perform(post("/api/ehm/v1/system/simulation/tick").contentType("application/json")
                    .content("{\"confirmation\":\"SYNTHETIC_ONLY\",\"requestId\":\"guard-http-0001\"}")
                    .requestAttr(IamAuditIdentityFilter.VERIFIED_ACTOR,actor(null))
                    .requestAttr(IamAuditIdentityFilter.VERIFIED_ADMIN,true)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(store,tx);
    }
}
