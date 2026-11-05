package com.bdemo.scheduling;

import com.bdemo.config.EmcsCentralScope;
import com.bdemo.energy.agent.AgentService;
import com.bdemo.energy.pipeline.PipelineService;
import com.bproject.iam.context.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnergyBackgroundPolicyTest {
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(EnergyScheduler.class)
                .withInitializer(c->c.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withBean(AgentService.class,()->mock(AgentService.class))
                .withBean(PipelineService.class,()->mock(PipelineService.class))
                .withPropertyValues("b-demo.demo-now=2026-07-12T23:59:00");
    }
    @Test void disabledSchedulerRemainsAbsentEvenWithFutureModeRequested() {
        context().withPropertyValues("b-demo.scheduling-enabled=false","b-demo.background.auth-mode=IAM_V2")
                .run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(EnergyScheduler.class).isEmpty());});
    }
    @Test void existingEnabledLegacyConfigurationStillInitializesWithoutRunningAnyJob() {
        context().withPropertyValues("b-demo.scheduling-enabled=true").run(c->{
            assertNull(c.getStartupFailure());assertNotNull(c.getBean(EnergyScheduler.class));
            verifyNoInteractions(c.getBean(AgentService.class),c.getBean(PipelineService.class));
        });
    }
    @Test void enabledV2ConfigurationStopsStartupBeforeBusinessCalls() {
        context().withPropertyValues("b-demo.scheduling-enabled=true","b-demo.background.auth-mode=IAM_V2")
                .run(c->{Throwable failure=c.getStartupFailure();assertNotNull(failure);
                    while(failure.getCause()!=null)failure=failure.getCause();
                    assertEquals("BACKGROUND_V2_NOT_ADAPTED",failure.getMessage());});
    }
    @Test void invalidModeAndClaimedScopeAreRejected() {
        assertEquals("BACKGROUND_AUTH_MODE_INVALID",assertThrows(IllegalStateException.class,
                ()->EnergyBackgroundPolicy.check("typo","","","","",false)).getMessage());
        for(int n=0;n<4;n++) {
            String[] values={"","","",""};values[n]="claimed";
            assertEquals("BACKGROUND_LEGACY_SCOPE_CONFLICT",assertThrows(IllegalStateException.class,
                    ()->EnergyBackgroundPolicy.check("LEGACY_ONLY",values[0],values[1],values[2],values[3],false)).getMessage());
        }
    }
    @Test void allScheduledEntrypointsRecheckBeforeAnyBusinessDependency() {
        var agent=mock(AgentService.class);var pipeline=mock(PipelineService.class);var scheduler=new EnergyScheduler(agent,pipeline);
        ReflectionTestUtils.setField(scheduler,"backgroundAuthMode","IAM_V2");
        for(Runnable call:new Runnable[]{scheduler::aggregateAndRules,scheduler::dailyCost,scheduler::inspection})
            assertEquals("BACKGROUND_V2_NOT_ADAPTED",assertThrows(IllegalStateException.class,call::run).getMessage());
        verifyNoInteractions(agent,pipeline);
    }
    @Test void userRequestContextIsNeverAServiceGrant() throws Exception {
        var agent=mock(AgentService.class);var pipeline=mock(PipelineService.class);var scheduler=new EnergyScheduler(agent,pipeline);
        try(var ignored=EmcsCentralScope.bind(mock(UserContext.class))) {
            assertEquals("BACKGROUND_REQUEST_CONTEXT_REJECTED",assertThrows(IllegalStateException.class,scheduler::inspection).getMessage());
        }
        assertNull(EmcsCentralScope.current());verifyNoInteractions(agent,pipeline);
    }
}
