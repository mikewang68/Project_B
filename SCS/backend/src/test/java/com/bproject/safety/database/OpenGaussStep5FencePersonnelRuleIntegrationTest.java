package com.bproject.safety.database;

import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.EdgeNode;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.module.fence.repository.JdbcFenceRepository;
import com.bproject.safety.module.personnel.model.BraceletStatuses;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.model.PersonRiskLevels;
import com.bproject.safety.module.personnel.model.PersonStatuses;
import com.bproject.safety.module.personnel.repository.JdbcPersonnelRepository;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.DemoRule.Param;
import com.bproject.safety.module.rule.model.DemoRule.Version;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.module.rule.repository.JdbcRuleRepository;
import com.bproject.safety.module.rule.repository.RuleQuery;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCS Phase 2 / Step 5: openGauss 真实实机集成测试（在 bpoc-node4 上执行）。
 * 验证 JdbcFenceRepository, JdbcPersonnelRepository, JdbcRuleRepository 迁移至 openGauss 后的
 * 物理表 CRUD、外键与版本子表落库、JSONB 映射与逻辑删除正确性。
 *
 * <p>安全原则：仅在环境变量 RUN_OPENGAUSS_STEP5=true 时激活，测试数据具有明确的前缀
 * {@code scs_step5_it_}，并在完成后精确删除自身测试记录，严禁影响正式数据。</p>
 */
@EnabledIfEnvironmentVariable(named = OpenGaussStep5FencePersonnelRuleIntegrationTest.ENV_STEP5_ENABLED, matches = "true")
@DisplayName("Phase 2 / Step 5: openGauss 6.0.5 真实数据库电子围栏、人员台账与安全规则集成验收测试")
public class OpenGaussStep5FencePersonnelRuleIntegrationTest extends OpenGaussSpikeSupport {

    public static final String ENV_STEP5_ENABLED = "RUN_OPENGAUSS_STEP5";

    private static final String TEST_PERSON_CODE = "scs_step5_it_p01";
    private static final String TEST_FENCE_CODE = "scs_step5_it_f01";
    private static final String TEST_RULE_CODE = "scs_step5_it_r01";

    private NamedParameterJdbcTemplate jdbcTemplate;
    private JdbcPersonnelRepository personnelRepository;
    private JdbcFenceRepository fenceRepository;
    private JdbcRuleRepository ruleRepository;

