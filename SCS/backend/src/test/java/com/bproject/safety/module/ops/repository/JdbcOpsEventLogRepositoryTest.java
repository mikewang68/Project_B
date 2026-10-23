package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.OpsEventLog;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Step 6: JdbcOpsEventLogRepository 单元与过滤语义测试")
class JdbcOpsEventLogRepositoryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private NamedParameterJdbcTemplate jdbcTemplate;
    private JdbcOpsEventLogRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        repository = new JdbcOpsEventLogRepository(jdbcTemplate);
    }

    @Test
    @DisplayName("append: INSERT RETURNING 主键回填，occurred_at 取自 ts")
    void testAppendReturnsGeneratedId() {
        when(jdbcTemplate.queryForObject(anyString(), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(42L);

        OffsetDateTime ts = OffsetDateTime.now(ZONE);
        OpsEventLog log = new OpsEventLog("OPS-LOG-123", ts, "10:00:00",
                "EDGE-01", "warning", OpsEventLog.NODE_OFFLINE, "节点心跳超时");

        OpsEventLog appended = repository.append(log);

        assertThat(appended.id).isEqualTo("42");
        assertThat(appended.text).isEqualTo("节点心跳超时");

        org.mockito.ArgumentCaptor<MapSqlParameterSource> captor =
                org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).queryForObject(anyString(), captor.capture(), any(Class.class));
        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("nodeId")).isEqualTo("EDGE-01");
        assertThat(params.getValue("level")).isEqualTo("warning");
        assertThat(params.getValue("type")).isEqualTo(OpsEventLog.NODE_OFFLINE);
        assertThat(params.getValue("text")).isEqualTo("节点心跳超时");
        assertThat(params.getValue("occurredAt")).isEqualTo(Timestamp.from(ts.toInstant()));
    }

    @Test
    @DisplayName("append: ts 为空立即失败（occurred_at 列 NOT NULL）")
    void testAppendRejectsNullTimestamp() {
        OpsEventLog log = new OpsEventLog("OPS-LOG-1", null, null, "EDGE-01", "info",
                OpsEventLog.NODE_ONLINE, "text");
        assertThatThrownBy(() -> repository.append(log))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("recent: limit 归一化（null/0 → 50，超界 → 300）")
    void testRecentLimitNormalization() {
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        repository.recent(null, null, null, null, null, null);
        repository.recent(0, null, null, null, null, null);
        repository.recent(9999, null, null, null, null, null);

        org.mockito.ArgumentCaptor<MapSqlParameterSource> captor =
                org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate, times(3)).query(anyString(), captor.capture(), any(RowMapper.class));
        assertThat(captor.getAllValues().get(0).getValue("maxRows")).isEqualTo(50);
        assertThat(captor.getAllValues().get(1).getValue("maxRows")).isEqualTo(50);
        assertThat(captor.getAllValues().get(2).getValue("maxRows")).isEqualTo(300);
    }

    @Test
    @DisplayName("recent: 空白过滤归一化为不过滤，合法 ISO 时间解析绑定、非法串静默忽略")
    void testRecentFilterNormalization() {
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        OffsetDateTime from = OffsetDateTime.parse("2026-10-24T08:00:00+08:00");
        repository.recent(10, "  ", "", null, "2026-10-24T08:00:00+08:00", "not-a-date");

        org.mockito.ArgumentCaptor<MapSqlParameterSource> captor =
                org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(anyString(), captor.capture(), any(RowMapper.class));
        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("nodeId")).isNull();
        assertThat(params.getValue("level")).isNull();
        assertThat(params.getValue("type")).isNull();
        assertThat(params.getValue("fromAt")).isEqualTo(Timestamp.from(from.toInstant()));
        assertThat(params.getValue("toAt")).isNull();
    }

    @Test
    @DisplayName("RowMapper: id 数值串化、time 由 occurred_at 重新生成")
    @SuppressWarnings("unchecked")
    void testRowMapper() throws Exception {
        OffsetDateTime ts = OffsetDateTime.parse("2026-10-24T15:30:05+08:00");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(7L);
        when(rs.wasNull()).thenReturn(false);
        when(rs.getString("edge_node_code")).thenReturn("EDGE-02");
        when(rs.getString("log_level")).thenReturn("warning");
        when(rs.getString("event_type")).thenReturn(OpsEventLog.REPLAY_SUCCESS);
        when(rs.getString("message")).thenReturn("补传成功 3 条");
        when(rs.getTimestamp("occurred_at")).thenReturn(Timestamp.from(ts.toInstant()));

        org.mockito.ArgumentCaptor<RowMapper<OpsEventLog>> mapperCaptor =
                org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), mapperCaptor.capture()))
                .thenReturn(List.of());

        repository.recent(10, null, null, null, null, null);

        OpsEventLog log = mapperCaptor.getValue().mapRow(rs, 0);
        assertThat(log.id).isEqualTo("7");
        assertThat(log.nodeId).isEqualTo("EDGE-02");
        assertThat(log.level).isEqualTo("warning");
        assertThat(log.type).isEqualTo(OpsEventLog.REPLAY_SUCCESS);
        assertThat(log.text).isEqualTo("补传成功 3 条");
        assertThat(log.ts).isEqualTo(ts.withOffsetSameInstant(ts.getOffset()));
        assertThat(log.time).isEqualTo("15:30:05");
    }
}
