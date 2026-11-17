package com.bproject.safety.module.alert.service;

import com.bproject.iam.context.ScopeTriple;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** L1 scoped 会话的 AI 确认告警：创建后必须补盖事件 tuple，门户 scoped 查询才能看到。 */
class AlertScopeStampTest {

    private static final ScopeTriple TRIPLE = new ScopeTriple("PROJECT_B", "SHEN_TIE", "SCS");

    @Test
    void aiAlertGetsTheEventScopeBeforeReadBack() {
        var repository = mock(AlertRepository.class);
        var persisted = new DemoAlert();
        persisted.id = "ALM-TEST";
        persisted.statusCode = "PENDING_CONFIRM";
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findById("ALM-TEST")).thenReturn(Optional.of(persisted));
        var numberGenerator = mock(AlertNumberGenerator.class);
        when(numberGenerator.nextAlertNumber()).thenReturn("ALM-TEST");
        var service = new AlertService(repository, null, null, null, Clock.systemUTC(),
                AlertChangeNotifier.NOOP, numberGenerator);

        DemoAiEvent event = new DemoAiEvent();
        event.id = "AI-E-TEST";
        event.type = "未佩戴安全帽";
        event.confidence = 90;
        event.projectId = "PROJECT_B";
        event.regionId = "SHEN_TIE";
        event.subsystemCode = "SCS";
        var alert = service.createFromAi(new AlertService.NewAlertFromAi(
                event.id, "AI 测试", "未佩戴安全帽", "SEVERE", "装卸区 A", "测试人员",
                1, "RULE-01", "v1.0", "model", "v1", null), event.scope());

        assertEquals("ALM-TEST", alert.id);
        verify(repository).stampScope(eq("ALM-TEST"), eq(TRIPLE));
    }

    @Test
    void aiEventScopeIsNullWhenAnyTupleFieldIsMissing() {
        DemoAiEvent event = new DemoAiEvent();
        event.projectId = "PROJECT_B";
        event.regionId = "SHEN_TIE";
        event.subsystemCode = "";
        org.junit.jupiter.api.Assertions.assertNull(event.scope());
        event.subsystemCode = "SCS";
        assertEquals(TRIPLE, event.scope());
    }
}
