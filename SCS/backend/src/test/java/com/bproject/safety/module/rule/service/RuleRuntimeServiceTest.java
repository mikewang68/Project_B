package com.bproject.safety.module.rule.service;

import com.bproject.safety.config.ScsMachineRegistry;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.alert.model.DecisionSources;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.module.rule.repository.RuleRepository;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 3 类演示规则的最小运行时求值回归；不依赖数据库/请求上下文。 */
class RuleRuntimeServiceTest {

    private final RuleRepository ruleRepository = mock(RuleRepository.class);
    private final FenceRepository fenceRepository = mock(FenceRepository.class);
    private final AlertService alertService = mock(AlertService.class);
    private final RuleRuntimeService runtime = new RuleRuntimeService(
            ruleRepository, fenceRepository, alertService, Clock.systemUTC());

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void collisionRuleUsesConfiguredDistanceThreshold() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("SIM-DEMO-SCS-20261113-RULE-02", "演示-设备防撞距离", "EFFECTIVE",
                        "告警距离(m)", "5.4")));
        when(fenceRepository.findAll()).thenReturn(List.of());
        runtime.refresh();

        assertTrue(runtime.matchCollision(5.0).isPresent());
        assertFalse(runtime.matchCollision(6.0).isPresent());
        assertTrue(runtime.matchCollision(5.0).orElseThrow().version().equals("v1.0"));
    }

    @Test
    void aiRuleRequiresPpeTypeAndConfidenceThreshold() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("SIM-DEMO-SCS-20261113-RULE-01", "演示-人员未戴安全帽", "ACTIVE",
                        "置信度阈值(%)", "80")));
        when(fenceRepository.findAll()).thenReturn(List.of());
        runtime.refresh();

        DemoAiEvent helmet = event("未佩戴安全帽", 92.0);
        DemoAiEvent low = event("未佩戴安全帽", 70.0);
        DemoAiEvent intrusion = event("闯入危险区域", 99.0);
        assertTrue(runtime.matchAi(helmet).isPresent());
        assertFalse(runtime.matchAi(low).isPresent());
        assertFalse(runtime.matchAi(intrusion).isPresent());
    }

    @Test
    void intrusionRuleMatchesConfiguredArea() {
        DemoRule rule = rule("SIM-DEMO-SCS-20261113-RULE-03", "演示-人员闯入危险区", "EFFECTIVE",
                "触发区域", "装卸区 A");
        rule.areas = List.of("装卸区 A");
        when(ruleRepository.findAll()).thenReturn(List.of(rule));
        when(fenceRepository.findAll()).thenReturn(List.of());
        runtime.refresh();

        assertTrue(runtime.matchIntrusion(person("装卸区 A"), 20, 22, "装卸区 A").isPresent());
        assertFalse(runtime.matchIntrusion(person("装卸区 B"), 20, 22, "装卸区 B").isPresent());
    }

    @Test
    void intrusionEvaluationCreatesRuleSourcedAlertForOwnedPerson() {
        DemoRule rule = rule("SIM-DEMO-SCS-20261113-RULE-03", "演示-人员闯入危险区", "EFFECTIVE",
                "触发区域", "装卸区 A");
        rule.areas = List.of("装卸区 A");
        when(ruleRepository.findAll()).thenReturn(List.of(rule));
        when(fenceRepository.findAll()).thenReturn(List.of());
        runtime.refresh();

        var binding = mock(ScsMachineRegistry.Binding.class);
        when(binding.owns(com.bproject.safety.config.ScsCentralScope.Root.PERSONNEL, "P-1001"))
                .thenReturn(true);
        var request = new MockHttpServletRequest("POST", "/api/v1/ingest/person-position");
        ScsMachineRegistry.bind(request, binding);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        runtime.evaluateIntrusion(person("装卸区 A"), 20, 22, "装卸区 A");

        ArgumentCaptor<AlertService.NewRiskAlert> captor =
                ArgumentCaptor.forClass(AlertService.NewRiskAlert.class);
        verify(alertService).createRiskAlert(captor.capture());
        AlertService.NewRiskAlert draft = captor.getValue();
        assertEquals("PERSON_INTRUSION:P-1001:" + rule.id, draft.dedupKey());
        assertEquals(rule.id, draft.ruleId());
        assertEquals("v1.0", draft.ruleVersion());
        assertEquals(DecisionSources.RULE, draft.decisionSourceType());
    }

    @Test
    void disabledRulesAreNotEvaluated() {
        when(ruleRepository.findAll()).thenReturn(List.of(
                rule("SIM-DEMO-SCS-20261113-RULE-02", "演示-设备防撞距离", RuleStatuses.DISABLED,
                        "告警距离(m)", "5.4")));
        when(fenceRepository.findAll()).thenReturn(List.of());
        runtime.refresh();

        assertFalse(runtime.matchCollision(1.0).isPresent());
    }

    private DemoRule rule(String id, String name, String status, String label, String value) {
        DemoRule rule = new DemoRule();
        rule.id = id;
        rule.name = name;
        rule.version = "v1.0";
        rule.statusCode = status;
        rule.riskCode = "SEVERE";
        rule.params = List.of(DemoRule.Param.of(label, value));
        return rule;
    }

    private DemoAiEvent event(String type, double confidence) {
        DemoAiEvent event = new DemoAiEvent();
        event.type = type;
        event.confidence = confidence;
        return event;
    }

    private DemoPersonnel person(String area) {
        DemoPersonnel person = new DemoPersonnel();
        person.id = "P-1001";
        person.name = "测试人员";
        person.jobNo = "T-001";
        person.braceletId = "WB-001";
        person.area = area;
        return person;
    }
}
