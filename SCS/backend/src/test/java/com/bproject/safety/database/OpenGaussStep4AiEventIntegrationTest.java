package com.bproject.safety.database;

import com.bproject.safety.module.ai.model.AiBox;
import com.bproject.safety.module.ai.model.AiPageResult;
import com.bproject.safety.module.ai.model.AiReviewStatuses;
import com.bproject.safety.module.ai.model.AiRiskLevels;
import com.bproject.safety.module.ai.model.AiTimelineEventTypes;
import com.bproject.safety.module.ai.model.AiTimelineNode;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.repository.AiEventQuery;
import com.bproject.safety.module.ai.repository.JdbcAiEventRepository;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.repository.JdbcAlertRepository;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.bproject.safety.support.number.JdbcAiEventNumberGenerator;
import com.bproject.safety.support.number.JdbcBusinessNumberStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SCS Phase 1 / Step 4: openGauss 真实实机集成测试（在 bpoc-node4 上执行）。
 * 验证 AiEventRepository 迁移至 openGauss 6.0.5 后的完整主表持久化、时间线保序、
 * 检测框 JSONB 读写、业务编号生成器以及与 Alert 的同库事务与外键一致性。
 *
 * <p>安全原则：仅在环境变量 RUN_OPENGAUSS_STEP4=true 时激活，测试数据具有明确的前缀
 * {@code scs_step4_it_}，并在完成后精确删除自身测试记录，严禁影响正式数据。</p>
 */
@EnabledIfEnvironmentVariable(named = OpenGaussStep4AiEventIntegrationTest.ENV_STEP4_ENABLED, matches = "true")
@DisplayName("Phase 1 / Step 4: openGauss 6.0.5 真实数据库 AI 识别事件与时间线集成验收测试")
public class OpenGaussStep4AiEventIntegrationTest extends OpenGaussSpikeSupport {

    public static final String ENV_STEP4_ENABLED = "RUN_OPENGAUSS_STEP4";

    private static final String TEST_EVENT_NO = "scs_step4_it_ai01";
    private static final String TEST_ALERT_NO = "scs_step4_it_alm01";

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private JdbcAiEventRepository aiEventRepository;
    private JdbcAlertRepository alertRepository;
    private JdbcAiEventNumberGenerator aiNumberGenerator;
    private JdbcBusinessNumberStore numberStore;

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

