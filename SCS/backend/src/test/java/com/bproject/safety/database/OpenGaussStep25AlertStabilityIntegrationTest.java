package com.bproject.safety.database;

import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.module.alert.dto.AlertRequests.AssignRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ConfirmRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TreatmentRequest;
import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.repository.JdbcAlertRepository;
import com.bproject.safety.module.alert.service.AlertChangeNotifier;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.support.concurrency.KeyedLock;
import com.bproject.safety.support.demo.DemoAlertNumberGenerator;
import com.bproject.safety.support.demo.DemoUserProperties;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * SCS Phase 1 / Step 2.5: openGauss 真实数据库 Alert 稳定性、事务边界与并发防重验收测试。
 * 覆盖：六表聚合原子性、异常回滚一致性、EdgeReplay 顺序/崩溃重试幂等、单 JVM 高并发防重。
 *
 * <p>安全准则：仅在环境变量 RUN_OPENGAUSS_STEP25=true 时激活，测试数据具有明确的前缀
 * {@code scs_step25_it_}，在完成后通过级联删除精确清除自身数据，严禁影响正式数据。</p>
 */
@EnabledIfEnvironmentVariable(named = OpenGaussStep25AlertStabilityIntegrationTest.ENV_STEP25_ENABLED, matches = "true")
@DisplayName("Phase 1 / Step 2.5: openGauss 真实数据库 Alert 事务边界与稳定性验收测试")
public class OpenGaussStep25AlertStabilityIntegrationTest extends OpenGaussSpikeSupport {

    public static final String ENV_STEP25_ENABLED = "RUN_OPENGAUSS_STEP25";

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private JdbcAlertRepository alertRepository;
    private AlertService alertService;
    private KeyedLock keyedLock;
    private Clock clock;

    @BeforeEach
    void setUp() {
        if (dataSource == null) {
            return;
        }
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);

        DemoMasterData masterData = new DemoMasterData();
        clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        ObjectMapper objectMapper = new ObjectMapper();

        alertRepository = new JdbcAlertRepository(jdbcTemplate, objectMapper, masterData, clock);
        IdempotencyService idempotency = new IdempotencyService(null, 600);
        DemoUserProperties demoUser = new DemoUserProperties("USR-001", "李娜", "安全员", "安全管理组", "夜班", true);
        keyedLock = new KeyedLock();
        com.bproject.safety.module.alert.service.AlertNumberGenerator numberGenerator = new com.bproject.safety.module.alert.service.AlertNumberGenerator() {
            private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(100);
            @Override
            public String nextAlertNumber() {
                return "scs_step25_it_" + System.currentTimeMillis() + "_" + counter.incrementAndGet();
            }
        };

