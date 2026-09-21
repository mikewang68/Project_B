package com.bproject.safety.support.phasea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.WebSocketSessionRegistry;
import com.bproject.safety.module.ai.model.AiReviewStatuses;
import com.bproject.safety.module.ai.model.AiRiskLevels;
import com.bproject.safety.module.ai.seed.AiDemoSeeder;
import com.bproject.safety.module.alert.dto.AlertRequests.AssignRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ConfirmRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.StartRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TransferRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TreatmentRequest;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.AlertTimelineEventTypes;
import com.bproject.safety.module.alert.model.DecisionSources;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.model.TimelineEvent;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.alert.service.AlertChangeNotifier;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.collision.model.CollisionRiskLevels;
import com.bproject.safety.module.collision.model.SensorHealth;
import com.bproject.safety.module.collision.repository.InMemoryCollisionRepository;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.module.fence.repository.InMemoryFenceRepository;
import com.bproject.safety.module.ops.service.OpsInventory;
import com.bproject.safety.module.personnel.repository.InMemoryPersonnelRepository;
import com.bproject.safety.module.personnel.service.PersonnelService;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.support.demo.DemoFeatureGuard;
import com.bproject.safety.support.demo.DemoUserProperties;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Database Pre-Migration Phase A 专项单测（F-01 / F-02 / F-07 / F-12 / 设备主数据 / Demo 开关）。
 *
 * <p>纯手工装配，不启动 Spring 上下文，避免依赖 profile 与种子启动顺序。</p>
 */
class PhaseASemanticHardeningTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneOffset.UTC);
    private final DemoMasterData masterData = new DemoMasterData();
    private final DemoDeviceMasterData deviceMasterData = new DemoDeviceMasterData(masterData);

    // ---------------------------------------------------------------------
    // F-01 机器 Code 体系
    // ---------------------------------------------------------------------
    @Nested
    @DisplayName("F-01 机器 Code 与中文 Label")
    class CodeSystem {

        @Test
        @DisplayName("Alert 风险等级 code ↔ label 双向映射，未知 code 返回 null")
        void riskLevelsRoundTrip() {
            assertThat(RiskLevels.label(RiskLevels.NORMAL)).isEqualTo("一般");
            assertThat(RiskLevels.label(RiskLevels.WARNING)).isEqualTo("预警");
            assertThat(RiskLevels.label(RiskLevels.SEVERE)).isEqualTo("严重");
            assertThat(RiskLevels.label(RiskLevels.URGENT)).isEqualTo("紧急");
            assertThat(RiskLevels.fromLabel("紧急")).isEqualTo(RiskLevels.URGENT);
            assertThat(RiskLevels.normalize("严重")).isEqualTo(RiskLevels.SEVERE);
            assertThat(RiskLevels.normalize(RiskLevels.SEVERE)).isEqualTo(RiskLevels.SEVERE);
            assertThat(RiskLevels.normalize("不存在的等级")).isNull();
            assertThat(RiskLevels.label("BOGUS")).isNull();
            assertThat(RiskLevels.isHigh(RiskLevels.SEVERE)).isTrue();
            assertThat(RiskLevels.isHigh(RiskLevels.WARNING)).isFalse();
        }

        @Test
        @DisplayName("Alert 状态 code 与中文 label 一一对应")
        void alertStatusesRoundTrip() {
            assertThat(AlertStatuses.label(AlertStatuses.PENDING_CONFIRM)).isEqualTo("待确认");
            assertThat(AlertStatuses.label(AlertStatuses.PROCESSING)).isEqualTo("处理中");
            assertThat(AlertStatuses.label(AlertStatuses.CLOSED)).isEqualTo("已关闭");
            assertThat(AlertStatuses.fromLabel("待复核")).isEqualTo(AlertStatuses.PENDING_REVIEW);
            assertThat(AlertStatuses.normalize("已升级")).isEqualTo(AlertStatuses.ESCALATED);
            assertThat(AlertStatuses.ACTIVE).doesNotContain(AlertStatuses.CLOSED);
        }

        @Test
        @DisplayName("AI 风险（低/中/高）与 Alert 四级风险是两套词表，映射集中在 AI 侧")
        void aiRiskIsSeparateVocabulary() {
            assertThat(AiRiskLevels.label(AiRiskLevels.HIGH)).isEqualTo("高");
            assertThat(AiRiskLevels.toAlertRiskCode(AiRiskLevels.HIGH)).isEqualTo(RiskLevels.SEVERE);
            assertThat(AiReviewStatuses.label(AiReviewStatuses.PENDING)).isEqualTo("待复核");
            assertThat(AiReviewStatuses.normalize("已确认违规")).isEqualTo(AiReviewStatuses.CONFIRMED);
        }

        @Test
        @DisplayName("Collision：待确认属于感知健康 UNCERTAIN，不是碰撞风险等级")
        void collisionRiskAndHealthAreSeparate() {
            assertThat(SensorHealth.fromLabel("待确认")).isEqualTo(SensorHealth.UNCERTAIN);
            assertThat(CollisionRiskLevels.label(CollisionRiskLevels.SAFE)).isEqualTo("安全");
            // UNCERTAIN 绝不能出现在碰撞风险词表里
            assertThat(CollisionRiskLevels.normalize(SensorHealth.UNCERTAIN)).isNull();
            assertThat(CollisionRiskLevels.toAlertRiskCode(CollisionRiskLevels.URGENT))
                    .isEqualTo(RiskLevels.URGENT);
        }

        @Test
        @DisplayName("Fence / Rule 状态均为机器 code")
        void fenceAndRuleStatusesAreCodes() {
            assertThat(FenceStatuses.label(FenceStatuses.EFFECTIVE)).isEqualTo("已生效");
            assertThat(FenceStatuses.normalize("已生效")).isEqualTo(FenceStatuses.EFFECTIVE);
            assertThat(RuleStatuses.label(RuleStatuses.ACTIVE)).isEqualTo("已生效");
            assertThat(RuleStatuses.normalize("草稿")).isEqualTo(RuleStatuses.DRAFT);
        }
    }

    // ---------------------------------------------------------------------
    // F-02 assignee userCode
    // ---------------------------------------------------------------------
    @Nested
    @DisplayName("F-02 派单责任人以 USR code 为权威关联")
    class AssigneeCodes {

        private AlertRepository repository;
        private AlertService service;

        @BeforeEach
        void setUp() {
            repository = new InMemoryAlertRepository();
            AlertDemoSeeder.buildSeeds(clock).forEach(repository::save);
            service = newAlertService(repository);
            service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), null);
        }

        @Test
        @DisplayName("派单保存 assigneeUserCode + 姓名快照，不信任前端传入姓名")
        void assignStoresUserCodeAndSnapshot() {
            DemoAlert a = service.assign("ALM-20260904-002",
                    new AssignRequest("前端随便传的名字", "USR-002", "王建国", "普通", 15, null, null, null), null);
            assertThat(a.assigneeUserCode).isEqualTo("USR-002");
            // 姓名快照来自 DemoMasterData，而非前端传入的名字
            assertThat(a.assignee).isEqualTo("王建国");
        }

        @Test
        @DisplayName("旧客户端只传唯一姓名仍可兼容（Deprecated 路径）")
        void assignByNameCompat() {
            DemoAlert a = service.assign("ALM-20260904-002",
                    new AssignRequest("王建国", null, null, "普通", 15, null, null, null), null);
            assertThat(a.assigneeUserCode).isEqualTo("USR-002");
            assertThat(a.assignee).isEqualTo("王建国");
        }

        @Test
        @DisplayName("不存在的 userId → 422")
        void unknownUserRejected() {
            assertThatThrownBy(() -> service.assign("ALM-20260904-002",
                    new AssignRequest(null, "USR-999", null, "普通", 15, null, null, null), null))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("派单必须指定有效责任人");
        }

        @Test
        @DisplayName("转派同样写 userCode + 姓名快照")
        void transferStoresUserCode() {
            service.assign("ALM-20260904-002",
                    new AssignRequest("王建国", "USR-002", "王建国", "普通", 15, null, null, null), null);
            DemoAlert a = service.transfer("ALM-20260904-002",
                    new TransferRequest("李娜", "USR-001", "改派", "王建国"), null);
            assertThat(a.assigneeUserCode).isEqualTo("USR-001");
            assertThat(a.assignee).isEqualTo("李娜");
        }
    }

    // ---------------------------------------------------------------------
    // F-07 Provenance + F-12 Timeline 结构化
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("F-07 人员越界：provenance=FENCE，ruleId/ruleVersion 必须为空，不得把围栏版本伪装成规则版本")
    void personnelIntrusionProvenanceIsFence() {
        InMemoryFenceRepository fenceRepository = new InMemoryFenceRepository();
        fenceRepository.resetDemoData();
        InMemoryPersonnelRepository personnelRepository = new InMemoryPersonnelRepository();
        personnelRepository.resetDemoData();
        AlertRepository alertRepository = new InMemoryAlertRepository();
        AlertService alertService = newAlertService(alertRepository);
        DomainLivePublisher publisher = new DomainLivePublisher(
                new WebSocketSessionRegistry(), new ObjectMapper(), clock,
                new com.bproject.safety.common.realtime.LiveEventGate());
        PersonnelService personnelService = new PersonnelService(
                personnelRepository, fenceRepository, alertRepository, alertService, publisher, clock,
                new com.bproject.safety.common.realtime.LiveEventGate());

        personnelService.simulateAbnormal("P-ZHAO", "intrusion");

        List<DemoAlert> intrusionAlerts = alertRepository.findAll().stream()
                .filter(a -> "人员越界".equals(a.eventType))
                .toList();
        assertThat(intrusionAlerts).hasSize(1);
        DemoAlert a = intrusionAlerts.get(0);
        assertThat(a.decisionSourceType).isEqualTo(DecisionSources.FENCE);
        assertThat(a.decisionSourceCode).startsWith("FENCE-");
        assertThat(a.decisionSourceVersion).isNotBlank();
        assertThat(a.ruleId).isNull();
        assertThat(a.ruleVersion).isNull();
    }

    @Test
    @DisplayName("F-12 Alert 处置闭环：所有 Timeline 节点 eventType 非空、sequenceNo 严格递增")
    void timelineIsStructuredThroughFullFlow() {
        AlertRepository repository = new InMemoryAlertRepository();
        AlertDemoSeeder.buildSeeds(clock).forEach(repository::save);
        AlertService service = newAlertService(repository);

        service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), null);
        service.assign("ALM-20260904-002",
                new AssignRequest("王建国", "USR-002", "王建国", "紧急", 10, null, null, null), null);
        service.start("ALM-20260904-002", new StartRequest("王建国", "王建国"), null);
        service.treatment("ALM-20260904-002",
                new TreatmentRequest(List.of("人员已撤离"), "风险已解除", null, "已教育", true, null), null);

        DemoAlert a = repository.findById("ALM-20260904-002").orElseThrow();
        List<TimelineEvent> nodes = a.timeline;
        assertThat(nodes).isNotEmpty();
        for (TimelineEvent n : nodes) {
            assertThat(n.eventType()).as("每个节点必须有机器 eventType").isNotBlank();
        }
        for (int i = 1; i < nodes.size(); i++) {
            assertThat(nodes.get(i).sequenceNo())
                    .as("sequenceNo 严格递增")
                    .isGreaterThan(nodes.get(i - 1).sequenceNo());
        }
        assertThat(nodes.get(0).sequenceNo()).isEqualTo(1);
        List<String> types = nodes.stream().map(TimelineEvent::eventType).toList();
        assertThat(types).contains(
                AlertTimelineEventTypes.CONFIRMED,
                AlertTimelineEventTypes.ASSIGNED,
                AlertTimelineEventTypes.STARTED,
                AlertTimelineEventTypes.TREATMENT_SUBMITTED);
    }

    // ---------------------------------------------------------------------
    // 设备主数据归口
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("设备主数据：Camera / Collision / OpsInventory 静态身份来自同一 DemoDeviceMasterData")
    void deviceMasterDataIsSingleSource() {
        // Camera（AI 域）
        var cameras = AiDemoSeeder.buildCameras(deviceMasterData);
        var cam01 = cameras.stream().filter(c -> "CAM-01".equals(c.cameraId())).findFirst().orElseThrow();
        DemoDeviceMasterData.DeviceIdentity camMaster = deviceMasterData.device("CAM-01").orElseThrow();
        assertThat(cam01.area()).isEqualTo(camMaster.areaName());
        assertThat(cam01.name()).isEqualTo(camMaster.name());

        // Collision 域
        InMemoryCollisionRepository collisionRepository = new InMemoryCollisionRepository(deviceMasterData);
        collisionRepository.resetDemoData();
        var veh07 = collisionRepository.findAll().stream()
                .filter(d -> "VEH-07".equals(d.id)).findFirst().orElseThrow();
        DemoDeviceMasterData.DeviceIdentity vehMaster = deviceMasterData.device("VEH-07").orElseThrow();
        assertThat(veh07.area).isEqualTo(vehMaster.areaName());
        assertThat(veh07.name).isEqualTo(vehMaster.name());

        // Ops 台账
        OpsInventory inventory = new OpsInventory(clock, deviceMasterData);
        inventory.reset();
        var opsCam01 = inventory.device("CAM-01").orElseThrow();
        assertThat(opsCam01.area()).isEqualTo(camMaster.areaName());
        assertThat(opsCam01.name()).isEqualTo(camMaster.name());
    }

    // ---------------------------------------------------------------------
    // F-11 Demo 开关（纯单元；profile 级验证见 DemoFeatureDisabledIntegrationTest）
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("F-11 DemoFeatureGuard：关闭模拟能力时 requireSimulator 抛 403 DEMO_FEATURE_DISABLED")
    void simulatorGuardRejectsWhenDisabled() {
        DemoFeatureGuard disabled = new DemoFeatureGuard(false, false);
        assertThat(disabled.isSeedEnabled()).isFalse();
        assertThat(disabled.isSimulatorEnabled()).isFalse();
        assertThatThrownBy(disabled::requireSimulator)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(DemoFeatureGuard.DEMO_FEATURE_DISABLED));

        DemoFeatureGuard enabled = new DemoFeatureGuard(true, true);
        enabled.requireSimulator(); // 不抛异常
        assertThat(enabled.isSimulatorEnabled()).isTrue();
    }

    private AlertService newAlertService(AlertRepository repository) {
        IdempotencyService idempotency = new IdempotencyService(null, 600);
        var demoUser = new DemoUserProperties(
                "USR-001", "李娜", "安全员", "安全管理组", "夜班", true);
        return new AlertService(repository, idempotency, demoUser, masterData, clock,
                AlertChangeNotifier.NOOP,
                new com.bproject.safety.support.demo.DemoAlertNumberGenerator(repository, clock));
    }
}
