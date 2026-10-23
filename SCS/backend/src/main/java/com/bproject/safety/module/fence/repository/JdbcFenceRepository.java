package com.bproject.safety.module.fence.repository;

import com.bproject.safety.common.geo.Polygon2D;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.EdgeNode;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 openGauss (PostgreSQL 兼容模式) 的电子围栏持久化仓储实现。
 * 覆盖电子围栏主表 {@code safety.safety_fence} 及版本子表 {@code safety.safety_fence_version}、
 * 边缘同步对账表 {@code safety.safety_fence_edge_sync}。
 *
 * <p>遵循 copy-on-read / copy-on-write 语义，提供与 InMemory 实现完全一致的业务表现。</p>
 */
@Repository
@Profile("server")
public class JdbcFenceRepository implements FenceRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcFenceRepository.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String BASE_SELECT = """
            SELECT f.id, f.fence_code, f.fence_name, f.fence_kind, f.tone, f.area_code, f.active_version,
                   f.status_code, f.effective_at, f.expires_at, f.approver_name_snapshot,
                   f.buffer_distance, f.is_deleted, f.created_at, f.updated_at,
                   v.risk_level_code, v.polygon, v.team_scope
            FROM safety.safety_fence f
            LEFT JOIN (
                SELECT fence_id, MAX(id) AS latest_id
                FROM safety.safety_fence_version
                GROUP BY fence_id
            ) latest ON latest.fence_id = f.id
            LEFT JOIN safety.safety_fence_version v ON v.id = latest.latest_id AND v.fence_id = f.id
            """;

    private static final String SELECT_ALL = BASE_SELECT + " WHERE f.is_deleted = false ORDER BY f.fence_code ASC";

    private static final String SELECT_BY_CODE = BASE_SELECT + " WHERE f.fence_code = :fenceCode AND f.is_deleted = false";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DemoMasterData masterData;
    private final Clock clock;
    private final RowMapper<FenceRowDto> rowMapper;

    public JdbcFenceRepository(NamedParameterJdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               DemoMasterData masterData,
                               Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.masterData = masterData;
        this.clock = clock;
        this.rowMapper = new FenceRowMapper();
    }

    @Override
    public List<DemoFence> findAll() {
        List<FenceRowDto> dtos = jdbcTemplate.query(SELECT_ALL, Collections.emptyMap(), rowMapper);
        return dtos.stream().map(this::toDemoFence).toList();
    }

    @Override
    public Optional<DemoFence> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            FenceRowDto dto = jdbcTemplate.queryForObject(
                    SELECT_BY_CODE,
                    Map.of("fenceCode", id.trim()),
                    rowMapper
            );
            return Optional.ofNullable(dto).map(this::toDemoFence);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public DemoFence save(DemoFence fence) {
        Objects.requireNonNull(fence, "fence must not be null");
        Objects.requireNonNull(fence.id, "fence.id must not be null");

        DemoFence toPersist = fence.copy();
        Long fenceId = upsertFenceRoot(toPersist);
        upsertFenceVersion(fenceId, toPersist);
        upsertFenceEdgeSync(fenceId, toPersist);

        return findById(toPersist.id).orElse(toPersist);
    }

    @Override
    public long count() {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM safety.safety_fence WHERE is_deleted = false",
                Collections.emptyMap(),
                Long.class
        );
        return c != null ? c : 0L;
    }

    // ---------- 内部持久化操作 ----------

    private Long upsertFenceRoot(DemoFence f) {
        String areaCode = null;
        if (f.area != null && !f.area.isBlank()) {
            areaCode = masterData.areas().stream()
                    .filter(a -> a.name().equals(f.area) || a.code().equals(f.area))
                    .map(DemoMasterData.DemoArea::code)
                    .findFirst()
                    .orElse(f.area);
        }

        Timestamp effectiveTs = parseEffectiveAt(f.effectiveAt);
        Timestamp expiresTs = parseExpiresAt(f.expiresAt);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fenceCode", f.id)
                .addValue("fenceName", f.name != null ? f.name : f.id)
                .addValue("fenceKind", f.kind != null ? f.kind : "危险区域")
                .addValue("tone", f.tone != null ? f.tone : "danger")
                .addValue("areaCode", areaCode)
                .addValue("activeVersion", f.version != null ? f.version : "v1.0")
                .addValue("statusCode", f.statusCode != null ? f.statusCode : FenceStatuses.EFFECTIVE)
                .addValue("effectiveAt", effectiveTs)
                .addValue("expiresAt", expiresTs)
                .addValue("approverName", f.approver)
                .addValue("bufferDistance", f.bufferDistance > 0 ? f.bufferDistance : 5.0);

        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM safety.safety_fence WHERE fence_code = :fenceCode",
                Map.of("fenceCode", f.id),
                (rs, rowNum) -> rs.getLong("id")
        );

        if (!ids.isEmpty()) {
            Long existingId = ids.get(0);
            params.addValue("id", existingId);
            String updateSql = """
                    UPDATE safety.safety_fence
                    SET fence_name = :fenceName,
                        fence_kind = :fenceKind,
                        tone = :tone,
                        area_code = :areaCode,
                        active_version = :activeVersion,
                        status_code = :statusCode,
                        effective_at = :effectiveAt,
                        expires_at = :expiresAt,
                        approver_name_snapshot = :approverName,
                        buffer_distance = :bufferDistance,
                        is_deleted = false,
                        updated_at = now()
                    WHERE id = :id
                    """;
            jdbcTemplate.update(updateSql, params);
            return existingId;
        } else {
            String insertSql = """
                    INSERT INTO safety.safety_fence (
                        fence_code, fence_name, fence_kind, tone, area_code, active_version,
                        status_code, effective_at, expires_at, approver_name_snapshot,
                        buffer_distance, lock_version, is_deleted, created_at, updated_at
                    ) VALUES (
                        :fenceCode, :fenceName, :fenceKind, :tone, :areaCode, :activeVersion,
                        :statusCode, :effectiveAt, :expiresAt, :approverName,
                        :bufferDistance, 0, false, now(), now()
                    ) RETURNING id
                    """;
            return jdbcTemplate.queryForObject(insertSql, params, Long.class);
        }
    }

    private void upsertFenceVersion(Long fenceId, DemoFence f) {
        String versionNo = f.version != null ? f.version : "v1.0";
        String polygonJson = "[]";
        try {
            polygonJson = objectMapper.writeValueAsString(f.polygon != null ? f.polygon : Collections.emptyList());
        } catch (Exception e) {
            log.warn("Failed to serialize polygon to JSON for fence {}", f.id, e);
        }
        PGobject polygonPg = toPgJsonb(polygonJson);

        String teamsJson = null;
        if (f.teams != null) {
            try {
                teamsJson = objectMapper.writeValueAsString(Map.of("raw", f.teams));
            } catch (Exception ignored) {
            }
        }
        PGobject teamsPg = toPgJsonb(teamsJson);

        List<Long> versionIds = jdbcTemplate.query(
                "SELECT id FROM safety.safety_fence_version WHERE fence_id = :fenceId AND version_no = :versionNo",
                Map.of("fenceId", fenceId, "versionNo", versionNo),
                (rs, rowNum) -> rs.getLong("id")
        );

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fenceId", fenceId)
                .addValue("versionNo", versionNo)
                .addValue("riskLevelCode", f.riskCode != null ? f.riskCode : RiskLevels.NORMAL)
                .addValue("statusCode", f.statusCode != null ? f.statusCode : FenceStatuses.EFFECTIVE)
                .addValue("polygon", polygonPg)
                .addValue("bufferDistance", f.bufferDistance > 0 ? f.bufferDistance : 5.0)
                .addValue("teamScope", teamsPg);

        if (!versionIds.isEmpty()) {
            String updateSql = """
                    UPDATE safety.safety_fence_version
                    SET risk_level_code = :riskLevelCode,
                        status_code = :statusCode,
                        polygon = :polygon,
                        buffer_distance = :bufferDistance,
                        team_scope = :teamScope
                    WHERE fence_id = :fenceId AND version_no = :versionNo
                    """;
            jdbcTemplate.update(updateSql, params);
        } else {
            String insertSql = """
                    INSERT INTO safety.safety_fence_version (
                        fence_id, version_no, risk_level_code, status_code, polygon,
                        buffer_distance, team_scope, created_at
                    ) VALUES (
                        :fenceId, :versionNo, :riskLevelCode, :statusCode, :polygon,
                        :bufferDistance, :teamScope, now()
                    )
                    """;
            jdbcTemplate.update(insertSql, params);
        }
    }

    private void upsertFenceEdgeSync(Long fenceId, DemoFence f) {
        List<EdgeNode> nodesToSync = f.nodes;
        if (nodesToSync == null || nodesToSync.isEmpty()) {
            nodesToSync = FenceStatuses.EFFECTIVE.equals(f.statusCode) ? DemoFence.successNodes() : DemoFence.pendingNodes();
        }

        String versionNo = f.version != null ? f.version : "v1.0";
        for (EdgeNode node : nodesToSync) {
            String syncStatus = EdgeNode.SUCCESS.equals(node.state()) ? "synced" : "syncing";
            List<Long> syncIds = jdbcTemplate.query(
                    "SELECT id FROM safety.safety_fence_edge_sync WHERE fence_id = :fenceId AND edge_node_code = :nodeCode",
                    Map.of("fenceId", fenceId, "nodeCode", node.id()),
                    (rs, rowNum) -> rs.getLong("id")
            );

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("fenceId", fenceId)
                    .addValue("nodeCode", node.id())
                    .addValue("expectedVersion", versionNo)
                    .addValue("edgeVersion", versionNo)
                    .addValue("syncStatus", syncStatus);

            if (!syncIds.isEmpty()) {
                String updateSql = """
                        UPDATE safety.safety_fence_edge_sync
                        SET expected_version = :expectedVersion,
                            edge_version = :edgeVersion,
                            sync_status = :syncStatus,
                            synced_at = now()
                        WHERE fence_id = :fenceId AND edge_node_code = :nodeCode
                        """;
                jdbcTemplate.update(updateSql, params);
            } else {
                String insertSql = """
                        INSERT INTO safety.safety_fence_edge_sync (
                            fence_id, edge_node_code, expected_version, edge_version, sync_status, synced_at
                        ) VALUES (
                            :fenceId, :nodeCode, :expectedVersion, :edgeVersion, :syncStatus, now()
                        )
                        """;
                jdbcTemplate.update(insertSql, params);
            }
        }
    }

    private DemoFence toDemoFence(FenceRowDto dto) {
        DemoFence f = new DemoFence();
        f.id = dto.fenceCode;
        f.name = dto.fenceName;
        f.kind = dto.fenceKind;
        f.tone = dto.tone;

        if (dto.areaCode != null) {
            f.area = masterData.areas().stream()
                    .filter(a -> a.code().equals(dto.areaCode) || a.name().equals(dto.areaCode))
                    .map(DemoMasterData.DemoArea::name)
                    .findFirst()
                    .orElse(dto.areaCode);
        }

        f.riskCode = dto.riskLevelCode != null ? dto.riskLevelCode : RiskLevels.NORMAL;
        f.version = dto.activeVersion != null ? dto.activeVersion : "v1.0";
        f.statusCode = dto.statusCode != null ? dto.statusCode : FenceStatuses.EFFECTIVE;
        f.effectiveAt = formatEffectiveAt(dto.effectiveAt);
        f.expiresAt = formatExpiresAt(dto.expiresAt);
        f.approver = dto.approverName;
        f.bufferDistance = dto.bufferDistance > 0 ? dto.bufferDistance : 5.0;
        f.isDeleted = dto.isDeleted;
        f.createdAt = dto.createdAt;
        f.updatedAt = dto.updatedAt;

        // Parse polygon
        if (dto.polygonJson != null && !dto.polygonJson.isBlank()) {
            try {
                f.polygon = objectMapper.readValue(dto.polygonJson, new TypeReference<List<FencePoint>>() {});
            } catch (Exception e) {
                f.polygon = new ArrayList<>();
            }
        } else {
            f.polygon = new ArrayList<>();
        }
        f.bufferPolygon = Polygon2D.expandPolygon(f.polygon, f.bufferDistance);

        // Parse teams
        if (dto.teamScopeJson != null && !dto.teamScopeJson.isBlank()) {
            try {
                JsonNode tn = objectMapper.readTree(dto.teamScopeJson);
                if (tn.has("raw")) {
                    f.teams = tn.get("raw").asText();
                } else if (tn.isArray()) {
                    List<String> list = new ArrayList<>();
                    tn.forEach(item -> list.add(item.asText()));
                    f.teams = String.join("、", list);
                } else {
                    f.teams = dto.teamScopeJson;
                }
            } catch (Exception e) {
                f.teams = dto.teamScopeJson;
            }
        }

        // Load edge nodes
        List<EdgeNode> nodes = jdbcTemplate.query(
                "SELECT edge_node_code, sync_status FROM safety.safety_fence_edge_sync WHERE fence_id = :fenceId ORDER BY edge_node_code ASC",
                Map.of("fenceId", dto.id),
                (rs, rowNum) -> {
                    String nodeCode = rs.getString("edge_node_code");
                    String syncStatus = rs.getString("sync_status");
                    return new EdgeNode(nodeCode, "synced".equalsIgnoreCase(syncStatus) ? EdgeNode.SUCCESS : EdgeNode.PENDING);
                }
        );

        if (nodes.isEmpty()) {
            f.nodes = FenceStatuses.EFFECTIVE.equals(f.statusCode) ? DemoFence.successNodes() : DemoFence.pendingNodes();
        } else {
            f.nodes = new ArrayList<>(nodes);
        }
        f.edgeSynced = (int) f.nodes.stream().filter(n -> EdgeNode.SUCCESS.equals(n.state())).count();
        f.edgeTotal = f.nodes.isEmpty() ? 4 : f.nodes.size();

        return f;
    }

    private PGobject toPgJsonb(String jsonStr) {
        if (jsonStr == null) {
            return null;
        }
        try {
            PGobject obj = new PGobject();
            obj.setType("jsonb");
            obj.setValue(jsonStr);
            return obj;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to construct PGobject jsonb", e);
        }
    }

    private Timestamp parseEffectiveAt(String str) {
        if (str == null || str.isBlank()) {
            return Timestamp.from(OffsetDateTime.now(clock).toInstant());
        }
        try {
            return Timestamp.from(LocalDateTime.parse(str, DT_FORMAT).atZone(ZONE).toInstant());
        } catch (Exception e) {
            try {
                return Timestamp.from(OffsetDateTime.parse(str).toInstant());
            } catch (Exception ignored) {
                return Timestamp.from(OffsetDateTime.now(clock).toInstant());
            }
        }
    }

    private Timestamp parseExpiresAt(String str) {
        if (str == null || str.isBlank() || "长期有效".equals(str.trim())) {
            return null;
        }
        try {
            return Timestamp.from(LocalDateTime.parse(str, DT_FORMAT).atZone(ZONE).toInstant());
        } catch (Exception e) {
            try {
                return Timestamp.from(OffsetDateTime.parse(str).toInstant());
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String formatEffectiveAt(Timestamp ts) {
        if (ts == null) return null;
        return ts.toInstant().atZone(ZONE).format(DT_FORMAT);
    }

    private String formatExpiresAt(Timestamp ts) {
        if (ts == null) return "长期有效";
        return ts.toInstant().atZone(ZONE).format(DT_FORMAT);
    }

    private static class FenceRowDto {
        Long id;
        String fenceCode;
        String fenceName;
        String fenceKind;
        String tone;
        String areaCode;
        String activeVersion;
        String statusCode;
        Timestamp effectiveAt;
        Timestamp expiresAt;
        String approverName;
        double bufferDistance;
        boolean isDeleted;
        OffsetDateTime createdAt;
        OffsetDateTime updatedAt;
        String riskLevelCode;
        String polygonJson;
        String teamScopeJson;
    }

    private class FenceRowMapper implements RowMapper<FenceRowDto> {
        @Override
        public FenceRowDto mapRow(ResultSet rs, int rowNum) throws SQLException {
            FenceRowDto d = new FenceRowDto();
            d.id = rs.getLong("id");
            d.fenceCode = rs.getString("fence_code");
            d.fenceName = rs.getString("fence_name");
            d.fenceKind = rs.getString("fence_kind");
            d.tone = rs.getString("tone");
            d.areaCode = rs.getString("area_code");
            d.activeVersion = rs.getString("active_version");
            d.statusCode = rs.getString("status_code");
            d.effectiveAt = rs.getTimestamp("effective_at");
            d.expiresAt = rs.getTimestamp("expires_at");
            d.approverName = rs.getString("approver_name_snapshot");
            d.bufferDistance = rs.getDouble("buffer_distance");
            d.isDeleted = rs.getBoolean("is_deleted");

            Timestamp createdTs = rs.getTimestamp("created_at");
            if (createdTs != null) {
                d.createdAt = createdTs.toInstant().atZone(ZONE).toOffsetDateTime();
            }
            Timestamp updatedTs = rs.getTimestamp("updated_at");
            if (updatedTs != null) {
                d.updatedAt = updatedTs.toInstant().atZone(ZONE).toOffsetDateTime();
            }

            d.riskLevelCode = rs.getString("risk_level_code");
            d.polygonJson = rs.getString("polygon");
            d.teamScopeJson = rs.getString("team_scope");
            return d;
        }
    }
}
