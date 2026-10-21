package com.bproject.safety.module.rule.repository;

import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.DemoRule.EdgeNode;
import com.bproject.safety.module.rule.model.DemoRule.Param;
import com.bproject.safety.module.rule.model.DemoRule.Version;
import com.bproject.safety.module.rule.model.DemoRule.VersionDiff;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.support.demo.DemoClearableStore;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Comparator;
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
 * 基于 openGauss (PostgreSQL 兼容模式) 的安全卡控规则持久化仓储实现。
 * 覆盖规则主表 {@code safety.safety_rule}、区域关联表 {@code safety.safety_rule_area}、
 * 版本快照表 {@code safety.safety_rule_version} 以及边缘同步表 {@code safety.safety_rule_edge_sync}。
 *
 * <p>遵循 copy-on-read / copy-on-write 语义，提供与 InMemory 实现完全一致的业务表现。</p>
 */
@Repository
@Profile("server")
public class JdbcRuleRepository implements RuleRepository, DemoClearableStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRuleRepository.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final String BASE_SELECT = """
            SELECT id, rule_code, rule_name, category, rule_type, scope_desc,
                   active_version, platform_version, risk_level_code, status_code,
                   high_risk, owner_name_snapshot, approver_name_snapshot,
                   effective_at, description, related_modules, is_deleted, created_at, updated_at
            FROM safety.safety_rule
            """;

    private static final Comparator<DemoRule> ORDER = Comparator
            .comparing((DemoRule r) -> r.updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(r -> r.id, Comparator.nullsLast(Comparator.naturalOrder()));

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RowMapper<RuleRowDto> rowMapper;

    public JdbcRuleRepository(NamedParameterJdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.rowMapper = new RuleRowMapper();
    }

    @Override
    public List<DemoRule> findAll() {
        String sql = BASE_SELECT + " WHERE is_deleted = false ORDER BY updated_at DESC NULLS LAST, rule_code ASC";
        List<RuleRowDto> dtos = jdbcTemplate.query(sql, Collections.emptyMap(), rowMapper);
        return dtos.stream().map(this::toDemoRule).sorted(ORDER).toList();
    }

    @Override
    public List<DemoRule> filter(RuleQuery q) {
        StringBuilder sql = new StringBuilder(BASE_SELECT).append(" WHERE is_deleted = false");
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (q.category() != null && !q.category().isBlank()) {
            sql.append(" AND category = :category");
            params.addValue("category", q.category().trim());
        }

        if (q.status() != null && !q.status().isBlank()) {
            sql.append(" AND status_code = :statusCode");
            params.addValue("statusCode", RuleStatuses.normalize(q.status()));
        }

        if (q.risk() != null && !q.risk().isBlank()) {
            sql.append(" AND risk_level_code = :riskCode");
            params.addValue("riskCode", RiskLevels.normalize(q.risk()));
        }

        if (q.keyword() != null && !q.keyword().isBlank()) {
            sql.append(" AND (rule_code LIKE :kw OR rule_name LIKE :kw OR owner_name_snapshot LIKE :kw)");
            params.addValue("kw", "%" + q.keyword().trim() + "%");
        }

        sql.append(" ORDER BY updated_at DESC NULLS LAST, rule_code ASC");

        List<RuleRowDto> dtos = jdbcTemplate.query(sql.toString(), params, rowMapper);
        return dtos.stream().map(this::toDemoRule).sorted(ORDER).toList();
    }

    @Override
    public Optional<DemoRule> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            String sql = BASE_SELECT + " WHERE rule_code = :ruleCode AND is_deleted = false";
            RuleRowDto dto = jdbcTemplate.queryForObject(
                    sql,
                    Map.of("ruleCode", id.trim()),
                    rowMapper
            );
            return Optional.ofNullable(dto).map(this::toDemoRule);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public DemoRule save(DemoRule rule) {
        Objects.requireNonNull(rule, "rule must not be null");
        Objects.requireNonNull(rule.id, "rule.id must not be null");

        DemoRule toPersist = rule.copy();
        Long ruleId = upsertRuleRoot(toPersist);
        saveAreas(ruleId, toPersist.areas);
        saveVersions(ruleId, toPersist);
        saveEdgeSync(ruleId, toPersist);

        return findById(toPersist.id).orElse(toPersist);
    }

    @Override
    public long count() {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM safety.safety_rule WHERE is_deleted = false",
                Collections.emptyMap(),
                Long.class
        );
        return c != null ? c : 0L;
    }

    @Override
    public void clearDemoData() {
        jdbcTemplate.update("UPDATE safety.safety_rule SET is_deleted = true, updated_at = now() WHERE is_deleted = false", Collections.emptyMap());
    }

    // ---------- 内部持久化操作 ----------

    private Long upsertRuleRoot(DemoRule r) {
        Timestamp effectiveTs = parseDateTime(r.effectiveAt);
        Timestamp updatedTs = parseDateTime(r.updatedAt);
        if (updatedTs == null) {
            updatedTs = Timestamp.from(OffsetDateTime.now(clock).toInstant());
        }

        String relatedModulesJson = null;
        if (r.relatedModules != null) {
            try {
                relatedModulesJson = objectMapper.writeValueAsString(r.relatedModules);
            } catch (Exception ignored) {
            }
        }
        PGobject relatedPg = toPgJsonb(relatedModulesJson);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ruleCode", r.id)
                .addValue("ruleName", r.name != null ? r.name : r.id)
                .addValue("category", r.category != null ? r.category : "人员安全")
                .addValue("ruleType", r.type)
                .addValue("scopeDesc", r.scope)
                .addValue("activeVersion", r.version != null ? r.version : "v1.0")
                .addValue("platformVersion", r.platformVersion != null ? r.platformVersion : r.version)
                .addValue("riskLevelCode", r.riskCode != null ? r.riskCode : RiskLevels.NORMAL)
                .addValue("statusCode", r.statusCode != null ? r.statusCode : RuleStatuses.ACTIVE)
                .addValue("highRisk", r.highRisk)
                .addValue("ownerName", r.owner)
                .addValue("approverName", r.approver)
                .addValue("effectiveAt", effectiveTs)
                .addValue("description", r.description)
                .addValue("relatedModules", relatedPg)
                .addValue("updatedAt", updatedTs);

        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM safety.safety_rule WHERE rule_code = :ruleCode",
                Map.of("ruleCode", r.id),
                (rs, rowNum) -> rs.getLong("id")
        );

        if (!ids.isEmpty()) {
            Long existingId = ids.get(0);
            params.addValue("id", existingId);
            String updateSql = """
                    UPDATE safety.safety_rule
                    SET rule_name = :ruleName,
                        category = :category,
                        rule_type = :ruleType,
                        scope_desc = :scopeDesc,
                        active_version = :activeVersion,
                        platform_version = :platformVersion,
                        risk_level_code = :riskLevelCode,
                        status_code = :statusCode,
                        high_risk = :highRisk,
                        owner_name_snapshot = :ownerName,
                        approver_name_snapshot = :approverName,
                        effective_at = :effectiveAt,
                        description = :description,
                        related_modules = :relatedModules,
                        is_deleted = false,
                        updated_at = :updatedAt
                    WHERE id = :id
                    """;
            jdbcTemplate.update(updateSql, params);
            return existingId;
        } else {
            String insertSql = """
                    INSERT INTO safety.safety_rule (
                        rule_code, rule_name, category, rule_type, scope_desc,
                        active_version, platform_version, risk_level_code, status_code,
                        high_risk, owner_name_snapshot, approver_name_snapshot,
                        effective_at, description, related_modules, lock_version,
                        is_deleted, created_at, updated_at
                    ) VALUES (
                        :ruleCode, :ruleName, :category, :ruleType, :scopeDesc,
                        :activeVersion, :platformVersion, :riskLevelCode, :statusCode,
                        :highRisk, :ownerName, :approverName,
                        :effectiveAt, :description, :relatedModules, 0,
                        false, now(), :updatedAt
                    ) RETURNING id
                    """;
            return jdbcTemplate.queryForObject(insertSql, params, Long.class);
        }
    }

    private void saveAreas(Long ruleId, List<String> areas) {
        if (areas == null || areas.isEmpty()) {
            return;
        }
        List<String> existing = jdbcTemplate.query(
                "SELECT area_code FROM safety.safety_rule_area WHERE rule_id = :ruleId",
                Map.of("ruleId", ruleId),
                (rs, rowNum) -> rs.getString("area_code")
        );

        for (String area : areas) {
            if (area != null && !area.isBlank() && !existing.contains(area.trim())) {
                jdbcTemplate.update(
                        "INSERT INTO safety.safety_rule_area (rule_id, area_code) VALUES (:ruleId, :areaCode)",
                        Map.of("ruleId", ruleId, "areaCode", area.trim())
                );
            }
        }
    }

    private void saveVersions(Long ruleId, DemoRule r) {
        List<Version> versionsToSave = r.versions;
        if (versionsToSave == null || versionsToSave.isEmpty()) {
            versionsToSave = List.of(new Version(
                    r.version != null ? r.version : "v1.0",
                    r.updatedAt,
                    "初始规则",
                    r.owner,
                    "当前",
                    null
            ));
        }

        for (Version v : versionsToSave) {
            String versionNo = v.version() != null ? v.version() : "v1.0";
            List<Param> paramsToUse = r.params;
            List<String> actionsToUse = r.actions;

            String paramsJson = "[]";
            try {
                paramsJson = objectMapper.writeValueAsString(paramsToUse != null ? paramsToUse : Collections.emptyList());
            } catch (Exception ignored) {
            }
            PGobject paramsPg = toPgJsonb(paramsJson);

            String actionsJson = "[]";
            try {
                actionsJson = objectMapper.writeValueAsString(actionsToUse != null ? actionsToUse : Collections.emptyList());
            } catch (Exception ignored) {
            }
            PGobject actionsPg = toPgJsonb(actionsJson);

            String diffsJson = null;
            if (v.diffs() != null && !v.diffs().isEmpty()) {
                try {
                    diffsJson = objectMapper.writeValueAsString(v.diffs());
                } catch (Exception ignored) {
                }
            }
            PGobject diffsPg = toPgJsonb(diffsJson);

            List<Long> existingIds = jdbcTemplate.query(
                    "SELECT id FROM safety.safety_rule_version WHERE rule_id = :ruleId AND version_no = :versionNo",
                    Map.of("ruleId", ruleId, "versionNo", versionNo),
                    (rs, rowNum) -> rs.getLong("id")
            );

            MapSqlParameterSource p = new MapSqlParameterSource()
                    .addValue("ruleId", ruleId)
                    .addValue("versionNo", versionNo)
                    .addValue("statusCode", v.state() != null ? v.state() : "ACTIVE")
                    .addValue("params", paramsPg)
                    .addValue("actions", actionsPg)
                    .addValue("sourceVersion", r.sourceVersion)
                    .addValue("versionNote", v.note())
                    .addValue("versionDiffs", diffsPg)
                    .addValue("authorName", v.author() != null ? v.author() : r.owner);

            if (!existingIds.isEmpty()) {
                String updateSql = """
                        UPDATE safety.safety_rule_version
                        SET status_code = :statusCode,
                            params = :params,
                            actions = :actions,
                            source_version = :sourceVersion,
                            version_note = :versionNote,
                            version_diffs = :versionDiffs,
                            author_name_snapshot = :authorName
                        WHERE rule_id = :ruleId AND version_no = :versionNo
                        """;
                jdbcTemplate.update(updateSql, p);
            } else {
                String insertSql = """
                        INSERT INTO safety.safety_rule_version (
                            rule_id, version_no, status_code, params, actions,
                            source_version, version_note, version_diffs,
                            author_name_snapshot, created_at
                        ) VALUES (
                            :ruleId, :versionNo, :statusCode, :params, :actions,
                            :sourceVersion, :versionNote, :versionDiffs,
                            :authorName, now()
                        )
                        """;
                jdbcTemplate.update(insertSql, p);
            }
        }
    }

    private void saveEdgeSync(Long ruleId, DemoRule r) {
        List<EdgeNode> nodesToSync = r.edgeNodes;
        if (nodesToSync == null || nodesToSync.isEmpty()) {
            String v = r.version != null ? r.version : "v1.0";
            nodesToSync = List.of(
                    new EdgeNode("EDGE-01", v, RuleStatuses.EDGE_SYNCED),
                    new EdgeNode("EDGE-02", v, RuleStatuses.EDGE_SYNCED),
                    new EdgeNode("EDGE-03", v, RuleStatuses.EDGE_SYNCED),
                    new EdgeNode("EDGE-04", v, RuleStatuses.EDGE_SYNCED)
            );
        }

        for (EdgeNode node : nodesToSync) {
            List<Long> syncIds = jdbcTemplate.query(
                    "SELECT id FROM safety.safety_rule_edge_sync WHERE rule_id = :ruleId AND edge_node_code = :nodeCode",
                    Map.of("ruleId", ruleId, "nodeCode", node.node()),
                    (rs, rowNum) -> rs.getLong("id")
            );

            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("ruleId", ruleId)
                    .addValue("nodeCode", node.node())
                    .addValue("expectedVersion", r.version != null ? r.version : "v1.0")
                    .addValue("edgeVersion", node.version() != null ? node.version() : r.version)
                    .addValue("syncStatus", node.state() != null ? node.state() : RuleStatuses.EDGE_SYNCED);

            if (!syncIds.isEmpty()) {
                String updateSql = """
                        UPDATE safety.safety_rule_edge_sync
                        SET expected_version = :expectedVersion,
                            edge_version = :edgeVersion,
                            sync_status = :syncStatus,
                            synced_at = now()
                        WHERE rule_id = :ruleId AND edge_node_code = :nodeCode
                        """;
                jdbcTemplate.update(updateSql, params);
            } else {
                String insertSql = """
                        INSERT INTO safety.safety_rule_edge_sync (
                            rule_id, edge_node_code, expected_version, edge_version, sync_status, synced_at
                        ) VALUES (
                            :ruleId, :nodeCode, :expectedVersion, :edgeVersion, :syncStatus, now()
                        )
                        """;
                jdbcTemplate.update(insertSql, params);
            }
        }
    }

    private DemoRule toDemoRule(RuleRowDto dto) {
        DemoRule r = new DemoRule();
        r.id = dto.ruleCode;
        r.name = dto.ruleName;
        r.category = dto.category;
        r.type = dto.ruleType;
        r.scope = dto.scopeDesc;
        r.version = dto.activeVersion;
        r.platformVersion = dto.platformVersion != null ? dto.platformVersion : dto.activeVersion;
        r.riskCode = dto.riskLevelCode;
        r.statusCode = dto.statusCode;
        r.highRisk = dto.highRisk;
        r.owner = dto.ownerName;
        r.approver = dto.approverName;
        r.effectiveAt = formatTimestamp(dto.effectiveAt);
        r.updatedAt = formatTimestamp(dto.updatedAt);
        r.description = dto.description;
        r.createdTime = dto.createdAt;
        r.isDeleted = dto.isDeleted;

        // Parse relatedModules
        if (dto.relatedModulesJson != null && !dto.relatedModulesJson.isBlank()) {
            try {
                r.relatedModules = objectMapper.readValue(dto.relatedModulesJson, new TypeReference<List<String>>() {});
            } catch (Exception ignored) {
                r.relatedModules = new ArrayList<>();
            }
        } else {
            r.relatedModules = new ArrayList<>();
        }

        // Query areas
        r.areas = jdbcTemplate.query(
                "SELECT area_code FROM safety.safety_rule_area WHERE rule_id = :ruleId ORDER BY id ASC",
                Map.of("ruleId", dto.id),
                (rs, rowNum) -> rs.getString("area_code")
        );

        // Query versions
        String verSql = """
                SELECT version_no, status_code, params, actions, source_version,
                       version_note, version_diffs, author_name_snapshot, created_at
                FROM safety.safety_rule_version
                WHERE rule_id = :ruleId
                ORDER BY id DESC
                """;
        List<VersionRowDto> verDtos = jdbcTemplate.query(
                verSql,
                Map.of("ruleId", dto.id),
                (rs, rowNum) -> {
                    VersionRowDto vd = new VersionRowDto();
                    vd.versionNo = rs.getString("version_no");
                    vd.statusCode = rs.getString("status_code");
                    vd.paramsJson = rs.getString("params");
                    vd.actionsJson = rs.getString("actions");
                    vd.sourceVersion = rs.getString("source_version");
                    vd.versionNote = rs.getString("version_note");
                    vd.versionDiffsJson = rs.getString("version_diffs");
                    vd.authorName = rs.getString("author_name_snapshot");
                    vd.createdAt = rs.getTimestamp("created_at");
                    return vd;
                }
        );

        List<Version> versionList = new ArrayList<>();
        for (VersionRowDto vd : verDtos) {
            List<VersionDiff> diffs = null;
            if (vd.versionDiffsJson != null && !vd.versionDiffsJson.isBlank()) {
                try {
                    diffs = objectMapper.readValue(vd.versionDiffsJson, new TypeReference<List<VersionDiff>>() {});
                } catch (Exception ignored) {
                }
            }
            String dateStr = vd.createdAt != null ? vd.createdAt.toInstant().atZone(ZONE).format(DATE_FORMAT) : null;
            versionList.add(new Version(
                    vd.versionNo,
                    dateStr,
                    vd.versionNote,
                    vd.authorName,
                    vd.statusCode,
                    diffs
            ));
        }
        r.versions = versionList;

        // If active/latest version has params/actions, set on DemoRule root
        if (!verDtos.isEmpty()) {
            VersionRowDto top = verDtos.get(0);
            r.sourceVersion = top.sourceVersion;
            if (top.paramsJson != null && !top.paramsJson.isBlank()) {
                try {
                    r.params = objectMapper.readValue(top.paramsJson, new TypeReference<List<Param>>() {});
                } catch (Exception ignored) {
                    r.params = new ArrayList<>();
                }
            }
            if (top.actionsJson != null && !top.actionsJson.isBlank()) {
                try {
                    r.actions = objectMapper.readValue(top.actionsJson, new TypeReference<List<String>>() {});
                } catch (Exception ignored) {
                    r.actions = new ArrayList<>();
                }
            }
        }

        // Query edge sync nodes
        String edgeSql = """
                SELECT edge_node_code, expected_version, edge_version, sync_status
                FROM safety.safety_rule_edge_sync
                WHERE rule_id = :ruleId
                ORDER BY edge_node_code ASC
                """;
        List<EdgeNode> edgeNodes = jdbcTemplate.query(
                edgeSql,
                Map.of("ruleId", dto.id),
                (rs, rowNum) -> {
                    String nodeCode = rs.getString("edge_node_code");
                    String edgeVer = rs.getString("edge_version");
                    String syncStatus = rs.getString("sync_status");
                    return new EdgeNode(nodeCode, edgeVer != null ? edgeVer : r.version, syncStatus);
                }
        );

        if (edgeNodes.isEmpty()) {
            String v = r.version != null ? r.version : "v1.0";
            r.edgeNodes = List.of(
                    new EdgeNode("EDGE-01", v, RuleStatuses.EDGE_SYNCED),
                    new EdgeNode("EDGE-02", v, RuleStatuses.EDGE_SYNCED),
                    new EdgeNode("EDGE-03", v, RuleStatuses.EDGE_SYNCED),
                    new EdgeNode("EDGE-04", v, RuleStatuses.EDGE_SYNCED)
            );
        } else {
            r.edgeNodes = new ArrayList<>(edgeNodes);
        }

        return r;
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

    private Timestamp parseDateTime(String str) {
        if (str == null || str.isBlank() || "—".equals(str.trim()) || "待发布".equals(str.trim())) {
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

    private String formatTimestamp(Timestamp ts) {
        if (ts == null) return null;
        return ts.toInstant().atZone(ZONE).format(DT_FORMAT);
    }

    private static class RuleRowDto {
        Long id;
        String ruleCode;
        String ruleName;
        String category;
        String ruleType;
        String scopeDesc;
        String activeVersion;
        String platformVersion;
        String riskLevelCode;
        String statusCode;
        boolean highRisk;
        String ownerName;
        String approverName;
        Timestamp effectiveAt;
        String description;
        String relatedModulesJson;
        boolean isDeleted;
        OffsetDateTime createdAt;
        Timestamp updatedAt;
    }

    private static class VersionRowDto {
        String versionNo;
        String statusCode;
        String paramsJson;
        String actionsJson;
        String sourceVersion;
        String versionNote;
        String versionDiffsJson;
        String authorName;
        Timestamp createdAt;
    }

    private class RuleRowMapper implements RowMapper<RuleRowDto> {
        @Override
        public RuleRowDto mapRow(ResultSet rs, int rowNum) throws SQLException {
            RuleRowDto d = new RuleRowDto();
            d.id = rs.getLong("id");
            d.ruleCode = rs.getString("rule_code");
            d.ruleName = rs.getString("rule_name");
            d.category = rs.getString("category");
            d.ruleType = rs.getString("rule_type");
            d.scopeDesc = rs.getString("scope_desc");
            d.activeVersion = rs.getString("active_version");
            d.platformVersion = rs.getString("platform_version");
            d.riskLevelCode = rs.getString("risk_level_code");
            d.statusCode = rs.getString("status_code");
            d.highRisk = rs.getBoolean("high_risk");
            d.ownerName = rs.getString("owner_name_snapshot");
            d.approverName = rs.getString("approver_name_snapshot");
            d.effectiveAt = rs.getTimestamp("effective_at");
            d.description = rs.getString("description");
            d.relatedModulesJson = rs.getString("related_modules");
            d.isDeleted = rs.getBoolean("is_deleted");

            Timestamp createdTs = rs.getTimestamp("created_at");
            if (createdTs != null) {
                d.createdAt = createdTs.toInstant().atZone(ZONE).toOffsetDateTime();
            }
            d.updatedAt = rs.getTimestamp("updated_at");
            return d;
        }
    }
}
