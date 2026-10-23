package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.alert.repository.JdbcAlertRepository;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.EdgeLocalLinkage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 openGauss (PostgreSQL 兼容模式) 的边缘离线事件队列持久化仓储实现。
 * 对应物理表 {@code safety.edge_pending_event}。
 *
 * <p>行主键 {@code id} 由 {@code idempotencyKey} 经 {@link JdbcAlertRepository#resolveUuid}
 * 确定性派生（MD5 name-based UUID），与 {@code uq_edge_pending_event_idempotency} 唯一约束
 * 双重保证：同一幂等键的重复补传在数据库层收敛到同一行，进程重启后队列完整——
 * 兑现"断网不瘫痪、恢复后幂等回传"的存储承诺。{@code linked_alert_id} 与
 * {@code safety_alert.id} 使用同一派生规则，读回时 LEFT JOIN 反查 {@code alert_no}
 * 还原业务编号。</p>
 *
 * <p>遵循 copy-on-read / copy-on-write 语义，提供与 InMemory 实现一致的表现。
 * 表上挂有 {@code fn_prevent_physical_delete} 触发器，本实现不提供物理删除，
 * 亦不实现 DemoClearableStore（正式库表无清场能力，Demo 清空仅存在于内存实现）。
 * 模型字段 {@code failNextReplay} 为 transient 演示标记，不持久化。
 * {@code payload_summary} 列 NOT NULL：null 摘要以 jsonb 字面量 {@code 'null'} 落库
 * 并原样读回为 null，保持与内存实现一致的空值语义。</p>
 */
@Repository
@Profile("server")
public class JdbcEdgeEventQueueRepository implements EdgeEventQueueRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 补传顺序契约（接口 queueOrder()）：edge_occurred_at 升序（NULL 最后），再按 event_id 升序。 */
    private static final String QUEUE_ORDER = " ORDER BY edge_occurred_at ASC NULLS LAST, event_id ASC";

    private static final String BASE_SELECT = """
            SELECT q.id, q.event_id, q.edge_node_code, q.event_type, q.business_key,
                   q.edge_occurred_at, q.received_at, q.server_received_at, q.synced_at,
                   q.idempotency_key, q.clock_offset_at_occurrence, q.rule_version_used,
                   q.risk_level_code, q.payload_summary, q.local_linkage,
                   q.status_code, q.retry_count, q.last_retry_at, q.last_error,
                   q.linked_alert_id, q.sync_delay_sec,
                   sa.alert_no AS linked_alert_no
            FROM safety.edge_pending_event q
            LEFT JOIN safety.safety_alert sa ON q.linked_alert_id = sa.id
            """;

    private static final String SELECT_BY_EVENT_ID =
            BASE_SELECT + " WHERE q.event_id = :eventId" + QUEUE_ORDER + " LIMIT 1";

    private static final String SELECT_BY_IDEMPOTENCY_KEY =
            BASE_SELECT + " WHERE q.idempotency_key = :idempotencyKey LIMIT 1";

    private static final String SELECT_QUERY = BASE_SELECT + """
             WHERE (:nodeId IS NULL OR :nodeId = '' OR q.edge_node_code = :nodeId)
               AND (:status IS NULL OR :status = '' OR q.status_code = :status)
            """ + QUEUE_ORDER;

    private static final String SELECT_PENDING_FOR_REPLAY = BASE_SELECT + """
             WHERE (:nodeId IS NULL OR :nodeId = '' OR q.edge_node_code = :nodeId)
               AND q.status_code IN ('PENDING', 'FAILED')
            """ + QUEUE_ORDER;

    private static final String SELECT_ALL = BASE_SELECT + QUEUE_ORDER;

    private static final String COUNT_PENDING = """
            SELECT count(*) FROM safety.edge_pending_event
            WHERE (:nodeId IS NULL OR edge_node_code = :nodeId)
              AND status_code IN ('PENDING', 'SYNCING', 'FAILED')
            """;

    private static final String COUNT_ALL = "SELECT count(*) FROM safety.edge_pending_event";

    private static final String UPDATE_EVENT = """
            UPDATE safety.edge_pending_event SET
                event_type = :eventType,
                business_key = :businessKey,
                edge_occurred_at = :edgeOccurredAt,
                received_at = :receivedAt,
                server_received_at = :serverReceivedAt,
                synced_at = :syncedAt,
                clock_offset_at_occurrence = :clockOffsetAtOccurrence,
                rule_version_used = :ruleVersionUsed,
                risk_level_code = :riskLevelCode,
                payload_summary = :payloadSummary,
                local_linkage = :localLinkage,
                status_code = :statusCode,
                retry_count = :retryCount,
                last_retry_at = :lastRetryAt,
                last_error = :lastError,
                linked_alert_id = :linkedAlertId,
                sync_delay_sec = :syncDelaySec
            WHERE id = :id
            """;

    private static final String INSERT_EVENT = """
            INSERT INTO safety.edge_pending_event (
                id, event_id, edge_node_code, event_type, business_key,
                edge_occurred_at, received_at, server_received_at, synced_at,
                idempotency_key, clock_offset_at_occurrence, rule_version_used,
                risk_level_code, payload_summary, local_linkage,
                status_code, retry_count, last_retry_at, last_error,
                linked_alert_id, sync_delay_sec
            ) VALUES (
                :id, :eventId, :edgeNodeCode, :eventType, :businessKey,
                :edgeOccurredAt, :receivedAt, :serverReceivedAt, :syncedAt,
                :idempotencyKey, :clockOffsetAtOccurrence, :ruleVersionUsed,
                :riskLevelCode, :payloadSummary, :localLinkage,
                :statusCode, :retryCount, :lastRetryAt, :lastError,
                :linkedAlertId, :syncDelaySec
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<EdgePendingEvent> rowMapper;

    public JdbcEdgeEventQueueRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = new EdgePendingEventRowMapper();
    }

    @Override
    public EdgePendingEvent save(EdgePendingEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(event.idempotencyKey, "event.idempotencyKey must not be null");
        Objects.requireNonNull(event.eventId, "event.eventId must not be null");

        EdgePendingEvent toPersist = event.copy();
        MapSqlParameterSource params = createParams(toPersist);

        int updated = jdbcTemplate.update(UPDATE_EVENT, params);
        if (updated == 0) {
            try {
                jdbcTemplate.update(INSERT_EVENT, params);
            } catch (DuplicateKeyException race) {
                // 并发按同幂等键插入的竞态：退化为按既有行更新；若仍无匹配行
                //（撞 uq_edge_pending_event_node_id，即同节点同 eventId 换幂等键重放）则如实抛出
                if (jdbcTemplate.update(UPDATE_EVENT, params) == 0) {
                    throw race;
                }
            }
        }
        return toPersist.copy();
    }

    @Override
    public Optional<EdgePendingEvent> findByEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        try {
            EdgePendingEvent e = jdbcTemplate.queryForObject(
                    SELECT_BY_EVENT_ID, Map.of("eventId", eventId.trim()), rowMapper);
            return Optional.ofNullable(e).map(EdgePendingEvent::copy);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<EdgePendingEvent> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        try {
            EdgePendingEvent e = jdbcTemplate.queryForObject(
                    SELECT_BY_IDEMPOTENCY_KEY, Map.of("idempotencyKey", idempotencyKey.trim()), rowMapper);
            return Optional.ofNullable(e).map(EdgePendingEvent::copy);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<EdgePendingEvent> query(String nodeId, String status) {
        // 可选参数显式声明 SQL 类型：绑定为 null 时 openGauss 无法从 "? IS NULL" 上下文推断类型
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("nodeId", normalizeFilter(nodeId), Types.VARCHAR)
                .addValue("status", normalizeFilter(status), Types.VARCHAR);
        List<EdgePendingEvent> list = jdbcTemplate.query(SELECT_QUERY, params, rowMapper);
        return list.stream().map(EdgePendingEvent::copy).toList();
    }

    @Override
    public List<EdgePendingEvent> findPendingForReplay(String nodeId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("nodeId", normalizeFilter(nodeId), Types.VARCHAR);
        List<EdgePendingEvent> list = jdbcTemplate.query(SELECT_PENDING_FOR_REPLAY, params, rowMapper);
        return list.stream().map(EdgePendingEvent::copy).toList();
    }

    @Override
    public List<EdgePendingEvent> findAll() {
        List<EdgePendingEvent> list = jdbcTemplate.query(SELECT_ALL, Collections.emptyMap(), rowMapper);
        return list.stream().map(EdgePendingEvent::copy).toList();
    }

    @Override
    public int pendingCount(String nodeId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("nodeId", nodeId == null ? null : nodeId.trim(), Types.VARCHAR);
        Integer val = jdbcTemplate.queryForObject(COUNT_PENDING, params, Integer.class);
        return val != null ? val : 0;
    }

    @Override
    public long count() {
        Long val = jdbcTemplate.queryForObject(COUNT_ALL, Collections.emptyMap(), Long.class);
        return val != null ? val : 0L;
    }

    /** query/findPendingForReplay 的过滤语义与内存实现一致：null 或空白串表示不过滤。 */
    private static String normalizeFilter(String filter) {
        return filter == null || filter.isBlank() ? null : filter.trim();
    }

    private MapSqlParameterSource createParams(EdgePendingEvent e) {
        return new MapSqlParameterSource()
                .addValue("id", JdbcAlertRepository.resolveUuid(e.idempotencyKey))
                .addValue("eventId", e.eventId)
                .addValue("edgeNodeCode", e.edgeNodeId)
                .addValue("eventType", e.eventType)
                .addValue("businessKey", e.businessKey)
                .addValue("edgeOccurredAt", toTimestamp(e.edgeOccurredAt))
                .addValue("receivedAt", toTimestamp(e.receivedAt))
                .addValue("serverReceivedAt", toTimestamp(e.serverReceivedAt))
                .addValue("syncedAt", toTimestamp(e.syncedAt))
                .addValue("idempotencyKey", e.idempotencyKey)
                .addValue("clockOffsetAtOccurrence", e.clockOffsetAtOccurrence)
                .addValue("ruleVersionUsed", e.ruleVersionUsed)
                .addValue("riskLevelCode", e.risk)
                .addValue("payloadSummary", toJsonb(e.payloadSummary))
                .addValue("localLinkage", toJsonb(e.localLinkage))
                .addValue("statusCode", e.status)
                .addValue("retryCount", e.retryCount)
                .addValue("lastRetryAt", toTimestamp(e.lastRetryAt))
                .addValue("lastError", e.lastError)
                .addValue("linkedAlertId", e.linkedAlertId == null || e.linkedAlertId.isBlank()
                        ? null : JdbcAlertRepository.resolveUuid(e.linkedAlertId))
                .addValue("syncDelaySec", e.syncDelaySec);
    }

    private static Timestamp toTimestamp(java.time.OffsetDateTime time) {
        return time == null ? null : Timestamp.from(time.toInstant());
    }

    /** 序列化为 jsonb 参数；null 对象序列化为字面量 "null"（jsonb 非空值），读回时还原为 null。 */
    private PGobject toJsonb(Object value) {
        PGobject pgo = new PGobject();
        pgo.setType("jsonb");
        try {
            pgo.setValue(objectMapper.writeValueAsString(value));
        } catch (Exception ex) {
            throw new IllegalStateException("JSONB 序列化失败: " + value, ex);
        }
        return pgo;
    }

    private class EdgePendingEventRowMapper implements RowMapper<EdgePendingEvent> {
        @Override
        public EdgePendingEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            EdgePendingEvent e = new EdgePendingEvent();
            e.eventId = rs.getString("event_id");
            e.edgeNodeId = rs.getString("edge_node_code");
            e.eventType = rs.getString("event_type");
            e.businessKey = rs.getString("business_key");
            e.edgeOccurredAt = toOffsetDateTime(rs.getTimestamp("edge_occurred_at"));
            e.receivedAt = toOffsetDateTime(rs.getTimestamp("received_at"));
            e.serverReceivedAt = toOffsetDateTime(rs.getTimestamp("server_received_at"));
            e.syncedAt = toOffsetDateTime(rs.getTimestamp("synced_at"));
            e.idempotencyKey = rs.getString("idempotency_key");

            long clockOffset = rs.getLong("clock_offset_at_occurrence");
            e.clockOffsetAtOccurrence = rs.wasNull() ? null : clockOffset;

            e.ruleVersionUsed = rs.getString("rule_version_used");
            e.risk = rs.getString("risk_level_code");
            e.payloadSummary = fromJsonb(rs.getString("payload_summary"), EdgePendingEvent.PayloadSummary.class);
            e.localLinkage = fromJsonb(rs.getString("local_linkage"), EdgeLocalLinkage.class);
            e.status = rs.getString("status_code");
            e.retryCount = rs.getInt("retry_count");
            e.lastRetryAt = toOffsetDateTime(rs.getTimestamp("last_retry_at"));
            e.lastError = rs.getString("last_error");

            // 优先反查 alert_no 还原业务编号；关联告警行缺失时退化为 UUID 字符串，保证信息不丢
            String linkedNo = rs.getString("linked_alert_no");
            if (linkedNo != null && !linkedNo.isBlank()) {
                e.linkedAlertId = linkedNo;
            } else {
                UUID linkedUuid = (UUID) rs.getObject("linked_alert_id");
                e.linkedAlertId = linkedUuid == null ? null : linkedUuid.toString();
            }

            long syncDelay = rs.getLong("sync_delay_sec");
            e.syncDelaySec = rs.wasNull() ? null : syncDelay;
            return e;
        }
    }

    private <T> T fromJsonb(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("JSONB 反序列化失败: " + json, ex);
        }
    }

    private static java.time.OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atZone(ZONE).toOffsetDateTime();
    }
}