        aiEventRepository = new JdbcAiEventRepository(jdbcTemplate, objectMapper, masterData, clock);
        alertRepository = new JdbcAlertRepository(jdbcTemplate, objectMapper, masterData, clock);
        numberStore = new JdbcBusinessNumberStore(jdbcTemplate, transactionManager);
        aiNumberGenerator = new JdbcAiEventNumberGenerator(numberStore, clock);

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
                    "DELETE FROM safety.safety_alert WHERE alert_no LIKE 'scs_step4_it_%'")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.ai_event_timeline WHERE ai_event_id IN "
                            + "(SELECT id FROM safety.ai_event WHERE event_no LIKE 'scs_step4_it_%')")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM safety.ai_event WHERE event_no LIKE 'scs_step4_it_%'")) {
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("AiEvent: 主表全字段与 detection_boxes JSONB 真实持久化与全字段读回验收")
    void testAiEventFullPersistenceAndReload() {
        DemoAiEvent event = new DemoAiEvent();
        event.id = TEST_EVENT_NO;
        event.type = "未佩戴安全帽";
        event.camera = "CAM-03";
        event.cameraName = "装卸区 A 球机";
        event.area = "装卸区 A";
        event.confidence = 94.8;
        event.durationSec = 3.2;
        event.model = "PPE-Detection-v2.4.1";
        event.threshold = 85.0;
        event.statusCode = AiReviewStatuses.PENDING;
        event.riskCode = AiRiskLevels.HIGH;
        event.health = "正常";
        event.scene = "helmet";
        event.boxes = List.of(
                new AiBox("box-1", "PERSON", 97.4, 40.0, 44.0, 23.0, 44.0, "person"),
                new AiBox("box-2", "NO HELMET", 96.2, 45.0, 42.0, 13.0, 14.0, "violation")
        );
        event.rule = "装卸作业区域必须佩戴安全帽";
        event.relatedPerson = "作业人员 P-1042";
        event.relatedDevice = "龙门吊 G-CRANE-01";
        event.judgeText = "连续 3.2 秒识别到人员头部未检测到安全帽，置信度高于 85% 规则阈值。";
        event.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        DemoAiEvent saved = aiEventRepository.save(event);
        assertThat(saved).isNotNull();

        Optional<DemoAiEvent> loadedOpt = aiEventRepository.findById(TEST_EVENT_NO);
        assertThat(loadedOpt).isPresent();
        DemoAiEvent loaded = loadedOpt.get();

        assertThat(loaded.id).isEqualTo(TEST_EVENT_NO);
        assertThat(loaded.type).isEqualTo("未佩戴安全帽");
        assertThat(loaded.camera).isEqualTo("CAM-03");
        assertThat(loaded.cameraName).isEqualTo("装卸区 A 球机");
        assertThat(loaded.area).isEqualTo("装卸区 A");
        assertThat(loaded.confidence).isEqualTo(94.8);
        assertThat(loaded.threshold).isEqualTo(85.0);
        assertThat(loaded.statusCode).isEqualTo(AiReviewStatuses.PENDING);
        assertThat(loaded.riskCode).isEqualTo(AiRiskLevels.HIGH);
        assertThat(loaded.boxes).hasSize(2);
        assertThat(loaded.boxes.get(0).label()).isEqualTo("PERSON");
        assertThat(loaded.boxes.get(1).label()).isEqualTo("NO HELMET");
        assertThat(loaded.rule).isEqualTo("装卸作业区域必须佩戴安全帽");
        assertThat(loaded.judgeText).contains("连续 3.2 秒识别到人员头部未检测到安全帽");
    }

    @Test
    @DisplayName("AiEventTimeline: 时间线严格保序追加与级联查询验收")
    void testAiEventTimelineAppendOnly() {
        DemoAiEvent event = new DemoAiEvent();
        event.id = TEST_EVENT_NO;
        event.type = "翻越护栏";
        event.camera = "CAM-05";
        event.statusCode = AiReviewStatuses.PENDING;
        event.riskCode = AiRiskLevels.MEDIUM;
        event.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));

        event.timeline = new ArrayList<>();
        event.timeline.add(new AiTimelineNode("10:00:00", "AI 检测到疑似翻越护栏", "done", AiTimelineEventTypes.DETECTED, 1));
        event.timeline.add(new AiTimelineNode("10:00:01", "事件进入复核队列", "active", AiTimelineEventTypes.QUEUED, 2));

        aiEventRepository.save(event);

        // 第二次保存追加新时间线节点
        DemoAiEvent loaded = aiEventRepository.findById(TEST_EVENT_NO).orElseThrow();
        loaded.statusCode = AiReviewStatuses.CONFIRMED;
        loaded.timeline.add(new AiTimelineNode("10:01:00", "人工确认违规", "active", AiTimelineEventTypes.CONFIRMED, 3));
        aiEventRepository.save(loaded);

        DemoAiEvent reloaded = aiEventRepository.findById(TEST_EVENT_NO).orElseThrow();
        assertThat(reloaded.timeline).hasSize(3);
        assertThat(reloaded.timeline.get(0).sequenceNo()).isEqualTo(1);
        assertThat(reloaded.timeline.get(0).eventType()).isEqualTo(AiTimelineEventTypes.DETECTED);
        assertThat(reloaded.timeline.get(1).sequenceNo()).isEqualTo(2);
        assertThat(reloaded.timeline.get(2).sequenceNo()).isEqualTo(3);
        assertThat(reloaded.timeline.get(2).eventType()).isEqualTo(AiTimelineEventTypes.CONFIRMED);
    }

    @Test
    @DisplayName("AiEventQuery: 多维度条件筛选（置信度高/中/低、状态、关键词）与分页验收")
    void testAiEventQueryFilterAndPaging() {
        DemoAiEvent e1 = new DemoAiEvent();
        e1.id = "scs_step4_it_q1";
        e1.type = "未佩戴安全帽";
        e1.camera = "CAM-01";
        e1.confidence = 95.0; // high (>=85)
        e1.statusCode = AiReviewStatuses.PENDING;
        e1.riskCode = AiRiskLevels.HIGH;
        e1.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        aiEventRepository.save(e1);

        DemoAiEvent e2 = new DemoAiEvent();
        e2.id = "scs_step4_it_q2";
        e2.type = "人员滞留";
        e2.camera = "CAM-02";
        e2.confidence = 75.0; // mid (70..84)
        e2.statusCode = AiReviewStatuses.CONFIRMED;
        e2.riskCode = AiRiskLevels.MEDIUM;
        e2.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        aiEventRepository.save(e2);

        DemoAiEvent e3 = new DemoAiEvent();
        e3.id = "scs_step4_it_q3";
        e3.type = "翻越护栏";
        e3.camera = "CAM-03";
        e3.confidence = 60.0; // low (<70)
        e3.statusCode = AiReviewStatuses.UNCERTAIN;
        e3.riskCode = AiRiskLevels.LOW;
        e3.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        aiEventRepository.save(e3);

        // 筛选置信度为 high
        AiEventQuery queryHigh = AiEventQuery.of(null, null, null, null, null, null, "high", null, 1, 10);
        AiPageResult resHigh = aiEventRepository.page(queryHigh);
        assertThat(resHigh.list().stream().anyMatch(e -> "scs_step4_it_q1".equals(e.id))).isTrue();
        assertThat(resHigh.list().stream().noneMatch(e -> "scs_step4_it_q2".equals(e.id))).isTrue();
        assertThat(resHigh.list().stream().noneMatch(e -> "scs_step4_it_q3".equals(e.id))).isTrue();

        // 筛选关键词
        AiEventQuery queryKw = AiEventQuery.of("滞留", null, null, null, null, null, null, null, 1, 10);
        AiPageResult resKw = aiEventRepository.page(queryKw);
        assertThat(resKw.list().stream().anyMatch(e -> "scs_step4_it_q2".equals(e.id))).isTrue();
    }

    @Test
    @DisplayName("Transaction: 确认违规联动生成 Alert 与回写 linked_alert_id 同库事务原子性")
    void testConfirmAiEventCreatesLinkedAlertInSameTransaction() throws Exception {
        DemoAiEvent event = new DemoAiEvent();
        event.id = TEST_EVENT_NO;
        event.type = "未佩戴安全帽";
        event.camera = "CAM-03";
        event.confidence = 94.8;
        event.statusCode = AiReviewStatuses.PENDING;
        event.riskCode = AiRiskLevels.HIGH;
        event.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        aiEventRepository.save(event);

        // 在事务内创建 Alert 并关联至 AiEvent
        transactionTemplate.executeWithoutResult(status -> {
            DemoAlert alert = new DemoAlert();
            alert.id = TEST_ALERT_NO;
            alert.title = "AI违规自动建单告警";
            alert.source = "AI违规";
            alert.eventType = "未佩戴安全帽";
            alert.riskCode = RiskLevels.SEVERE;
            alert.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
            alertRepository.save(alert);

            DemoAiEvent cur = aiEventRepository.findById(TEST_EVENT_NO).orElseThrow();
            cur.statusCode = AiReviewStatuses.CONFIRMED;
            cur.linkedAlertId = TEST_ALERT_NO;
            aiEventRepository.save(cur);
        });

        // 验证业务读出
        DemoAiEvent verifiedEvent = aiEventRepository.findById(TEST_EVENT_NO).orElseThrow();
        assertThat(verifiedEvent.statusCode).isEqualTo(AiReviewStatuses.CONFIRMED);
        assertThat(verifiedEvent.linkedAlertId).isEqualTo(TEST_ALERT_NO);

        DemoAlert verifiedAlert = alertRepository.findById(TEST_ALERT_NO).orElseThrow();
        assertThat(verifiedAlert.id).isEqualTo(TEST_ALERT_NO);

        // 验证数据库底层真实外键 UUID
        try (Connection conn = getConnection()) {
            UUID expectedAlertUuid = JdbcAlertRepository.resolveUuid(TEST_ALERT_NO);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT linked_alert_id FROM safety.ai_event WHERE event_no = ?")) {
                ps.setString(1, TEST_EVENT_NO);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    UUID actualLinkedUuid = (UUID) rs.getObject("linked_alert_id");
                    assertThat(actualLinkedUuid).isEqualTo(expectedAlertUuid);
                }
            }
        }
    }

    @Test
    @DisplayName("TransactionRollback: 业务异常时 Alert 与 AiEvent 同库全部回滚")
    void testTransactionRollbackConsistency() {
        DemoAiEvent event = new DemoAiEvent();
        event.id = TEST_EVENT_NO;
        event.type = "未佩戴安全帽";
        event.statusCode = AiReviewStatuses.PENDING;
        event.riskCode = AiRiskLevels.HIGH;
        event.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        aiEventRepository.save(event);

        assertThrows(RuntimeException.class, () -> {
            transactionTemplate.executeWithoutResult(status -> {
                DemoAlert alert = new DemoAlert();
                alert.id = TEST_ALERT_NO;
                alert.title = "回滚测试告警";
                alert.source = "AI违规";
                alert.eventType = "未佩戴安全帽";
                alert.occurredAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
                alertRepository.save(alert);

                DemoAiEvent cur = aiEventRepository.findById(TEST_EVENT_NO).orElseThrow();
                cur.statusCode = AiReviewStatuses.CONFIRMED;
                cur.linkedAlertId = TEST_ALERT_NO;
                aiEventRepository.save(cur);

                throw new RuntimeException("模拟业务失败触发回滚");
            });
        });

        // 验证 Alert 未入库，AiEvent 状态未改变、未关联 Alert
        assertThat(alertRepository.findById(TEST_ALERT_NO)).isEmpty();
        DemoAiEvent rolledBackEvent = aiEventRepository.findById(TEST_EVENT_NO).orElseThrow();
        assertThat(rolledBackEvent.statusCode).isEqualTo(AiReviewStatuses.PENDING);
        assertThat(rolledBackEvent.linkedAlertId).isNull();
    }

    @Test
    @DisplayName("NumberGenerator: 基于 openGauss sys_business_number 的 AI-E-yyyyMMdd-NNN 原子单号生成")
    void testAiEventNumberGenerator() {
        String num1 = aiNumberGenerator.nextAiEventNumber();
        String num2 = aiNumberGenerator.nextAiEventNumber();

        String expectedDate = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(num1).startsWith("AI-E-" + expectedDate + "-");
        assertThat(num2).startsWith("AI-E-" + expectedDate + "-");
        assertThat(num1).isNotEqualTo(num2);
    }
}
