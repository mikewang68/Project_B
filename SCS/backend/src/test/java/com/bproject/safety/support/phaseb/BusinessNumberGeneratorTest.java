package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.repository.InMemoryAiEventRepository;
import com.bproject.safety.module.ai.service.AiEventNumberGenerator;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.service.AlertNumberGenerator;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.fence.repository.InMemoryFenceRepository;
import com.bproject.safety.module.fence.service.FenceNumberGenerator;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.repository.InMemoryRuleRepository;
import com.bproject.safety.module.rule.repository.RuleRepository;
import com.bproject.safety.module.rule.service.RuleNumberGenerator;
import com.bproject.safety.support.demo.DemoAiEventNumberGenerator;
import com.bproject.safety.support.demo.DemoAlertNumberGenerator;
import com.bproject.safety.support.demo.DemoFenceNumberGenerator;
import com.bproject.safety.support.demo.DemoRuleNumberGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase B：业务编号 Generator 从 Service 抽离后的格式与递增测试。
 * 编号格式必须与历史 Demo 完全一致；多实例唯一性明确留待 openGauss 序列 / 号段解决。
 */
class BusinessNumberGeneratorTest {

    private Clock clock;

    @BeforeEach
    void setUp() {
        // 2026-09-04 13:30 上海时间
        clock = Clock.fixed(Instant.parse("2026-09-04T05:30:00Z"), ZoneOffset.ofHours(8));
    }

    @Test
    @DisplayName("Alert：ALM-yyyyMMdd-NNN，同日序号随已有数据递增")
    void alertNumberFormatAndIncrement() {
        AlertRepository repository = new InMemoryAlertRepository(clock);
        AlertNumberGenerator generator = new DemoAlertNumberGenerator(repository, clock);
        assertThat(generator.nextAlertNumber()).isEqualTo("ALM-20260904-001");

        DemoAlert a = new DemoAlert();
        a.id = "ALM-20260904-001";
        repository.save(a);
        assertThat(generator.nextAlertNumber()).isEqualTo("ALM-20260904-002");
    }

    @Test
    @DisplayName("AI Event：AI-E-yyyyMMdd-NNN（上海时区自然日），序号递增")
    void aiEventNumberFormatAndIncrement() {
        AiEventRepository repository = new InMemoryAiEventRepository(clock);
        AiEventNumberGenerator generator = new DemoAiEventNumberGenerator(repository, clock);
        assertThat(generator.nextAiEventNumber()).isEqualTo("AI-E-20260904-001");

        DemoAiEvent e = new DemoAiEvent();
        e.id = "AI-E-20260904-001";
        repository.save(e);
        assertThat(generator.nextAiEventNumber()).isEqualTo("AI-E-20260904-002");
    }

    @Test
    @DisplayName("Rule：RULE-<域前缀>-NNN，类别前缀映射保持历史口径")
    void ruleNumberPrefixes() {
        RuleRepository repository = new InMemoryRuleRepository();
        RuleNumberGenerator generator = new DemoRuleNumberGenerator(repository);
        assertThat(generator.nextRuleNumber("人员安全")).isEqualTo("RULE-PER-001");
        assertThat(generator.nextRuleNumber("设备安全")).isEqualTo("RULE-DEV-001");
        assertThat(generator.nextRuleNumber("AI识别")).isEqualTo("RULE-AI-001");
        assertThat(generator.nextRuleNumber("告警策略")).isEqualTo("RULE-ALM-001");
        assertThat(generator.nextRuleNumber("联动策略")).isEqualTo("RULE-LNK-001");
        assertThat(generator.nextRuleNumber("通知策略")).isEqualTo("RULE-NTF-001");

        DemoRule r = new DemoRule();
        r.id = "RULE-PER-001";
        repository.save(r);
        assertThat(generator.nextRuleNumber("人员安全")).isEqualTo("RULE-PER-002");
    }

    @Test
    @DisplayName("Fence：FENCE-NNN 随现有围栏数顺延")
    void fenceNumberIncrement() {
        FenceRepository repository = new InMemoryFenceRepository();
        FenceNumberGenerator generator = new DemoFenceNumberGenerator(repository);
        assertThat(generator.nextFenceNumber()).isEqualTo("FENCE-001");

        com.bproject.safety.module.fence.model.DemoFence f =
                new com.bproject.safety.module.fence.model.DemoFence();
        f.id = "FENCE-001";
        repository.save(f);
        assertThat(generator.nextFenceNumber()).isEqualTo("FENCE-002");
    }
}
