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
import com.bproject.safety.module.fence.model.DemoFence;
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
 * Phase B.5 B5-05: 业务编号生成器碰撞与静默覆盖安全测试。
 * 验证：
 * 1. 编号存在间隙（如 001, 003, 005）时，生成器必须基于当前最大序号 +1 (006)，不得使用 count()+1 (004) 造成主键碰撞；
 * 2. 连续新建不会覆盖已有数据；
 * 3. 容错性：遇到非数字非法编号时不崩溃。
 */
class BusinessNumberCollisionSafetyTest {

    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-04T05:30:00Z"), ZoneOffset.ofHours(8));
    }

    @Test
    @DisplayName("Alert 编号间隙：001, 003, 007 -> next 必须为 008，而非 count()+1 (004)")
    void alertNumberGapScenario() {
        AlertRepository repository = new InMemoryAlertRepository(clock);
        AlertNumberGenerator generator = new DemoAlertNumberGenerator(repository, clock);

        saveAlert(repository, "ALM-20260904-001");
        saveAlert(repository, "ALM-20260904-003");
        saveAlert(repository, "ALM-20260904-007");

        assertThat(generator.nextAlertNumber())
                .as("现有最大编号为 007，下一个编号必须为 008")
                .isEqualTo("ALM-20260904-008");
    }

    @Test
    @DisplayName("AI Event 编号间隙：001, 005 -> next 必须为 006，而非 count()+1 (003)")
    void aiEventNumberGapScenario() {
        AiEventRepository repository = new InMemoryAiEventRepository(clock);
        AiEventNumberGenerator generator = new DemoAiEventNumberGenerator(repository, clock);

        saveAiEvent(repository, "AI-E-20260904-001");
        saveAiEvent(repository, "AI-E-20260904-005");

        assertThat(generator.nextAiEventNumber())
                .as("现有最大编号为 005，下一个编号必须为 006")
                .isEqualTo("AI-E-20260904-006");
    }

    @Test
    @DisplayName("Rule 编号按类别独立维护最大序列：PER 001, 005 -> PER-006；DEV 为空 -> DEV-001")
    void ruleNumberGapScenarioPerCategory() {
        RuleRepository repository = new InMemoryRuleRepository();
        RuleNumberGenerator generator = new DemoRuleNumberGenerator(repository);

        saveRule(repository, "RULE-PER-001", "人员安全");
        saveRule(repository, "RULE-PER-005", "人员安全");

        assertThat(generator.nextRuleNumber("人员安全"))
                .as("RULE-PER 最大为 005，下一个必须为 006")
                .isEqualTo("RULE-PER-006");

        assertThat(generator.nextRuleNumber("设备安全"))
                .as("RULE-DEV 无记录，下一个必须为 001")
                .isEqualTo("RULE-DEV-001");
    }

    @Test
    @DisplayName("Fence 编号间隙与异常容错：001, 003, 005, INVALID -> next 必须为 006 且不崩溃")
    void fenceNumberGapAndMalformedScenario() {
        FenceRepository repository = new InMemoryFenceRepository();
        FenceNumberGenerator generator = new DemoFenceNumberGenerator(repository);

        saveFence(repository, "FENCE-001", "围栏1");
        saveFence(repository, "FENCE-003", "围栏3");
        saveFence(repository, "FENCE-005", "围栏5重要数据");
        saveFence(repository, "FENCE-BAD_FORMAT", "异常数据");

        assertThat(generator.nextFenceNumber())
                .as("现有最大有效数字为 5，下一个必须为 FENCE-006")
                .isEqualTo("FENCE-006");

        // 验证连续生成
        saveFence(repository, "FENCE-006", "围栏6");
        assertThat(generator.nextFenceNumber()).isEqualTo("FENCE-007");

        // 验证原有 FENCE-005 绝不被覆盖
        DemoFence original005 = repository.findById("FENCE-005").orElseThrow();
        assertThat(original005.name).isEqualTo("围栏5重要数据");
    }

    private void saveAlert(AlertRepository repo, String id) {
        DemoAlert a = new DemoAlert();
        a.id = id;
        repo.save(a);
    }

    private void saveAiEvent(AiEventRepository repo, String id) {
        DemoAiEvent e = new DemoAiEvent();
        e.id = id;
        repo.save(e);
    }

    private void saveRule(RuleRepository repo, String id, String category) {
        DemoRule r = new DemoRule();
        r.id = id;
        r.category = category;
        repo.save(r);
    }

    private void saveFence(FenceRepository repo, String id, String name) {
        DemoFence f = new DemoFence();
        f.id = id;
        f.name = name;
        repo.save(f);
    }
}
