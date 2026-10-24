package com.bproject.safety.database;

import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.repository.JdbcAlertRepository;
import com.bproject.safety.module.ops.model.EdgeLocalLinkage;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.OpsEventLog;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.bproject.safety.module.ops.repository.JdbcEdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.JdbcOpsEventLogRepository;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCS Phase 1 / Step 6: openGauss 真实实机集成测试（在 bpoc-node4 上执行）。
 * 验证边缘离线事件队列（edge_pending_event）与运维事件日志（ops_event_log）迁移至
 * openGauss 后的核心承诺：重启存活、幂等键收敛、补传顺序契约、联动告警编号反查、
 * JSONB null 语义保真与运维日志过滤口径。
 *
 * <p>安全原则：仅在 RUN_OPENGAUSS_STEP6=true 时激活；测试数据使用每轮唯一的
 * {@code scs_step6_it_<millis>} 前缀（幂等键 / 节点编号 / 告警编号三处同源），
 * 各轮数据互不相交，且行主键确定性派生保证同轮重跑收敛到同一行——
 * 即使表上的防物理删除触发器使清理 DELETE 静默失效，也不会产生跨轮污染。</p>
 */
@EnabledIfEnvironmentVariable(named = OpenGaussStep6EdgeOpsIntegrationTest.ENV_STEP6_ENABLED, matches = "true")
@DisplayName("Phase 1 / Step 6: openGauss 6.0.5 边缘离线队列与运维日志持久化集成验收测试")
public class OpenGaussStep6EdgeOpsIntegrationTest extends OpenGaussSpikeSupport {

    public static final String ENV_STEP6_ENABLED = "RUN_OPENGAUSS_STEP6";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 每轮唯一前缀：幂等键 / 节点编号 / 告警编号三处同源。 */
    private final String runPrefix = "scs_step6_it_" + System.currentTimeMillis();

    private NamedParameterJdbcTemplate jdbcTemplate;
    private JdbcEdgeEventQueueRepository queueRepository;
    private JdbcOpsEventLogRepository opsLogRepository;
    private JdbcAlertRepository alertRepository;

    @BeforeEach
    void setUp() {
        if (dataSource == null) {
            return;
        }
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        queueRepository = new JdbcEdgeEventQueueRepository(jdbcTemplate, objectMapper);
        opsLogRepository = new JdbcOpsEventLogRepository(jdbcTemplate);
        alertRepository = new JdbcAlertRepository(jdbcTemplate, objectMapper,
                new DemoMasterData(), Clock.system(ZONE));
        cleanupTestData();
    }