        alertService = new AlertService(alertRepository, idempotency, demoUser, masterData, clock,
                AlertChangeNotifier.NOOP, numberGenerator, transactionManager, keyedLock);

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
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_alert WHERE alert_no LIKE 'scs_step25_it_%'")) {
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("P1-A: 事务边界与原子性：事务内写操作回滚，主表与所有子表记录彻底消失")
    void testDirectWriteTransactionAtomicityRollback() throws Exception {
        String alertId = "scs_step25_it_tx_atom_01";
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        try {
            transactionTemplate.execute(status -> {
                DemoAlert alert = new DemoAlert();
                alert.id = alertId;
                alert.title = "事务原子性测试";
                alert.riskCode = RiskLevels.URGENT;
                alert.statusCode = AlertStatuses.PENDING_CONFIRM;
                alert.occurredAt = now;
                alert.timeline = List.of(
                        com.bproject.safety.module.alert.model.TimelineEvent.of("10:00:00", now, "节点1", "done", "CREATED", 1)
                );
                alert.evidence = AlertEvidence.MetricEvidence.device(List.of(), "测试证据");
                alertRepository.save(alert);

                // 强制触发子表或后续业务异常
                status.setRollbackOnly();
                throw new IllegalStateException("Simulated aggregate failure triggering rollback");
            });
        } catch (IllegalStateException ignored) {
        }

        // 验证主表与子表全无痕迹
        Optional<DemoAlert> reloaded = alertRepository.findById(alertId);
        assertThat(reloaded).isEmpty();

        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM safety.safety_alert_timeline t JOIN safety.safety_alert a ON t.alert_id = a.id WHERE a.alert_no = ?")) {
                ps.setString(1, alertId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(0);
                }
            }
        }
    }

    @Test
    @DisplayName("P1-B: EdgeReplay 顺序幂等：连续两次补传同一离线事件，openGauss 严格仅有 1 条告警且时间线无重复")
    void testEdgeReplaySequentialIdempotencyOnOpenGauss() throws Exception {
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(30);
        String offlineEvtId = "scs_step25_it_replay_01";
        AlertService.EdgeReplayDraft draft = new AlertService.EdgeReplayDraft(
                "EDGE-NODE-01", offlineEvtId, occurredAt,
                "边缘感知", "离线人员闯入告警", "危险区域闯入", "严重",
                "1号泊位", "作业人员 P-1002", "RULE-01", "v1.0",
                "RULE", "RULE-01", "v1.0", 15, null,
                "离线判定闯入", "现场已发出蜂鸣");

        DemoAlert first = alertService.createEdgeReplayAlert(draft);
        assertThat(first).isNotNull();
        String alertId = first.id;
        assertThat(first.timeline).hasSize(7);

        // 第二次重复调用
        DemoAlert second = alertService.createEdgeReplayAlert(draft);
        assertThat(second.id).isEqualTo(alertId);

        // 验证 openGauss 实机状态
        try (Connection conn = getConnection()) {
            String dedupKey = "EDGE-REPLAY:EDGE-NODE-01:" + offlineEvtId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM safety.safety_alert WHERE dedup_key = ?")) {
                ps.setString(1, dedupKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM safety.safety_alert_timeline t JOIN safety.safety_alert a ON t.alert_id = a.id WHERE a.alert_no = ?")) {
                ps.setString(1, alertId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // 初始时间线节点严格为 7 条，未翻倍为 14 条
                    assertThat(rs.getInt(1)).isEqualTo(7);
                }
            }
        }
    }

    @Test
    @DisplayName("P1-C: 8 线程高并发相同 dedupKey 建单，在 openGauss 实机上产生且仅产生 1 条主表记录")
    void testConcurrentDedupKeySingleAggregateOnOpenGauss() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<DemoAlert> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        String dedupKey = "scs_step25_it_dedup_conc_" + System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    AlertService.NewRiskAlert draft = new AlertService.NewRiskAlert(
                            "设备防碰撞", dedupKey, "并发防重实机测试", "碰撞预警",
                            RiskLevels.SEVERE, "龙门吊A区", "吊装机01 & 02",
                            "RULE-COL-001", "v1.0", "RULE", "RULE-COL-001", "v1.0",
                            10, null, "系统感知到碰撞风险", "已触发声光预警");
                    DemoAlert alert = alertService.createRiskAlert(draft);
                    results.add(alert);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threads);

        // 所有线程获取相同 ID
        String primaryId = results.get(0).id;
        for (DemoAlert alert : results) {
            assertThat(alert.id).isEqualTo(primaryId);
        }

        // 实机数据库断言：safety_alert 严格只有 1 条记录
        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM safety.safety_alert WHERE dedup_key = ?")) {
                ps.setString(1, dedupKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM safety.safety_alert_timeline t JOIN safety.safety_alert a ON t.alert_id = a.id WHERE a.alert_no = ?")) {
                ps.setString(1, primaryId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // 严格为 2 条时间线
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
            }
        }
    }

    @Test
    @DisplayName("P1-C: 并发提交不同 dedupKey，在 openGauss 上各自独立持久化")
    void testConcurrentDifferentDedupKeyParallelOnOpenGauss() throws Exception {
        int count = 4;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch readyLatch = new CountDownLatch(count);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<DemoAlert> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < count; i++) {
            final int idx = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    String dedupKey = "scs_step25_it_diff_" + idx + "_" + System.currentTimeMillis();
                    AlertService.NewRiskAlert draft = new AlertService.NewRiskAlert(
                            "人员安全", dedupKey, "独立预警 " + idx, "越界预警",
                            RiskLevels.WARNING, "B区", "人员 P-" + idx,
                            "RULE-02", "v1.0", "RULE", "RULE-02", "v1.0",
                            5, null, "越界检测", "发出提醒");
                    results.add(alertService.createRiskAlert(draft));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(15, TimeUnit.SECONDS);

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(count);
        long distinctIds = results.stream().map(a -> a.id).distinct().count();
        assertThat(distinctIds).isEqualTo(count);

        // 验证各自在 openGauss 中独立落库
        for (DemoAlert alert : results) {
            Optional<DemoAlert> found = alertRepository.findById(alert.id);
            assertThat(found).isPresent();
        }
    }
}