    @BeforeEach
    void setUp() {
        if (dataSource == null) {
            return;
        }
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        DemoMasterData masterData = new DemoMasterData();
        Clock clock = Clock.system(ZoneId.of("Asia/Shanghai"));
        ObjectMapper objectMapper = new ObjectMapper();

        personnelRepository = new JdbcPersonnelRepository(jdbcTemplate, masterData, clock);
        fenceRepository = new JdbcFenceRepository(jdbcTemplate, objectMapper, masterData, clock);
        ruleRepository = new JdbcRuleRepository(jdbcTemplate, objectMapper, clock);

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
            // 清理围栏子表与主表
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_fence_edge_sync WHERE fence_id IN "
                            + "(SELECT id FROM safety.safety_fence WHERE fence_code LIKE 'scs_step5_it_%')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_fence_version WHERE fence_id IN "
                            + "(SELECT id FROM safety.safety_fence WHERE fence_code LIKE 'scs_step5_it_%')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_fence WHERE fence_code LIKE 'scs_step5_it_%'")) {
                ps.executeUpdate();
            }

            // 清理规则子表与主表
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_rule_edge_sync WHERE rule_id IN "
                            + "(SELECT id FROM safety.safety_rule WHERE rule_code LIKE 'scs_step5_it_%')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_rule_area WHERE rule_id IN "
                            + "(SELECT id FROM safety.safety_rule WHERE rule_code LIKE 'scs_step5_it_%')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_rule_version WHERE rule_id IN "
                            + "(SELECT id FROM safety.safety_rule WHERE rule_code LIKE 'scs_step5_it_%')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_rule WHERE rule_code LIKE 'scs_step5_it_%'")) {
                ps.executeUpdate();
            }

            // 清理人员台账
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.safety_personnel WHERE personnel_code LIKE 'scs_step5_it_%'")) {
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("Personnel: 人员台账真实 CRUD 与单 SQL 聚合统计 getStats() 验收")
    void testPersonnelPersistenceAndStats() {
        DemoPersonnel p = new DemoPersonnel();
        p.id = TEST_PERSON_CODE;
        p.jobNo = "J-5001";
        p.name = "实机测试员";
        p.team = "装卸一班";
        p.area = "装卸区 A";
        p.braceletId = "WB-IT-01";
        p.battery = 88;
        p.statusCode = PersonStatuses.ONLINE;
        p.braceletStatusCode = BraceletStatuses.ONLINE;
        p.riskCode = PersonRiskLevels.NORMAL;
        p.x = 100.0;
        p.y = 80.0;
        p.coordinate = "A-01 / 100.0, 80.0";
        p.updatedAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        DemoPersonnel saved = personnelRepository.save(p);
        assertThat(saved).isNotNull();

        Optional<DemoPersonnel> loadedOpt = personnelRepository.findById(TEST_PERSON_CODE);
        assertThat(loadedOpt).isPresent();
        DemoPersonnel loaded = loadedOpt.get();
        assertThat(loaded.name).isEqualTo("实机测试员");
        assertThat(loaded.battery).isEqualTo(88);
        assertThat(loaded.team).isEqualTo("装卸一班");
        assertThat(loaded.area).isEqualTo("装卸区 A");

        PersonnelRepository.PersonnelStats stats = personnelRepository.getStats();
        assertThat(stats).isNotNull();
        assertThat(stats.online()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Fence: 电子围栏主表、版本表多边形 JSONB 与边缘同步真实落库与查询验收")
    void testFencePersistenceWithVersionAndNodes() {
        DemoFence fence = new DemoFence();
        fence.id = TEST_FENCE_CODE;
        fence.name = "实机测试禁区";
        fence.kind = "危险区域";
        fence.tone = "danger";
        fence.area = "装卸区 A";
        fence.version = "v1.0";
        fence.statusCode = FenceStatuses.EFFECTIVE;
        fence.effectiveAt = "2026-10-01 08:00";
        fence.expiresAt = "2026-12-31 18:00";
        fence.approver = "审核员";
        fence.bufferDistance = 4.0;
        fence.riskCode = RiskLevels.SEVERE;
        fence.polygon = List.of(new FencePoint(10.0, 10.0), new FencePoint(50.0, 10.0), new FencePoint(50.0, 50.0));
        fence.teams = "装卸一班";
        fence.nodes = List.of(new EdgeNode("EDGE-01", EdgeNode.SUCCESS));

        DemoFence saved = fenceRepository.save(fence);
        assertThat(saved).isNotNull();

        Optional<DemoFence> loadedOpt = fenceRepository.findById(TEST_FENCE_CODE);
        assertThat(loadedOpt).isPresent();
        DemoFence loaded = loadedOpt.get();
        assertThat(loaded.id).isEqualTo(TEST_FENCE_CODE);
        assertThat(loaded.name).isEqualTo("实机测试禁区");
        assertThat(loaded.polygon).hasSize(3);
        assertThat(fenceRepository.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Rule: 安全规则主表、多区域、版本与参数 JSONB 真实落库、条件筛选及逻辑删除验收")
    void testRulePersistenceFilterAndLogicalClear() {
        DemoRule rule = new DemoRule();
        rule.id = TEST_RULE_CODE;
        rule.name = "实机作业卡控规则";
        rule.category = "人员防护";
        rule.type = "视觉卡控";
        rule.scope = "装卸作业区全覆盖";
        rule.version = "v1.0";
        rule.platformVersion = "v1.0";
        rule.riskCode = RiskLevels.SEVERE;
        rule.statusCode = RuleStatuses.ACTIVE;
        rule.highRisk = true;
        rule.owner = "规则专员";
        rule.approver = "安全总监";
        rule.effectiveAt = "2026-10-01 00:00";
        rule.description = "实机规则描述";
        rule.relatedModules = List.of("AI识别");
        rule.areas = List.of("装卸区 A");
        rule.updatedAt = "2026-10-15 12:00";
        rule.params = List.of(new Param("距离门限", "3.0", true, "米"));
        rule.versions = List.of(new Version("v1.0", "2026-10-01", "初始版本", "规则专员", "已生效", List.of()));
        rule.edgeNodes = List.of(new DemoRule.EdgeNode("EDGE-01", "v1.0", "synced"));

        DemoRule saved = ruleRepository.save(rule);
        assertThat(saved).isNotNull();

        // RuleQuery 字段序为 (keyword, category, status, risk)
        RuleQuery query = new RuleQuery("实机作业", "人员防护", "已生效", "严重");
        List<DemoRule> filtered = ruleRepository.filter(query);
        assertThat(filtered.stream().anyMatch(r -> TEST_RULE_CODE.equals(r.id))).isTrue();

        // 验证 clearDemoData() 执行逻辑删除 is_deleted = true
        ruleRepository.clearDemoData();
        assertThat(ruleRepository.findById(TEST_RULE_CODE)).isEmpty();
    }
}