    private void cleanupTestData() {
        if (dataSource == null) {
            return;
        }
        // 若库上防删触发器生效则 DELETE 报错被吞掉——靠每轮唯一前缀保证无跨轮污染
        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.edge_pending_event WHERE idempotency_key LIKE ?")) {
                ps.setString(1, runPrefix + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.ops_event_log WHERE edge_node_code LIKE ?")) {
                ps.setString(1, runPrefix + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_alert WHERE alert_no LIKE ?")) {
                ps.setString(1, runPrefix + "%");
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
        }
    }

    private EdgePendingEvent newEvent(String seq, String status, OffsetDateTime occurredAt) {
        EdgePendingEvent e = new EdgePendingEvent();
        e.eventId = runPrefix + "_evt_" + seq;
        e.edgeNodeId = runPrefix + "_node";
        e.eventType = "person-intrusion";
        e.businessKey = e.edgeNodeId + ":person-intrusion:" + seq;
        // openGauss TIMESTAMPTZ 微秒精度按四舍五入存储；预截断消除亚微秒断言抖动
        e.edgeOccurredAt = occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        e.receivedAt = e.edgeOccurredAt;
        e.idempotencyKey = runPrefix + "_key_" + seq;
        e.ruleVersionUsed = "RULE-B5-04";
        e.risk = "严重";
        e.payloadSummary = new EdgePendingEvent.PayloadSummary(
                "人员闯入测试盲区", "装卸区 A", "P-1042", "FENCE-03", null, "严重", e.businessKey, "距离 3.6m");
        e.status = status;
        return e;
    }

    @Test
    @DisplayName("重启存活: 新仓储实例（模拟进程重启）读回完整队列与全部字段")
    void testQueueSurvivesRestart() {
        OffsetDateTime base = OffsetDateTime.now(ZONE).minusMinutes(10);
        queueRepository.save(newEvent("01", PendingEventStatuses.PENDING, base));
        queueRepository.save(newEvent("02", PendingEventStatuses.FAILED, base.plusMinutes(1)));

        // 模拟进程重启：全新仓储实例直连同一数据库
        JdbcEdgeEventQueueRepository revived = new JdbcEdgeEventQueueRepository(
                jdbcTemplate, new ObjectMapper());

        List<EdgePendingEvent> all = revived.findAll();
        List<EdgePendingEvent> mine = all.stream()
                .filter(e -> e.idempotencyKey.startsWith(runPrefix)).toList();
        assertThat(mine).hasSize(2);

        EdgePendingEvent loaded = revived.findByEventId(runPrefix + "_evt_01").orElseThrow();
        assertThat(loaded.edgeNodeId).isEqualTo(runPrefix + "_node");
        assertThat(loaded.eventType).isEqualTo("person-intrusion");
        assertThat(loaded.risk).isEqualTo("严重");
        assertThat(loaded.status).isEqualTo(PendingEventStatuses.PENDING);
        assertThat(loaded.payloadSummary).isNotNull();
        assertThat(loaded.payloadSummary.title).isEqualTo("人员闯入测试盲区");
        assertThat(loaded.payloadSummary.person).isEqualTo("P-1042");
        // openGauss TIMESTAMPTZ 精度为微秒，往返后纳秒位截断属预期行为
        assertThat(loaded.edgeOccurredAt)
                .isEqualTo(base.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("幂等收敛: 同幂等键重复补传收敛到同一行，行数不增长")
    void testIdempotentReplayConvergesToSingleRow() throws Exception {
        EdgePendingEvent first = newEvent("10", PendingEventStatuses.PENDING,
                OffsetDateTime.now(ZONE).minusMinutes(5));
        queueRepository.save(first);

        // 同幂等键重放：状态推进为 SYNCED 并回填联动信息
        EdgePendingEvent replay = first.copy();
        replay.status = PendingEventStatuses.SYNCED;
        replay.syncedAt = OffsetDateTime.now(ZONE);
        replay.retryCount = 1;
        replay.syncDelaySec = 42L;
        queueRepository.save(replay);

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM safety.edge_pending_event WHERE idempotency_key LIKE ?")) {
            ps.setString(1, runPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(1);
            }
        }

        EdgePendingEvent loaded = queueRepository.findByIdempotencyKey(first.idempotencyKey).orElseThrow();
        assertThat(loaded.status).isEqualTo(PendingEventStatuses.SYNCED);
        assertThat(loaded.retryCount).isEqualTo(1);
        assertThat(loaded.syncDelaySec).isEqualTo(42L);
    }

    @Test
    @DisplayName("补传顺序契约: PENDING/FAILED 按发生时间升序，SYNCING 不被重放捞取")
    void testPendingForReplayOrdering() {
        OffsetDateTime base = OffsetDateTime.now(ZONE).minusMinutes(30);
        queueRepository.save(newEvent("21", PendingEventStatuses.PENDING, base.plusMinutes(3)));
        queueRepository.save(newEvent("22", PendingEventStatuses.SYNCING, base.plusMinutes(1)));
        queueRepository.save(newEvent("23", PendingEventStatuses.FAILED, base.plusMinutes(1)));
        queueRepository.save(newEvent("24", PendingEventStatuses.SYNCED, base.plusMinutes(2)));

        List<EdgePendingEvent> pending = queueRepository.findPendingForReplay(runPrefix + "_node");
        List<String> ids = pending.stream().map(e -> e.eventId).toList();
        assertThat(ids).containsExactly(runPrefix + "_evt_23", runPrefix + "_evt_21");
        assertThat(ids).doesNotContain(runPrefix + "_evt_22", runPrefix + "_evt_24");

        int depth = queueRepository.pendingCount(runPrefix + "_node");
        assertThat(depth).isEqualTo(3); // PENDING + SYNCING + FAILED 均占用队列
    }

    @Test
    @DisplayName("联动告警反查: linked_alert_id 落 UUID，读回还原 alert_no 业务编号")
    void testLinkedAlertNumberRoundtrip() {
        String alertNo = runPrefix + "_alm";
        DemoAlert alert = new DemoAlert();
        alert.id = alertNo;
        alert.title = "边缘补传联动告警";
        alert.source = "边缘补传";
        alert.eventType = "person-intrusion";
        alert.riskCode = RiskLevels.SEVERE;
        alert.occurredAt = OffsetDateTime.now(ZONE);
        alertRepository.save(alert);

        EdgePendingEvent e = newEvent("30", PendingEventStatuses.SYNCED,
                OffsetDateTime.now(ZONE).minusMinutes(2));
        e.linkedAlertId = alertNo;
        e.syncedAt = OffsetDateTime.now(ZONE);
        queueRepository.save(e);

        EdgePendingEvent loaded = queueRepository.findByIdempotencyKey(e.idempotencyKey).orElseThrow();
        assertThat(loaded.linkedAlertId).isEqualTo(alertNo);
    }

    @Test
    @DisplayName("JSONB null 语义: localLinkage 为空时落 jsonb 'null' 并读回为 null")
    void testJsonbNullFidelity() {
        EdgePendingEvent e = newEvent("40", PendingEventStatuses.PENDING,
                OffsetDateTime.now(ZONE).minusMinutes(1));
        e.localLinkage = null;
        queueRepository.save(e);

        EdgePendingEvent loaded = queueRepository.findByIdempotencyKey(e.idempotencyKey).orElseThrow();
        assertThat(loaded.localLinkage).isNull();
        assertThat(loaded.payloadSummary).isNotNull();
    }

    @Test
    @DisplayName("localLinkage 完整往返: 联动动作列表序列化不丢失")
    void testLocalLinkageRoundtrip() {
        EdgePendingEvent e = newEvent("41", PendingEventStatuses.PENDING,
                OffsetDateTime.now(ZONE).minusMinutes(1));
        e.localLinkage = EdgeLocalLinkage.success(List.of("现场声光报警已启动", "PLC 停车指令已执行"), true);
        queueRepository.save(e);

        EdgePendingEvent loaded = queueRepository.findByIdempotencyKey(e.idempotencyKey).orElseThrow();
        assertThat(loaded.localLinkage).isNotNull();
        assertThat(loaded.localLinkage.alarm).isTrue();
        assertThat(loaded.localLinkage.plcStop).isEqualTo("success");
        assertThat(loaded.localLinkage.actions).containsExactly("现场声光报警已启动", "PLC 停车指令已执行");
    }

    @Test
    @DisplayName("运维日志: 过滤口径与倒序（level 忽略大小写、时间含边界、limit 截断）")
    void testOpsLogFiltersAndOrdering() {
        String node = runPrefix + "_node";
        OffsetDateTime base = OffsetDateTime.now(ZONE).minusMinutes(5);
        appendLog(node, "error", OpsEventLog.NODE_OFFLINE, "节点心跳超时", base);
        appendLog(node, "warning", OpsEventLog.REPLAY_STARTED, "开始补传", base.plusMinutes(1));
        appendLog(node, "info", OpsEventLog.REPLAY_SUCCESS, "补传成功", base.plusMinutes(2));
        appendLog(node, "info", OpsEventLog.NODE_ONLINE, "节点恢复", base.plusMinutes(3));

        // level 忽略大小写
        List<OpsEventLog> errors = opsLogRepository.recent(50, node, "ERROR", null, null, null);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).type).isEqualTo(OpsEventLog.NODE_OFFLINE);

        // 时间范围含边界: [base+1, base+2]
        List<OpsEventLog> ranged = opsLogRepository.recent(50, node, null, null,
                base.plusMinutes(1).toString(), base.plusMinutes(2).toString());
        assertThat(ranged).hasSize(2);
        // 倒序：最新在前
        assertThat(ranged.get(0).type).isEqualTo(OpsEventLog.REPLAY_SUCCESS);
        assertThat(ranged.get(1).type).isEqualTo(OpsEventLog.REPLAY_STARTED);

        // type 精确匹配
        List<OpsEventLog> byType = opsLogRepository.recent(50, node, null, OpsEventLog.NODE_ONLINE, null, null);
        assertThat(byType).hasSize(1);

        // limit 截断为最新 2 条
        List<OpsEventLog> limited = opsLogRepository.recent(2, node, null, null, null, null);
        assertThat(limited).hasSize(2);
        assertThat(limited.get(0).type).isEqualTo(OpsEventLog.NODE_ONLINE);

        // time 展示字段由 occurred_at 重新生成
        assertThat(limited.get(0).time).isNotBlank();
    }

    private void appendLog(String node, String level, String type, String text, OffsetDateTime ts) {
        OpsEventLog log = new OpsEventLog("OPS-LOG-t", ts, null, node, level, type, text);
        opsLogRepository.append(log);
    }

    @Test
    @DisplayName("跨仓储事务: 队列状态推进与运维日志追加同事务原子提交")
    void testQueueAndLogSameTransaction() {
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        EdgePendingEvent e = newEvent("50", PendingEventStatuses.PENDING,
                OffsetDateTime.now(ZONE).minusMinutes(1));

        tx.executeWithoutResult(status -> {
            queueRepository.save(e);
            appendLog(runPrefix + "_node", "info", OpsEventLog.REPLAY_STARTED, "同事务追加", OffsetDateTime.now(ZONE));
        });

        Optional<EdgePendingEvent> loaded = queueRepository.findByIdempotencyKey(e.idempotencyKey);
        assertThat(loaded).isPresent();
        List<OpsEventLog> logs = opsLogRepository.recent(10, runPrefix + "_node", null,
                OpsEventLog.REPLAY_STARTED, null, null);
        assertThat(logs).isNotEmpty();
    }
}
