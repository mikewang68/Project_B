package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.module.alert.dto.AlertRequests.AssignRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ConfirmRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.EscalateRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ReviewRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.StartRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TreatmentRequest;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.AlertTimelineEventTypes;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.alert.service.AlertChangeNotifier;
import com.bproject.safety.module.alert.service.AlertNumberGenerator;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.support.demo.DemoAlertNumberGenerator;
import com.bproject.safety.support.demo.DemoUserProperties;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase B.5 B5-01: Alert Escalation 语义与生命周期测试。
 * 验证升级是风险升高事件而非生命周期状态，升级后主状态保持原值且后续流转畅通无死锁。
 */
class AlertEscalationWorkflowTest {

    private InMemoryAlertRepository repository;
    private AlertService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-04T05:30:00Z"), ZoneOffset.ofHours(8));
        repository = new InMemoryAlertRepository();
        AlertDemoSeeder.buildSeeds(clock).forEach(repository::save);
        IdempotencyService idempotency = new IdempotencyService(null, 600);
        DemoUserProperties user = new DemoUserProperties("USR-001", "李娜", "安全员", "安全管理组", "夜班", true);
        DemoMasterData masterData = new DemoMasterData();
        AlertNumberGenerator generator = new DemoAlertNumberGenerator(repository, clock);
        service = new AlertService(repository, idempotency, user, masterData, clock, AlertChangeNotifier.NOOP, generator);
    }

    @Test
    @DisplayName("Test 1: PROCESSING + SEVERE escalate -> PROCESSING + URGENT，主状态不被篡改为 ESCALATED")
    void escalateDoesNotChangeMainStatus() {
        // ALM-20260904-002: 初始为 PENDING_CONFIRM，将其一路推进至 PROCESSING (SEVERE)
        service.confirm("ALM-20260904-002", new ConfirmRequest(null), null);
        service.assign("ALM-20260904-002", new AssignRequest("王建国", "USR-002", null, "普通", 15, null, null, null), null);
        service.start("ALM-20260904-002", new StartRequest(null, null), null);

        DemoAlert before = service.get("ALM-20260904-002");
        assertThat(before.statusCode).isEqualTo(AlertStatuses.PROCESSING);
        assertThat(before.riskCode).isEqualTo(RiskLevels.SEVERE);

        // 执行升级为 URGENT (紧急)
        DemoAlert after = service.escalate("ALM-20260904-002", new EscalateRequest("现场风险升级", "紧急", null, null), null);

        // 主状态必须保持 PROCESSING，而不是 ESCALATED
        assertThat(after.statusCode).isEqualTo(AlertStatuses.PROCESSING);
        assertThat(after.getStatus()).isEqualTo("处理中");
        assertThat(after.riskCode).isEqualTo(RiskLevels.URGENT);
        assertThat(after.getRisk()).isEqualTo("紧急");
        assertThat(after.upgradedFromCode).isEqualTo(RiskLevels.SEVERE);
        assertThat(after.previousRiskLevelCode).isEqualTo(RiskLevels.SEVERE);
    }

    @Test
    @DisplayName("Test 2 & 3: 升级后仍能正常 treatment -> PENDING_REVIEW，以及 review -> CLOSED")
    void escalateAllowsTreatmentAndReview() {
        service.confirm("ALM-20260904-002", new ConfirmRequest(null), null);
        service.assign("ALM-20260904-002", new AssignRequest("王建国", "USR-002", null, "普通", 15, null, null, null), null);
        service.start("ALM-20260904-002", new StartRequest(null, null), null);

        // 升级
        service.escalate("ALM-20260904-002", new EscalateRequest("现场恶化", "紧急", null, null), null);

        // 提交处置
        DemoAlert treated = service.treatment("ALM-20260904-002",
                new TreatmentRequest(List.of("紧急停机", "疏散人员"), "现场处置完毕", null, "无遗留", null, null), null);
        assertThat(treated.statusCode).isEqualTo(AlertStatuses.PENDING_REVIEW);
        assertThat(treated.getStatus()).isEqualTo("待复核");

        // 安全复核关闭
        DemoAlert reviewed = service.review("ALM-20260904-002", new ReviewRequest("李娜", null, "现场确认安全", null), null);
        assertThat(reviewed.statusCode).isEqualTo(AlertStatuses.CLOSED);
        assertThat(reviewed.getStatus()).isEqualTo("已关闭");
    }

    @Test
    @DisplayName("Test 4 & 5: 时间线记录 ESCALATED 事件，previousRiskLevelCode 正确")
    void timelineAndPreviousRiskLevel() {
        service.confirm("ALM-20260904-002", new ConfirmRequest(null), null);
        service.assign("ALM-20260904-002", new AssignRequest("王建国", "USR-002", null, "普通", 15, null, null, null), null);
        service.start("ALM-20260904-002", new StartRequest(null, null), null);

        DemoAlert alert = service.escalate("ALM-20260904-002", new EscalateRequest("测试升级原因", "紧急", null, null), null);
        assertThat(alert.previousRiskLevelCode).isEqualTo(RiskLevels.SEVERE);
        assertThat(alert.timeline).anyMatch(e -> AlertTimelineEventTypes.ESCALATED.equals(e.eventType())
                && e.text().contains("升级为紧急：测试升级原因"));
    }

    @Test
    @DisplayName("Test 6: 已经是 URGENT 时再次 escalate 报 409 STATE_CONFLICT")
    void repeatedEscalateThrowsConflict() {
        service.confirm("ALM-20260904-002", new ConfirmRequest(null), null);
        service.assign("ALM-20260904-002", new AssignRequest("王建国", "USR-002", null, "普通", 15, null, null, null), null);
        service.start("ALM-20260904-002", new StartRequest(null, null), null);

        service.escalate("ALM-20260904-002", new EscalateRequest("首次升级", "紧急", null, null), null);

        assertThatThrownBy(() -> service.escalate("ALM-20260904-002", new EscalateRequest("二次升级", "紧急", null, null), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("告警已处于最高风险等级或目标等级未升高");
    }

    @Test
    @DisplayName("Test 7: 待处理状态下升级保持待处理状态")
    void escalateFromPendingProcessRetainsStatus() {
        service.confirm("ALM-20260904-002", new ConfirmRequest(null), null);
        service.assign("ALM-20260904-002", new AssignRequest("王建国", "USR-002", null, "普通", 15, null, null, null), null);

        DemoAlert alert = service.escalate("ALM-20260904-002", new EscalateRequest("派单后恶化", "紧急", null, null), null);
        assertThat(alert.statusCode).isEqualTo(AlertStatuses.PENDING_PROCESS);
        assertThat(alert.getStatus()).isEqualTo("待处理");
        assertThat(alert.riskCode).isEqualTo(RiskLevels.URGENT);

        // 依然可以顺利接单开始处理
        DemoAlert started = service.start("ALM-20260904-002", new StartRequest(null, null), null);
        assertThat(started.statusCode).isEqualTo(AlertStatuses.PROCESSING);
    }
}
