package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.alert.repository.JdbcAlertRepository;
import com.bproject.safety.module.ops.model.EdgeLocalLinkage;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Step 6: JdbcEdgeEventQueueRepository 单元与持久化映射测试")
class JdbcEdgeEventQueueRepositoryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private NamedParameterJdbcTemplate jdbcTemplate;
    private JdbcEdgeEventQueueRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        repository = new JdbcEdgeEventQueueRepository(jdbcTemplate, new ObjectMapper());
    }

    private EdgePendingEvent sampleEvent() {
        EdgePendingEvent e = new EdgePendingEvent();
        e.eventId = "EDGE-EVT-20261024-01";
        e.edgeNodeId = "EDGE-03";
        e.eventType = "person-intrusion";
        e.businessKey = "EDGE-03:person-intrusion:1";
        e.edgeOccurredAt = OffsetDateTime.now(ZONE);
        e.receivedAt = OffsetDateTime.now(ZONE);
        e.idempotencyKey = "EDGE-03:person-intrusion:1798000000:1";
        e.clockOffsetAtOccurrence = 1200L;
        e.ruleVersionUsed = "RULE-B5-04";
        e.risk = "严重";
        e.payloadSummary = new EdgePendingEvent.PayloadSummary(
                "人员闯入龙门吊作业盲区", "装卸区 A", "作业人员 P-1042",
                "FENCE-03", null, "严重", e.businessKey, "距离 3.6m");
        e.localLinkage = EdgeLocalLinkage.success(java.util.List.of("现场声光报警已启动"), true);
        e.status = PendingEventStatuses.PENDING;
        e.retryCount = 0;
        e.linkedAlertId = "ALM-20261024-001";
        e.syncDelaySec = null;
        return e;
    }

    @Test
    @DisplayName("save 首次保存: UPDATE 未命中走 INSERT，行主键由幂等键派生、JSONB/UUID 参数正确")
    void testSaveInsertPath() {
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);

        EdgePendingEvent saved = repository.save(sampleEvent());

        assertThat(saved.eventId).isEqualTo("EDGE-EVT-20261024-01");
        verify(jdbcTemplate, times(2)).update(anyString(), any(MapSqlParameterSource.class));

        org.mockito.ArgumentCaptor<MapSqlParameterSource> captor =
                org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate, times(2)).update(anyString(), captor.capture());
        MapSqlParameterSource insertParams = captor.getAllValues().get(1);

        assertThat(insertParams.getValue("id"))
                .isEqualTo(JdbcAlertRepository.resolveUuid("EDGE-03:person-intrusion:1798000000:1"));
        assertThat(insertParams.getValue("eventId")).isEqualTo("EDGE-EVT-20261024-01");
        assertThat(insertParams.getValue("edgeNodeCode")).isEqualTo("EDGE-03");
        assertThat(insertParams.getValue("riskLevelCode")).isEqualTo("严重");
        assertThat(insertParams.getValue("statusCode")).isEqualTo(PendingEventStatuses.PENDING);
        assertThat(insertParams.getValue("linkedAlertId"))
                .isEqualTo(JdbcAlertRepository.resolveUuid("ALM-20261024-001"));
        assertThat(insertParams.getValue("clockOffsetAtOccurrence")).isEqualTo(1200L);
        assertThat(insertParams.getValue("syncDelaySec")).isNull();

        PGobject payload = (PGobject) insertParams.getValue("payloadSummary");
        assertThat(payload.getType()).isEqualTo("jsonb");
        assertThat(payload.getValue()).contains("人员闯入龙门吊作业盲区");

        PGobject linkage = (PGobject) insertParams.getValue("localLinkage");
        assertThat(linkage.getType()).isEqualTo("jsonb");
        assertThat(linkage.getValue()).contains("现场声光报警已启动");
    }

    @Test
    @DisplayName("save 已存在: UPDATE 命中则不再 INSERT")
    void testSaveUpdatePath() {
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        repository.save(sampleEvent());

        verify(jdbcTemplate, times(1)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("save 并发竞态: INSERT 撞唯一键后退化为 UPDATE 且命中，保存成功")
    void testSaveConcurrentInsertRace() {
        AtomicInteger calls = new AtomicInteger();
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return 0; // 首次 UPDATE 未命中
            }
            if (n == 2) {
                throw new DuplicateKeyException("并发插入竞态");
            }
            return 1; // 退化为 UPDATE 命中既有行
        });

        EdgePendingEvent saved = repository.save(sampleEvent());

        assertThat(saved).isNotNull();
        verify(jdbcTemplate, times(3)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("save 异常数据: 同节点同 eventId 换幂等键时如实上抛唯一键冲突")
    void testSaveDuplicateKeyOnDistinctIdempotencyKey() {
        AtomicInteger calls = new AtomicInteger();
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenAnswer(inv -> {
            int n = calls.incrementAndGet();
            if (n == 2) {
                throw new DuplicateKeyException("uq_edge_pending_event_node_id");
            }
            return 0; // 两次 UPDATE 均未命中
        });

        assertThatThrownBy(() -> repository.save(sampleEvent()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("save 副本语义: 返回值与入参不共享可变子对象")
    void testSaveCopySemantics() {
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        EdgePendingEvent input = sampleEvent();
        EdgePendingEvent saved = repository.save(input);
        assertThat(saved.payloadSummary).isNotSameAs(input.payloadSummary);
        assertThat(saved.localLinkage).isNotSameAs(input.localLinkage);
        assertThat(saved.payloadSummary.title).isEqualTo(input.payloadSummary.title);
    }

    @Test
    @DisplayName("RowMapper: 全字段读回，JSONB 反序列化、alert_no 反查优先、null 语义保真")
    @SuppressWarnings("unchecked")
    void testRowMapperFullFields() throws Exception {
        Timestamp occurredAt = Timestamp.from(OffsetDateTime.now(ZONE).toInstant());

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("event_id")).thenReturn("EDGE-EVT-20261024-01");
        when(rs.getString("edge_node_code")).thenReturn("EDGE-03");
        when(rs.getString("event_type")).thenReturn("person-intrusion");
        when(rs.getString("business_key")).thenReturn("EDGE-03:person-intrusion:1");
        when(rs.getTimestamp("edge_occurred_at")).thenReturn(occurredAt);
        when(rs.getTimestamp("received_at")).thenReturn(occurredAt);
        when(rs.getTimestamp("server_received_at")).thenReturn(null);
        when(rs.getTimestamp("synced_at")).thenReturn(null);
        when(rs.getString("idempotency_key")).thenReturn("EDGE-03:person-intrusion:1798000000:1");
        when(rs.getLong("clock_offset_at_occurrence")).thenReturn(1200L);
        when(rs.getString("rule_version_used")).thenReturn("RULE-B5-04");
        when(rs.getString("risk_level_code")).thenReturn("严重");
        when(rs.getString("payload_summary"))
                .thenReturn("{\"title\":\"人员闯入龙门吊作业盲区\",\"person\":\"作业人员 P-1042\"}");
        when(rs.getString("local_linkage")).thenReturn("null"); // jsonb 字面量 null → 还原为 null
        when(rs.getString("status_code")).thenReturn(PendingEventStatuses.SYNCED);
        when(rs.getInt("retry_count")).thenReturn(1);
        when(rs.getTimestamp("last_retry_at")).thenReturn(null);
        when(rs.getString("last_error")).thenReturn(null);
        when(rs.getString("linked_alert_no")).thenReturn("ALM-20261024-001");
        when(rs.getObject("linked_alert_id"))
                .thenReturn(JdbcAlertRepository.resolveUuid("ALM-20261024-001"));
        when(rs.getLong("sync_delay_sec")).thenReturn(0L);
        // 两次 wasNull 调用依次对应 clock_offset_at_occurrence(非空) 与 sync_delay_sec(空)
        when(rs.wasNull()).thenReturn(false, true);

        org.mockito.ArgumentCaptor<RowMapper<EdgePendingEvent>> mapperCaptor =
                org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), anyMap(), mapperCaptor.capture()))
                .thenReturn(java.util.List.of());

        repository.findAll();

        EdgePendingEvent e = mapperCaptor.getValue().mapRow(rs, 0);
        assertThat(e.eventId).isEqualTo("EDGE-EVT-20261024-01");
        assertThat(e.edgeNodeId).isEqualTo("EDGE-03");
        assertThat(e.risk).isEqualTo("严重");
        assertThat(e.status).isEqualTo(PendingEventStatuses.SYNCED);
        assertThat(e.retryCount).isEqualTo(1);
        assertThat(e.clockOffsetAtOccurrence).isEqualTo(1200L);
        assertThat(e.syncDelaySec).isNull();
        assertThat(e.serverReceivedAt).isNull();
        assertThat(e.edgeOccurredAt).isEqualTo(occurredAt.toInstant().atZone(ZONE).toOffsetDateTime());
        assertThat(e.payloadSummary).isNotNull();
        assertThat(e.payloadSummary.title).isEqualTo("人员闯入龙门吊作业盲区");
        assertThat(e.payloadSummary.person).isEqualTo("作业人员 P-1042");
        assertThat(e.localLinkage).isNull();
        // linked_alert_no 反查优先于 UUID 字符串
        assertThat(e.linkedAlertId).isEqualTo("ALM-20261024-001");
    }

    @Test
    @DisplayName("findByIdempotencyKey: 无匹配返回 empty，空白键直接短路")
    void testFindByIdempotencyKeyNotFound() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), any(RowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(repository.findByIdempotencyKey("missing-key")).isEmpty();
        assertThat(repository.findByIdempotencyKey(null)).isEmpty();
        assertThat(repository.findByIdempotencyKey("  ")).isEmpty();
        verify(jdbcTemplate, times(1)).queryForObject(anyString(), anyMap(), any(RowMapper.class));
    }

    @Test
    @DisplayName("findByIdempotencyKey: 命中返回防御性副本")
    @SuppressWarnings("unchecked")
    void testFindByIdempotencyKeyCopyOnRead() {
        EdgePendingEvent row = sampleEvent();
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), any(RowMapper.class))).thenReturn(row);

        Optional<EdgePendingEvent> found = repository.findByIdempotencyKey(row.idempotencyKey);

        assertThat(found).isPresent();
        assertThat(found.get()).isNotSameAs(row);
        assertThat(found.get().payloadSummary).isNotSameAs(row.payloadSummary);
    }

    @Test
    @DisplayName("行主键派生: 幂等键 → 确定性 MD5 UUID，跨实例重启稳定")
    void testDeterministicRowId() {
        UUID key = UUID.nameUUIDFromBytes(
                "EDGE-03:person-intrusion:1798000000:1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(JdbcAlertRepository.resolveUuid("EDGE-03:person-intrusion:1798000000:1"))
                .isEqualTo(key)
                .isEqualTo(JdbcAlertRepository.resolveUuid("EDGE-03:person-intrusion:1798000000:1"));
    }

    @Test
    @DisplayName("pendingCount: 空白节点串不过滤的口径与内存实现保持一致（仅 null 免过滤）")
    void testPendingCountNullSemantics() {
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(7);

        assertThat(repository.pendingCount(null)).isEqualTo(7);
        assertThat(repository.pendingCount("EDGE-01")).isEqualTo(7);

        org.mockito.ArgumentCaptor<MapSqlParameterSource> captor =
                org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate, times(2)).queryForObject(anyString(), captor.capture(), any(Class.class));
        assertThat(captor.getAllValues().get(0).getValue("nodeId")).isNull();
        assertThat(captor.getAllValues().get(1).getValue("nodeId")).isEqualTo("EDGE-01");
    }

    @Test
    @DisplayName("query 过滤参数: null 与空白串归一化为不过滤")
    void testQueryFilterNormalization() {
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(java.util.List.of());

        repository.query("  ", null);
        repository.query("EDGE-01", "FAILED");

        org.mockito.ArgumentCaptor<MapSqlParameterSource> captor =
                org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate, times(2)).query(anyString(), captor.capture(), any(RowMapper.class));
        MapSqlParameterSource first = captor.getAllValues().get(0);
        MapSqlParameterSource second = captor.getAllValues().get(1);
        assertThat(first.getValue("nodeId")).isNull();
        assertThat(first.getValue("status")).isNull();
        assertThat(second.getValue("nodeId")).isEqualTo("EDGE-01");
        assertThat(second.getValue("status")).isEqualTo("FAILED");
    }
}
