package com.bproject.safety.database;

import com.bproject.safety.module.collision.model.CollisionRiskLevels;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.model.SensorHealth;
import com.bproject.safety.module.collision.repository.JdbcCollisionRepository;
import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgeNodeStatuses;
import com.bproject.safety.module.ops.repository.JdbcEdgeNodeRepository;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * SCS Phase 1 / Step 1: openGauss 真实实机集成测试（在 bpoc-node4 上执行）。
 * 验证 CollisionRepository 与 EdgeNodeRepository 在 openGauss 6.0.5 上的完整持久化、
 * 事务提交与回滚隔离性。
 *
 * <p>安全原则：仅在环境变量 RUN_OPENGAUSS_STEP1=true 时激活，测试数据具有明确的前缀
 * {@code scs_step1_it_}，并在完成后精确删除自身测试记录，严禁 TRUNCATE 或影响正式数据。</p>
 */
@EnabledIfEnvironmentVariable(named = OpenGaussStep1IntegrationTest.ENV_STEP1_ENABLED, matches = "true")
@DisplayName("Phase 1 / Step 1: openGauss 6.0.5 真实数据库持久化与事务集成验收测试")
public class OpenGaussStep1IntegrationTest extends OpenGaussSpikeSupport {

    public static final String ENV_STEP1_ENABLED = "RUN_OPENGAUSS_STEP1";

    private static final String TEST_DEVICE_CODE = "scs_step1_it_veh01";
    private static final String TEST_NODE_CODE = "scs_step1_it_edge01";
    private static final String TX_COMMIT_DEVICE = "scs_step1_it_tx_commit";
    private static final String TX_ROLLBACK_DEVICE = "scs_step1_it_tx_rollback";

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private JdbcCollisionRepository collisionRepository;
    private JdbcEdgeNodeRepository edgeNodeRepository;

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

