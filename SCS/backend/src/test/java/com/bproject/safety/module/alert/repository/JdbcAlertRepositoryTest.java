package com.bproject.safety.module.alert.repository;

import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.LinkageStep;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.model.TimelineEvent;
import com.bproject.safety.module.alert.model.TreatmentRecord;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Step 2: JdbcAlertRepository 单元与聚合逻辑测试")
class JdbcAlertRepositoryTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private DemoMasterData masterData;
    private Clock clock;
    private JdbcAlertRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        objectMapper = new ObjectMapper();
        masterData = new DemoMasterData();
        clock = Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        repository = new JdbcAlertRepository(jdbcTemplate, objectMapper, masterData, clock);
    }

    @Test
    @DisplayName("resolveUuid: ALM 业务编号生成确定性 UUID，标准 UUID 字符串直接解析")
    void testResolveUuid() {
        String alertNo = "ALM-20260904-001";
        UUID u1 = JdbcAlertRepository.resolveUuid(alertNo);
        UUID u2 = JdbcAlertRepository.resolveUuid(alertNo);
        assertThat(u1).isEqualTo(u2);
        assertThat(u1.version()).isEqualTo(3); // MD5 name-based UUID

        String randomUuidStr = UUID.randomUUID().toString();
        UUID parsed = JdbcAlertRepository.resolveUuid(randomUuidStr);
        assertThat(parsed.toString()).isEqualTo(randomUuidStr);
    }

    @Test
    @DisplayName("save: 保存聚合根时正确插入 safety.safety_alert 并在存在时更新")
    void testSaveRootAlert() {
        DemoAlert alert = new DemoAlert();
        alert.id = "ALM-20260922-001";
        alert.title = "人员闯入测试告警";
        alert.riskCode = RiskLevels.SEVERE;
        alert.statusCode = AlertStatuses.PENDING_CONFIRM;
        alert.area = "装卸区 A";
        alert.occurredAt = OffsetDateTime.now(clock);
        // 模拟第一次保存：不存在已有的记录
        when(jdbcTemplate.query(anyString(), any(Map.class), any(RowMapper.class))).thenReturn(List.of());

        DemoAlert saved = repository.save(alert);
        assertThat(saved.id).isEqualTo(alert.id);
        assertThat(saved.title).isEqualTo(alert.title);

        ArgumentCaptor<MapSqlParameterSource> paramCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), paramCaptor.capture());

        boolean hasInsertAlert = paramCaptor.getAllValues().stream()
                .anyMatch(p -> "ALM-20260922-001".equals(p.getValue("alertNo")));
        assertThat(hasInsertAlert).isTrue();
    }

    @Test
    @DisplayName("appendTimelineEvents: 严格 append-only，已存在的 sequence_no 不得重复插入")
    void testTimelineAppendOnly() {
        DemoAlert alert = new DemoAlert();
        alert.id = "ALM-20260922-002";
        alert.title = "时间线审计测试";
        alert.timeline = List.of(
                TimelineEvent.of("12:00:00", OffsetDateTime.now(clock), "节点1", "done", "CREATED", 1),
                TimelineEvent.of("12:01:00", OffsetDateTime.now(clock), "节点2", "active", "STARTED", 2)
        );

        // 模拟已存在 seq=1
        when(jdbcTemplate.query(eq("SELECT sequence_no FROM safety.safety_alert_timeline WHERE alert_id = :alertId"),
                any(Map.class), any(RowMapper.class))).thenReturn(List.of(1));

        repository.save(alert);

        ArgumentCaptor<MapSqlParameterSource> paramCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), paramCaptor.capture());

        // 检查插入 timeline 的参数：应仅插入 seq=2，不应插入 seq=1
        List<MapSqlParameterSource> timelineInserts = paramCaptor.getAllValues().stream()
                .filter(p -> p.hasValue("sequenceNo"))
                .toList();

        assertThat(timelineInserts).hasSize(1);
        assertThat(timelineInserts.get(0).getValue("sequenceNo")).isEqualTo(2);
        assertThat(timelineInserts.get(0).getValue("message")).isEqualTo("节点2");
    }

    @Test
    @DisplayName("copy-on-read & copy-on-write: 仓储返回副本修改不影响原始对象")
    void testCopySemantics() {
        DemoAlert alert = new DemoAlert();
        alert.id = "ALM-20260922-003";
        alert.title = "不可变语义测试";
        alert.linkage = List.of(new LinkageStep("step-1", "声光报警", "wait", "等待执行", null));

        DemoAlert saved1 = repository.save(alert);
        saved1.title = "被外部篡改的标题";
        saved1.linkage.get(0).withState("success", "已执行", null);

        assertThat(alert.title).isEqualTo("不可变语义测试");
    }
}
