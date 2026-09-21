package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgeNodeStatuses;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 openGauss (PostgreSQL 兼容模式) 的边缘计算节点持久化仓储实现。
 * 对应物理表 {@code safety.edge_node}。
 *
 * <p>遵循 copy-on-read / copy-on-write 语义，提供与 InMemory 实现一致的表现。</p>
 */
@Repository
@Profile("server")
public class JdbcEdgeNodeRepository implements EdgeNodeRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String BASE_SELECT = """
            SELECT node_code, node_name, area_code, ip_address, node_status_code, cloud_connected,
                   autonomy_active, agent_version, active_rule_version, expected_rule_version,
                   active_fence_version, expected_fence_version, clock_offset_ms, latency_ms,
                   cpu_usage_pct, memory_usage_pct, disk_usage_pct, temperature_c, queue_depth,
                   cached_event_count, uptime_sec, last_heartbeat_at, last_sync_at, last_error
            FROM safety.edge_node
            """;

    private static final String SELECT_ALL = BASE_SELECT + " WHERE is_deleted = false ORDER BY node_code ASC";

    private static final String SELECT_BY_CODE = BASE_SELECT + " WHERE node_code = :nodeCode AND is_deleted = false";

    private static final String COUNT_NODES = "SELECT count(*) FROM safety.edge_node WHERE is_deleted = false";

    private static final String UPDATE_NODE = """
            UPDATE safety.edge_node
            SET node_name = :nodeName,
                area_code = :areaCode,
                ip_address = :ipAddress,
                node_status_code = :statusCode,
                cloud_connected = :cloudConnected,
                autonomy_active = :autonomyActive,
                agent_version = :agentVersion,
                active_rule_version = :activeRuleVersion,
                expected_rule_version = :expectedRuleVersion,
                active_fence_version = :activeFenceVersion,
                expected_fence_version = :expectedFenceVersion,
                clock_offset_ms = :clockOffsetMs,
                latency_ms = :latencyMs,
                cpu_usage_pct = :cpuUsage,
                memory_usage_pct = :memoryUsage,
                disk_usage_pct = :diskUsage,
                temperature_c = :temperature,
                queue_depth = :queueDepth,
                cached_event_count = :cachedEventCount,
                uptime_sec = :uptimeSec,
                last_heartbeat_at = :lastHeartbeatAt,
                last_sync_at = :lastSyncAt,
                last_error = :lastError,
                updated_at = now()
            WHERE node_code = :nodeCode AND is_deleted = false
            """;

    private static final String INSERT_NODE = """
            INSERT INTO safety.edge_node (
                node_code, node_name, area_code, ip_address, node_status_code, cloud_connected,
                autonomy_active, agent_version, active_rule_version, expected_rule_version,
                active_fence_version, expected_fence_version, clock_offset_ms, latency_ms,
                cpu_usage_pct, memory_usage_pct, disk_usage_pct, temperature_c, queue_depth,
                cached_event_count, uptime_sec, last_heartbeat_at, last_sync_at, last_error,
                is_deleted, created_at, updated_at
            ) VALUES (
                :nodeCode, :nodeName, :areaCode, :ipAddress, :statusCode, :cloudConnected,
                :autonomyActive, :agentVersion, :activeRuleVersion, :expectedRuleVersion,
                :activeFenceVersion, :expectedFenceVersion, :clockOffsetMs, :latencyMs,
                :cpuUsage, :memoryUsage, :diskUsage, :temperature, :queueDepth,
                :cachedEventCount, :uptimeSec, :lastHeartbeatAt, :lastSyncAt, :lastError,
                false, now(), now()
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DemoMasterData masterData;
    private final RowMapper<DemoEdgeNode> rowMapper;

    public JdbcEdgeNodeRepository(NamedParameterJdbcTemplate jdbcTemplate,
                                  DemoMasterData masterData) {
        this.jdbcTemplate = jdbcTemplate;
        this.masterData = masterData;
        this.rowMapper = new EdgeNodeRowMapper();
    }

    @Override
    public List<DemoEdgeNode> findAll() {
        List<DemoEdgeNode> list = jdbcTemplate.query(SELECT_ALL, Collections.emptyMap(), rowMapper);
        return list.stream().map(DemoEdgeNode::copy).toList();
    }

    @Override
    public Optional<DemoEdgeNode> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            DemoEdgeNode node = jdbcTemplate.queryForObject(
                    SELECT_BY_CODE,
                    Map.of("nodeCode", id.trim()),
                    rowMapper
            );
            return Optional.ofNullable(node).map(DemoEdgeNode::copy);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public DemoEdgeNode save(DemoEdgeNode node) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(node.id, "node.id must not be null");

        DemoEdgeNode toPersist = node.copy();
        MapSqlParameterSource params = createParams(toPersist);

        int updated = jdbcTemplate.update(UPDATE_NODE, params);
        if (updated == 0) {
            jdbcTemplate.update(INSERT_NODE, params);
        }

        return toPersist.copy();
    }

    @Override
    public long count() {
        Long val = jdbcTemplate.queryForObject(COUNT_NODES, Collections.emptyMap(), Long.class);
        return val != null ? val : 0L;
    }

    private MapSqlParameterSource createParams(DemoEdgeNode n) {
        String areaCode = null;
        if (n.area != null && !n.area.isBlank()) {
            areaCode = masterData.areas().stream()
                    .filter(a -> a.name().equals(n.area) || a.code().equals(n.area))
                    .map(DemoMasterData.DemoArea::code)
                    .findFirst()
                    .orElse(n.area);
        }

        Timestamp hbTs = n.lastHeartbeat != null ? Timestamp.from(n.lastHeartbeat.toInstant()) : null;
        Timestamp syncTs = n.lastSyncAt != null ? Timestamp.from(n.lastSyncAt.toInstant()) : null;

        return new MapSqlParameterSource()
                .addValue("nodeCode", n.id)
                .addValue("nodeName", n.name != null ? n.name : n.id)
                .addValue("areaCode", areaCode)
                .addValue("ipAddress", n.ip)
                .addValue("statusCode", n.status != null ? n.status : EdgeNodeStatuses.ONLINE)
                .addValue("cloudConnected", n.cloudConnected)
                .addValue("autonomyActive", n.autonomyActive)
                .addValue("agentVersion", n.agentVersion)
                .addValue("activeRuleVersion", n.activeRuleVersion)
                .addValue("expectedRuleVersion", n.expectedRuleVersion)
                .addValue("activeFenceVersion", n.activeFenceVersion)
                .addValue("expectedFenceVersion", n.expectedFenceVersion)
                .addValue("clockOffsetMs", n.clockOffsetMs)
                .addValue("latencyMs", n.latencyMs)
                .addValue("cpuUsage", n.cpuUsage)
                .addValue("memoryUsage", n.memoryUsage)
                .addValue("diskUsage", n.diskUsage)
                .addValue("temperature", n.temperature)
                .addValue("queueDepth", n.queueDepth)
                .addValue("cachedEventCount", n.cachedEventCount)
                .addValue("uptimeSec", n.uptimeSec)
                .addValue("lastHeartbeatAt", hbTs)
                .addValue("lastSyncAt", syncTs)
                .addValue("lastError", n.lastError);
    }

    private class EdgeNodeRowMapper implements RowMapper<DemoEdgeNode> {
        @Override
        public DemoEdgeNode mapRow(ResultSet rs, int rowNum) throws SQLException {
            DemoEdgeNode n = new DemoEdgeNode();
            n.id = rs.getString("node_code");
            n.name = rs.getString("node_name");

            String areaCode = rs.getString("area_code");
            if (areaCode != null) {
                n.area = masterData.areas().stream()
                        .filter(a -> a.code().equals(areaCode))
                        .map(DemoMasterData.DemoArea::name)
                        .findFirst()
                        .orElse(areaCode);
            }

            n.ip = rs.getString("ip_address");
            n.status = rs.getString("node_status_code");
            n.cloudConnected = rs.getBoolean("cloud_connected");
            n.autonomyActive = rs.getBoolean("autonomy_active");
            n.agentVersion = rs.getString("agent_version");
            n.activeRuleVersion = rs.getString("active_rule_version");
            n.expectedRuleVersion = rs.getString("expected_rule_version");
            n.activeFenceVersion = rs.getString("active_fence_version");
            n.expectedFenceVersion = rs.getString("expected_fence_version");

            long clockOffset = rs.getLong("clock_offset_ms");
            n.clockOffsetMs = rs.wasNull() ? null : clockOffset;

            int latency = rs.getInt("latency_ms");
            n.latencyMs = rs.wasNull() ? null : latency;

            int cpu = rs.getInt("cpu_usage_pct");
            n.cpuUsage = rs.wasNull() ? null : cpu;

            int mem = rs.getInt("memory_usage_pct");
            n.memoryUsage = rs.wasNull() ? null : mem;

            int disk = rs.getInt("disk_usage_pct");
            n.diskUsage = rs.wasNull() ? null : disk;

            double temp = rs.getDouble("temperature_c");
            n.temperature = rs.wasNull() ? null : (int) Math.round(temp);

            n.queueDepth = rs.getInt("queue_depth");
            n.cachedEventCount = rs.getInt("cached_event_count");

            long uptime = rs.getLong("uptime_sec");
            n.uptimeSec = rs.wasNull() ? 0L : uptime;

            Timestamp hbTs = rs.getTimestamp("last_heartbeat_at");
            if (hbTs != null) {
                n.lastHeartbeat = hbTs.toInstant().atZone(ZONE).toOffsetDateTime();
            }

            Timestamp syncTs = rs.getTimestamp("last_sync_at");
            if (syncTs != null) {
                n.lastSyncAt = syncTs.toInstant().atZone(ZONE).toOffsetDateTime();
            }

            n.lastError = rs.getString("last_error");

            if (n.diskUsage != null) {
                n.cacheParts = List.of(
                        new DemoEdgeNode.CachePart("事件缓存", n.diskUsage - 3),
                        new DemoEdgeNode.CachePart("视频证据缓存", n.diskUsage + 4),
                        new DemoEdgeNode.CachePart("日志空间", n.diskUsage - 9));
            } else {
                n.cacheParts = new ArrayList<>();
            }
            n.recoveryPhases = new ArrayList<>();

            return n;
        }
    }
}
