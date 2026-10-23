package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.AiModelInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 openGauss (PostgreSQL 兼容模式) 的 AI 工业模型配置持久化仓储实现。
 * 对应物理表 {@code safety.ai_model}（软删除；防删触发器约束下不提供物理删除与 Demo 清场）。
 *
 * <p>与内存实现行为对等：表为空时构造自动补种 5 款预置模型（种子与内存版共用
 * {@link AiModelSeedData}），保证 server profile 生产环境（Demo 灌种关闭）下
 * /ai/models 页面开箱可用；模型选用与参数调优重启不丢。
 * {@code is_selected} 排他性由应用层 selectModel 循环保证，仓储不加硬约束。</p>
 */
@Repository
@Profile("server")
public class JdbcAiModelRepository implements AiModelRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcAiModelRepository.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** findAll 契约：选用中的模型在前，其余按 model_code 升序。 */
    private static final String BASE_SELECT = """
            SELECT model_code, model_name, task_type, version_no, engine_type,
                   accuracy_map, latency_ms, fps, confidence_threshold, iou_threshold,
                   is_selected, status_code, weight_size, supported_classes, description,
                   is_deleted, updated_at
            FROM safety.ai_model
            """;

    private static final String SELECT_ALL =
            BASE_SELECT + " WHERE is_deleted = false ORDER BY is_selected DESC, model_code ASC";

    private static final String SELECT_BY_ID =
            BASE_SELECT + " WHERE model_code = :modelCode AND is_deleted = false";

    private static final String SELECT_SELECTED =
            BASE_SELECT + " WHERE is_deleted = false AND is_selected ORDER BY model_code ASC LIMIT 1";

    private static final String COUNT_ALL = "SELECT count(*) FROM safety.ai_model";

    private static final String UPDATE_MODEL = """
            UPDATE safety.ai_model SET
                model_name = :modelName,
                task_type = :taskType,
                version_no = :versionNo,
                engine_type = :engineType,
                accuracy_map = :accuracyMap,
                latency_ms = :latencyMs,
                fps = :fps,
                confidence_threshold = :confidenceThreshold,
                iou_threshold = :iouThreshold,
                is_selected = :isSelected,
                status_code = :statusCode,
                weight_size = :weightSize,
                supported_classes = :supportedClasses,
                description = :description,
                is_deleted = :isDeleted,
                updated_at = :updatedAt
            WHERE model_code = :modelCode
            """;

    private static final String INSERT_MODEL = """
            INSERT INTO safety.ai_model (
                model_code, model_name, task_type, version_no, engine_type,
                accuracy_map, latency_ms, fps, confidence_threshold, iou_threshold,
                is_selected, status_code, weight_size, supported_classes, description,
                is_deleted, updated_at, created_at
            ) VALUES (
                :modelCode, :modelName, :taskType, :versionNo, :engineType,
                :accuracyMap, :latencyMs, :fps, :confidenceThreshold, :iouThreshold,
                :isSelected, :statusCode, :weightSize, :supportedClasses, :description,
                :isDeleted, :updatedAt, now()
            )
            """;

    private static final String SOFT_DELETE = """
            UPDATE safety.ai_model SET is_deleted = true, updated_at = now()
            WHERE model_code = :modelCode AND is_deleted = false
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RowMapper<AiModelInfo> rowMapper;

    public JdbcAiModelRepository(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.rowMapper = new AiModelRowMapper();
        seedPresetsIfEmpty();
    }

    /** 空表自动补种预置模型；失败仅告警降级（页面空列表），不阻断服务启动。 */
    private void seedPresetsIfEmpty() {
        try {
            Long count = jdbcTemplate.queryForObject(COUNT_ALL, Collections.emptyMap(), Long.class);
            if (count != null && count > 0) {
                return;
            }
            OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZONE));
            for (AiModelInfo preset : AiModelSeedData.presets(now)) {
                try {
                    jdbcTemplate.update(INSERT_MODEL, createParams(preset));
                } catch (DuplicateKeyException ignored) {
                    // 并发启动竞态：另一实例已补种
                }
            }
            log.info("safety.ai_model 空表已自动补种 {} 款预置工业模型", AiModelSeedData.presets(now).size());
        } catch (Exception ex) {
            log.warn("safety.ai_model 预置模型补种检查失败（表可能尚未建立），降级为空配置: {}", ex.getMessage());
        }
    }

    @Override
    public List<AiModelInfo> findAll() {
        List<AiModelInfo> list = jdbcTemplate.query(SELECT_ALL, Collections.emptyMap(), rowMapper);
        return list.stream().map(AiModelInfo::copy).toList();
    }

    @Override
    public Optional<AiModelInfo> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            AiModelInfo m = jdbcTemplate.queryForObject(
                    SELECT_BY_ID, Map.of("modelCode", id.trim()), rowMapper);
            return Optional.ofNullable(m).map(AiModelInfo::copy);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AiModelInfo> findSelected() {
        try {
            AiModelInfo m = jdbcTemplate.queryForObject(
                    SELECT_SELECTED, Collections.emptyMap(), rowMapper);
            return Optional.ofNullable(m).map(AiModelInfo::copy);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public AiModelInfo save(AiModelInfo model) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(model.id, "model.id must not be null");

        if (model.updatedAt == null) {
            model.updatedAt = OffsetDateTime.now(clock.withZone(ZONE));
        }
        AiModelInfo toPersist = model.copy();
        MapSqlParameterSource params = createParams(toPersist);

        int updated = jdbcTemplate.update(UPDATE_MODEL, params);
        if (updated == 0) {
            try {
                jdbcTemplate.update(INSERT_MODEL, params);
            } catch (DuplicateKeyException race) {
                if (jdbcTemplate.update(UPDATE_MODEL, params) == 0) {
                    throw race;
                }
            }
        }
        return toPersist.copy();
    }

    @Override
    public boolean deleteById(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("modelCode", id.trim());
        return jdbcTemplate.update(SOFT_DELETE, params) > 0;
    }

    private MapSqlParameterSource createParams(AiModelInfo m) {
        return new MapSqlParameterSource()
                .addValue("modelCode", m.id)
                .addValue("modelName", m.name)
                .addValue("taskType", m.taskType)
                .addValue("versionNo", m.version)
                .addValue("engineType", m.engine)
                .addValue("accuracyMap", m.accuracyMap)
                .addValue("latencyMs", m.latencyMs)
                .addValue("fps", m.fps)
                .addValue("confidenceThreshold", m.confidenceThreshold)
                .addValue("iouThreshold", m.iouThreshold)
                .addValue("isSelected", m.isSelected)
                .addValue("statusCode", m.status)
                .addValue("weightSize", m.weightSize)
                .addValue("supportedClasses", toJsonb(m.supportedClasses))
                .addValue("description", m.description)
                .addValue("isDeleted", m.isDeleted)
                .addValue("updatedAt", m.updatedAt == null ? null : Timestamp.from(m.updatedAt.toInstant()));
    }

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

    private class AiModelRowMapper implements RowMapper<AiModelInfo> {
        @Override
        public AiModelInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
            AiModelInfo m = new AiModelInfo();
            m.id = rs.getString("model_code");
            m.name = rs.getString("model_name");
            m.taskType = rs.getString("task_type");
            m.version = rs.getString("version_no");
            m.engine = rs.getString("engine_type");
            m.accuracyMap = rs.getDouble("accuracy_map");
            m.latencyMs = rs.getDouble("latency_ms");
            m.fps = rs.getDouble("fps");
            m.confidenceThreshold = rs.getDouble("confidence_threshold");
            m.iouThreshold = rs.getDouble("iou_threshold");
            m.isSelected = rs.getBoolean("is_selected");
            m.status = rs.getString("status_code");
            m.weightSize = rs.getString("weight_size");
            m.supportedClasses = fromJsonbClasses(rs.getString("supported_classes"));
            m.description = rs.getString("description");
            m.isDeleted = rs.getBoolean("is_deleted");
            Timestamp updated = rs.getTimestamp("updated_at");
            m.updatedAt = updated == null ? null : updated.toInstant().atZone(ZONE).toOffsetDateTime();
            return m;
        }
    }

    private List<String> fromJsonbClasses(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return new java.util.ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("JSONB 反序列化失败: " + json, ex);
        }
    }
}
