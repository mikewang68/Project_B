package com.bproject.safety.database;

import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.LinkageStep;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.model.TimelineEvent;
import com.bproject.safety.module.alert.model.TreatmentRecord;
import com.bproject.safety.module.alert.repository.AlertQuery;
import com.bproject.safety.module.alert.repository.JdbcAlertRepository;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCS Phase 1 / Step 2: openGauss 真实实机集成测试（在 bpoc-node4 上执行）。
 * 验证 AlertRepository 迁移至 openGauss 6.0.5 后的完整聚合根持久化、时间线 append-only、
 * 多态证据 JSONB、现场处置、联动会话与事务原子性。
 *
 * <p>安全原则：仅在环境变量 RUN_OPENGAUSS_STEP2=true 时激活，测试数据具有明确的前缀
 * {@code scs_step2_it_}，并在完成后精确删除自身测试记录，严禁影响正式数据。</p>
 */
@EnabledIfEnvironmentVariable(named = OpenGaussStep2AlertIntegrationTest.ENV_STEP2_ENABLED, matches = "true")
@DisplayName("Phase 1 / Step 2: openGauss 6.0.5 真实数据库 Alert 聚合持久化与事务集成验收测试")
public class OpenGaussStep2AlertIntegrationTest extends OpenGaussSpikeSupport {

    public static final String ENV_STEP2_ENABLED = "RUN_OPENGAUSS_STEP2";

    private static final String TEST_ALERT_NO = "scs_step2_it_alm01";
    private static final String TX_COMMIT_ALERT = "scs_step2_it_tx_commit";
    private static final String TX_ROLLBACK_ALERT = "scs_step2_it_tx_rollback";

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private JdbcAlertRepository alertRepository;

    @BeforeEach
    void setUp() {
        if (dataSource == null) {
            return;
        }
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);

        DemoMasterData masterData = new DemoMasterData();
        Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        ObjectMapper objectMapper = new ObjectMapper();

        alertRepository = new JdbcAlertRepository(jdbcTemplate, objectMapper, masterData, clock);

        cleanupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
    }

    private void cleanupTestData() {
        if (dataSource == null) {
            return;
        }
        try (Connection conn = getConnection()) {
            // 因外键定义为 ON DELETE CASCADE，删除 safety_alert 会级联清空 5 张子表自身的测试记录
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_alert WHERE alert_no LIKE 'scs_step2_it_%'")) {
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("AlertAggregate: 完整聚合根与 5 个子表真实持久化与全字段读回验收")
    void testAlertAggregateFullPersistenceAndReload() throws Exception {
        DemoAlert alert = new DemoAlert();
        alert.id = TEST_ALERT_NO;
        alert.title = "集成测试龙门吊碰撞预警";
        alert.source = "设备防碰撞";
        alert.eventType = "设备接近预警";
        alert.riskCode = RiskLevels.SEVERE;
        alert.statusCode = AlertStatuses.PROCESSING;
        alert.area = "装卸区 A";
        alert.target = "G-CRANE-01 / VEH-08";
        alert.assignee = "安全员 王建国";
        alert.assigneeUserCode = "USR-1001";
        alert.priority = "紧急";
        alert.slaLimitMin = 15;
        alert.dedupKey = "COLLISION:CRANE-01:VEH-08";
        alert.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        alert.slaDeadline = alert.occurredAt.plusMinutes(15);

        // 1. 时间线节点
        alert.timeline = List.of(
                TimelineEvent.of("13:15:16", alert.occurredAt.minusMinutes(1), "设备间距进入 10m 预警圈", "done", "CREATED", 1),
                TimelineEvent.of("13:15:40", alert.occurredAt, "间距跌破 6m 严重阈值，生成告警", "done", "STARTED", 2)
        );

        // 2. 多态结构化证据
        alert.evidence = AlertEvidence.CollisionEvidence.of(
                4.2, 1.8, List.of(9.6, 8.4, 7.5, 6.8, 5.9, 5.1, 4.2),
                "毫米波雷达正常 · 激光雷达正常", "当前制动距离 3.6m"
        );

        // 3. 现场处置
        alert.treatment = new TreatmentRecord(
                List.of("设备已减速", "现场引导分流"), "风险暂时可控",
                "现场照片_it.jpg", "正在观察", "13:20:00", "王建国"
        );

        // 4. 现场联动
        alert.linkageAvailable = true;
        alert.linkageFinished = false;
        alert.linkage = List.of(
                new LinkageStep("sound-light", "现场声光提醒", "success", "已启动", null),
                new LinkageStep("band", "人员手环提醒", "success", "已发送", null),
                new LinkageStep("slowdown", "减速请求", "running", "正在发送", null)
        );

        // 执行保存
        alertRepository.save(alert);

        // 读取并验证
        Optional<DemoAlert> foundOpt = alertRepository.findById(TEST_ALERT_NO);
        assertThat(foundOpt).isPresent();
        DemoAlert loaded = foundOpt.get();

        assertThat(loaded.id).isEqualTo(TEST_ALERT_NO);
        assertThat(loaded.title).isEqualTo("集成测试龙门吊碰撞预警");
        assertThat(loaded.riskCode).isEqualTo(RiskLevels.SEVERE);
        assertThat(loaded.statusCode).isEqualTo(AlertStatuses.PROCESSING);
        assertThat(loaded.area).isEqualTo("装卸区 A");
        assertThat(loaded.dedupKey).isEqualTo("COLLISION:CRANE-01:VEH-08");

        // 验证时间线保序加载
        assertThat(loaded.timeline).hasSize(2);
        assertThat(loaded.timeline.get(0).sequenceNo()).isEqualTo(1);
        assertThat(loaded.timeline.get(0).text()).isEqualTo("设备间距进入 10m 预警圈");
        assertThat(loaded.timeline.get(1).sequenceNo()).isEqualTo(2);

        // 验证多态证据正确反序列化
        assertThat(loaded.evidence).isInstanceOf(AlertEvidence.CollisionEvidence.class);
        AlertEvidence.CollisionEvidence ce = (AlertEvidence.CollisionEvidence) loaded.evidence;
        assertThat(ce.distance()).isEqualTo(4.2);
        assertThat(ce.radar()).isEqualTo("毫米波雷达正常 · 激光雷达正常");

        // 验证现场处置
        assertThat(loaded.treatment).isNotNull();
        assertThat(loaded.treatment.measures()).containsExactly("设备已减速", "现场引导分流");
        assertThat(loaded.treatment.result()).isEqualTo("风险暂时可控");
        assertThat(loaded.treatment.handler()).isEqualTo("王建国");

        // 验证现场联动与步骤
        assertThat(loaded.linkageAvailable).isTrue();
        assertThat(loaded.linkage).hasSize(3);
        assertThat(loaded.linkage.get(0).id()).isEqualTo("sound-light");
        assertThat(loaded.linkage.get(0).state()).isEqualTo("success");
        assertThat(loaded.linkage.get(2).id()).isEqualTo("slowdown");
        assertThat(loaded.linkage.get(2).state()).isEqualTo("running");

        // 物理数据库直接校验
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM safety.safety_alert WHERE alert_no = ?")) {
            ps.setString(1, TEST_ALERT_NO);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("TimelineAppendOnly: 时间线审计流严格 append-only 验收")
    void testTimelineAppendOnlySemantics() {
        DemoAlert alert = new DemoAlert();
        alert.id = "scs_step2_it_timeline_app";
        alert.title = "时间线追加测试";
        alert.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        alert.timeline = new ArrayList<>(List.of(
                TimelineEvent.of("10:00:00", alert.occurredAt, "步骤1", "done", "CREATED", 1),
                TimelineEvent.of("10:01:00", alert.occurredAt, "步骤2", "done", "STARTED", 2)
        ));
        alertRepository.save(alert);

        // 追加步骤 3
        alert.timeline.add(TimelineEvent.of("10:02:00", alert.occurredAt, "步骤3", "active", "ASSIGNED", 3));
        alertRepository.save(alert);

        Optional<DemoAlert> reloaded = alertRepository.findById("scs_step2_it_timeline_app");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().timeline).hasSize(3);
        assertThat(reloaded.get().timeline.get(0).sequenceNo()).isEqualTo(1);
        assertThat(reloaded.get().timeline.get(1).sequenceNo()).isEqualTo(2);
        assertThat(reloaded.get().timeline.get(2).sequenceNo()).isEqualTo(3);
        assertThat(reloaded.get().timeline.get(2).text()).isEqualTo("步骤3");
    }

    @Test
    @DisplayName("DedupKey: findOpenByDedupKey 在真实 openGauss 上按去重键查找未关闭告警")
    void testFindOpenByDedupKey() {
        String dedup = "COLLISION:IT_DEV1:IT_DEV2";
        DemoAlert alert = new DemoAlert();
        alert.id = "scs_step2_it_dedup_01";
        alert.title = "去重键测试";
        alert.dedupKey = dedup;
        alert.statusCode = AlertStatuses.PENDING_PROCESS;
        alert.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        alertRepository.save(alert);

        Optional<DemoAlert> openAlert = alertRepository.findOpenByDedupKey(dedup);
        assertThat(openAlert).isPresent();
        assertThat(openAlert.get().id).isEqualTo("scs_step2_it_dedup_01");

        // 关闭告警后不再命中
        alert.statusCode = AlertStatuses.CLOSED;
        alertRepository.save(alert);

        Optional<DemoAlert> closedAlert = alertRepository.findOpenByDedupKey(dedup);
        assertThat(closedAlert).isEmpty();
    }

    @Test
    @DisplayName("Transaction: 数据库事务回滚验收（主表与 5 个子表完全回滚不留痕）")
    void testTransactionRollbackAcrossAggregate() {
        try {
            transactionTemplate.execute(status -> {
                DemoAlert alert = new DemoAlert();
                alert.id = TX_ROLLBACK_ALERT;
                alert.title = "事务回滚测试";
                alert.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
                alert.timeline = List.of(TimelineEvent.of("11:00:00", alert.occurredAt, "节点", "done", "CREATED", 1));
                alert.evidence = AlertEvidence.MetricEvidence.device(List.of(), "测试");
                alertRepository.save(alert);

                status.setRollbackOnly();
                throw new RuntimeException("Forced rollback for Step 2 integration test");
            });
        } catch (RuntimeException ignored) {
        }

        Optional<DemoAlert> rolledBack = alertRepository.findById(TX_ROLLBACK_ALERT);
        assertThat(rolledBack).isEmpty();
    }
}
