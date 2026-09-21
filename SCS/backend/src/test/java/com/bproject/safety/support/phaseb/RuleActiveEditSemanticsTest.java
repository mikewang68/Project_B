package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.module.rule.dto.RuleRequests.CreateRuleRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.PublishRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.RollbackRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.UpdateRuleRequest;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.DemoRule.Param;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.module.rule.repository.InMemoryRuleRepository;
import com.bproject.safety.module.rule.repository.RuleRepository;
import com.bproject.safety.module.rule.seed.RuleDemoSeeder;
import com.bproject.safety.module.rule.service.RuleNumberGenerator;
import com.bproject.safety.module.rule.service.RuleService;
import com.bproject.safety.support.demo.DemoRuleNumberGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase B.5 B5-04: Rule ACTIVE 编辑语义与版本保护测试。
 * 验证：
 * 1. ACTIVE 规则普通编辑 (asNewVersion=false)：主状态保持 ACTIVE，effectiveAt 不被抹除，边缘节点版本保持原 active 版本与 synced 状态；
 * 2. 新草稿版本被妥善记录在 versions 中；
 * 3. publish 后，生效版本顺利切换至新版本；
 * 4. rollback 保持复制旧内容生成新版本的语义；
 * 5. DRAFT 规则普通编辑仍保持 DRAFT。
 */
class RuleActiveEditSemanticsTest {

    private RuleRepository repository;
    private RuleService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-04T05:30:00Z"), ZoneOffset.ofHours(8));
        repository = new InMemoryRuleRepository();
        RuleDemoSeeder.buildSeeds().forEach(repository::save);
        RuleNumberGenerator generator = new DemoRuleNumberGenerator(repository);
        com.bproject.safety.common.realtime.DomainLivePublisher publisher =
                new com.bproject.safety.common.realtime.DomainLivePublisher(
                        new com.bproject.safety.common.realtime.WebSocketSessionRegistry(),
                        new com.fasterxml.jackson.databind.ObjectMapper(), clock, new com.bproject.safety.common.realtime.LiveEventGate());
        service = new RuleService(repository, publisher, clock, generator);
    }

    @Test
    @DisplayName("Test 1: ACTIVE v2.4 普通 edit (asNewVersion=false) -> status 仍 ACTIVE，effectiveAt 不清空，Edge active version 仍 v2.4")
    void activeRuleOrdinaryEditKeepsActive() {
        DemoRule before = service.get("RULE-DEV-003");
        assertThat(before.statusCode).isEqualTo(RuleStatuses.ACTIVE);
        assertThat(before.version).isEqualTo("v2.4");
        String originalEffectiveAt = before.effectiveAt;
        assertThat(originalEffectiveAt).isNotEqualTo("—");
        assertThat(before.edgeNodes).allMatch(n -> "v2.4".equals(n.version()) && RuleStatuses.EDGE_SYNCED.equals(n.state()));

        // 普通编辑：修改名称与参数，asNewVersion=false, submitReview=false
        UpdateRuleRequest req = new UpdateRuleRequest(
                "转运车辆距离预警-修改版", "设备安全", List.of("装卸区 A"), "严重",
                List.of(Param.of("预警距离", "11m")), List.of("通知司机"), false,
                false, false, "修改规则说明", List.of("设备防碰撞")
        );

        DemoRule after = service.update("RULE-DEV-003", req);

        // 核心断言：不能因为普通编辑而让已生效规则退回草稿/待评审
        assertThat(after.statusCode).as("已生效规则普通编辑后主状态必须保持 ACTIVE").isEqualTo(RuleStatuses.ACTIVE);
        assertThat(after.getStatus()).isEqualTo("已生效");
        assertThat(after.effectiveAt).as("effectiveAt 不得被清空为 —").isEqualTo(originalEffectiveAt);
        assertThat(after.edgeNodes).as("边缘节点必须继续执行当前 active 版本 v2.4 且保持 synced")
                .allMatch(n -> "v2.4".equals(n.version()) && RuleStatuses.EDGE_SYNCED.equals(n.state()));
    }

    @Test
    @DisplayName("Test 2: ACTIVE 规则编辑后，新版本草稿妥善记录在 versions[0] 中")
    void newDraftVersionRecordedInVersions() {
        UpdateRuleRequest req = new UpdateRuleRequest(
                "转运车辆距离预警-新草稿", "设备安全", List.of("装卸区 A"), "严重",
                List.of(Param.of("预警距离", "12m")), List.of("通知司机"), false,
                false, false, "修订说明", List.of("设备防碰撞")
        );

        DemoRule after = service.update("RULE-DEV-003", req);

        assertThat(after.versions).isNotEmpty();
        assertThat(after.versions.get(0).version()).isEqualTo("v2.5");
        assertThat(after.versions.get(0).note()).contains("新版本草稿");
    }

    @Test
    @DisplayName("Test 3: publish 新版本后，currentVersion 切换至新版本，Edge 同步至新版本")
    void publishSwitchesActiveVersion() {
        // 先创建新草稿
        UpdateRuleRequest req = new UpdateRuleRequest(
                "转运车辆距离预警-发布版", "设备安全", List.of("装卸区 A"), "严重",
                List.of(Param.of("预警距离", "12m")), List.of("通知司机"), false,
                false, false, "发布测试", List.of("设备防碰撞")
        );
        service.update("RULE-DEV-003", req);

        // 发布新版本
        DemoRule published = service.publish("RULE-DEV-003", new PublishRequest("安全员 李娜", null));
        assertThat(published.statusCode).isEqualTo(RuleStatuses.ACTIVE);
        assertThat(published.version).isEqualTo("v2.5");
        assertThat(published.edgeNodes).allMatch(n -> "v2.5".equals(n.version()) && RuleStatuses.EDGE_SYNCED.equals(n.state()));
    }

    @Test
    @DisplayName("Test 4: rollback 保持原设计语义：基于历史版本生成新版本，待评审")
    void rollbackMaintainsDesignSemantics() {
        Map<String, Object> res = service.rollback("RULE-PER-001", new RollbackRequest("v3.2", "安全员 李娜"));
        assertThat(res.get("newVersion")).isEqualTo("v3.4");
        assertThat(res.get("sourceVersion")).isEqualTo("v3.2");
        assertThat(res.get("statusCode")).isEqualTo(RuleStatuses.REVIEW);
    }

    @Test
    @DisplayName("Test 5: DRAFT 规则普通编辑仍保持 DRAFT")
    void draftRuleOrdinaryEditKeepsDraft() {
        // 创建一条草稿规则
        DemoRule draft = service.create(new CreateRuleRequest(
                "草稿规则测试", "人员安全", "违规检测", "测试", List.of("全部区域"),
                "一般", "安全员 李娜", "描述", List.of("告警中心"),
                List.of(Param.of("阈值", "5")), List.of("通知"), false, false
        ));
        assertThat(draft.statusCode).isEqualTo(RuleStatuses.DRAFT);

        // 普通编辑该草稿
        DemoRule updated = service.update(draft.id, new UpdateRuleRequest(
                "草稿规则测试-修改", "人员安全", List.of("全部区域"), "一般",
                List.of(Param.of("阈值", "6")), List.of("通知"), false,
                false, false, "修改描述", List.of("告警中心")
        ));
        assertThat(updated.statusCode).isEqualTo(RuleStatuses.DRAFT);
        assertThat(updated.getStatus()).isEqualTo("草稿");
    }
}