        collisionRepository = new JdbcCollisionRepository(jdbcTemplate, masterData, clock);
        edgeNodeRepository = new JdbcEdgeNodeRepository(jdbcTemplate, masterData);

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
                    "DELETE FROM safety.collision_device WHERE device_code LIKE 'scs_step1_it_%'")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.edge_node WHERE node_code LIKE 'scs_step1_it_%'")) {
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("Collision: openGauss 真实 CRUD 与字段往返持久化验收")
    void testCollisionDeviceCrudAndPersistence() throws Exception {
        DemoCollisionDevice device = new DemoCollisionDevice();
        device.id = TEST_DEVICE_CODE;
        device.name = "集成测试转运车";
        device.type = "转运车辆";
        device.area = "车辆通道";
        device.status = "作业中";
        device.speed = 8.5;
        device.direction = "东北";
        device.controlStatus = "自动控制可用";
        device.communication = "在线 · 15ms";
        device.radarStatus = "正常";
        device.riskCode = CollisionRiskLevels.WARNING;
        device.healthCode = SensorHealth.NORMAL;
        device.relatedEquipmentId = "TIP-02";
        device.x = 42.0;
        device.y = 58.0;
        device.lastUpdated = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString();

        // 1. Save (INSERT)
        collisionRepository.save(device);

        // 2. FindById
        Optional<DemoCollisionDevice> found = collisionRepository.findById(TEST_DEVICE_CODE);
        assertThat(found).isPresent();
        DemoCollisionDevice d = found.get();
        assertThat(d.id).isEqualTo(TEST_DEVICE_CODE);
        assertThat(d.name).isEqualTo("集成测试转运车");
        assertThat(d.type).isEqualTo("转运车辆");
        assertThat(d.area).isEqualTo("车辆通道");
        assertThat(d.speed).isEqualTo(8.5);
        assertThat(d.direction).isEqualTo("东北");
        assertThat(d.riskCode).isEqualTo(CollisionRiskLevels.WARNING);
        assertThat(d.healthCode).isEqualTo(SensorHealth.NORMAL);

        // 3. Update
        d.speed = 10.2;
        d.riskCode = CollisionRiskLevels.SEVERE;
        collisionRepository.save(d);

        Optional<DemoCollisionDevice> updated = collisionRepository.findById(TEST_DEVICE_CODE);
        assertThat(updated).isPresent();
        assertThat(updated.get().speed).isEqualTo(10.2);
        assertThat(updated.get().riskCode).isEqualTo(CollisionRiskLevels.SEVERE);

        // 4. Verify openGauss physical row
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM safety.collision_device WHERE device_code = ? AND is_deleted = false")) {
            ps.setString(1, TEST_DEVICE_CODE);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("EdgeNode: openGauss 真实 CRUD、TIMESTAMPTZ 与运行态持久化验收")
    void testEdgeNodeCrudAndPersistence() throws Exception {
        DemoEdgeNode node = new DemoEdgeNode();
        node.id = TEST_NODE_CODE;
        node.name = "集成测试边缘节点";
        node.area = "装卸区 A";
        node.ip = "192.168.101.99";
        node.status = EdgeNodeStatuses.ONLINE;
        node.cloudConnected = true;
        node.autonomyActive = false;
        node.agentVersion = "edge-agent 1.4.2";
        node.activeRuleVersion = "RULE-V1.2";
        node.expectedRuleVersion = "RULE-V1.2";
        node.activeFenceVersion = "FENCE-V1.0";
        node.expectedFenceVersion = "FENCE-V1.0";
        node.clockOffsetMs = 28L;
        node.latencyMs = 19;
        node.cpuUsage = 35;
        node.memoryUsage = 50;
        node.diskUsage = 45;
        node.temperature = 39;
        node.queueDepth = 2;
        node.cachedEventCount = 500;
        node.uptimeSec = 72000L;
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        node.lastHeartbeat = now;
        node.lastSyncAt = now;
        node.lastError = null;

        // 1. Save (INSERT)
        edgeNodeRepository.save(node);

        // 2. FindById
        Optional<DemoEdgeNode> found = edgeNodeRepository.findById(TEST_NODE_CODE);
        assertThat(found).isPresent();
        DemoEdgeNode n = found.get();
        assertThat(n.id).isEqualTo(TEST_NODE_CODE);
        assertThat(n.name).isEqualTo("集成测试边缘节点");
        assertThat(n.area).isEqualTo("装卸区 A");
        assertThat(n.ip).isEqualTo("192.168.101.99");
        assertThat(n.cloudConnected).isTrue();
        assertThat(n.cpuUsage).isEqualTo(35);
        assertThat(n.diskUsage).isEqualTo(45);
        assertThat(n.cacheParts).isNotEmpty();
        assertThat(n.lastHeartbeat).isNotNull();

        // 3. Count
        long count = edgeNodeRepository.count();
        assertThat(count).isGreaterThanOrEqualTo(1L);

        // 4. Update
        n.status = EdgeNodeStatuses.DEGRADED;
        n.latencyMs = 150;
        edgeNodeRepository.save(n);

        Optional<DemoEdgeNode> updated = edgeNodeRepository.findById(TEST_NODE_CODE);
        assertThat(updated).isPresent();
        assertThat(updated.get().status).isEqualTo(EdgeNodeStatuses.DEGRADED);
        assertThat(updated.get().latencyMs).isEqualTo(150);
    }

    @Test
    @DisplayName("Transaction: 数据库事务提交验收（提交后数据真实落盘）")
    void testTransactionCommit() {
        transactionTemplate.execute(status -> {
            DemoCollisionDevice device = new DemoCollisionDevice();
            device.id = TX_COMMIT_DEVICE;
            device.name = "事务提交测试设备";
            collisionRepository.save(device);
            return null;
        });

        // 事务提交后，数据库中必定存在该记录
        Optional<DemoCollisionDevice> committed = collisionRepository.findById(TX_COMMIT_DEVICE);
        assertThat(committed).isPresent();
    }

    @Test
    @DisplayName("Transaction: 数据库事务回滚验收（回滚后数据库绝不留痕）")
    void testTransactionRollback() {
        try {
            transactionTemplate.execute(status -> {
                DemoCollisionDevice device = new DemoCollisionDevice();
                device.id = TX_ROLLBACK_DEVICE;
                device.name = "事务回滚测试设备";
                collisionRepository.save(device);

                // 显式触发回滚
                status.setRollbackOnly();
                throw new RuntimeException("Forced rollback for integration test");
            });
        } catch (RuntimeException ignored) {
            // Expected
        }

        // 事务回滚后，数据库中绝不存在该记录
        Optional<DemoCollisionDevice> rolledBack = collisionRepository.findById(TX_ROLLBACK_DEVICE);
        assertThat(rolledBack).isEmpty();
    }
}
