package com.bproject.ehm.platform.persistence.opengauss;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * openGauss上的聚合存储基线。
 *
 * 当前Demo以JSON文本保存完整领域聚合，避免数据库切换侵入领域层和Web接口；聚合类型和主键
 * 独立建索引，后续可以按模块渐进拆成规范化业务表，而不影响现有Repository端口。
 */
@Repository
@Profile("server")
public class OpenGaussJsonStore {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String qualifiedTable;
    private final String schema;
    private final boolean autoDdl;

    public OpenGaussJsonStore(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${ehm.persistence.schema:ehm}") String schema,
            @Value("${ehm.persistence.table:aggregate_store}") String table,
            @Value("${ehm.persistence.auto-ddl:false}") boolean autoDdl
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.schema = safeIdentifier(schema, "schema");
        this.qualifiedTable = this.schema + "." + safeIdentifier(table, "table");
        this.autoDdl = autoDdl;
    }

    @PostConstruct
    void initialize() {
        if (!autoDdl) return;
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(192) NOT NULL,
                    payload TEXT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (aggregate_type, aggregate_id)
                )
                """.formatted(qualifiedTable));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ehm_aggregate_updated ON "
                + qualifiedTable + " (aggregate_type, updated_at DESC)");
    }

    public <T> Optional<T> find(String type, String id, Class<T> valueType) {
        List<String> rows = jdbc.query(
                "SELECT payload FROM " + qualifiedTable + " WHERE aggregate_type = ? AND aggregate_id = ?",
                (result, rowNum) -> result.getString(1), type, id);
        return rows.stream().findFirst().map(value -> decode(value, valueType));
    }

    public <T> List<T> findAll(String type, Class<T> valueType) {
        return jdbc.query(
                "SELECT payload FROM " + qualifiedTable + " WHERE aggregate_type = ? ORDER BY updated_at DESC",
                (result, rowNum) -> decode(result.getString(1), valueType), type);
    }

    public <T> T save(String type, String id, T value) {
        String payload = encode(value);
        Timestamp updatedAt = Timestamp.from(Instant.now());
        int updated = jdbc.update(
                "UPDATE " + qualifiedTable + " SET payload = ?, updated_at = ? "
                        + "WHERE aggregate_type = ? AND aggregate_id = ?",
                payload, updatedAt, type, id);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO " + qualifiedTable
                                + " (aggregate_type, aggregate_id, payload, updated_at) VALUES (?, ?, ?, ?)",
                        type, id, payload, updatedAt);
            } catch (DuplicateKeyException concurrentInsert) {
                jdbc.update(
                        "UPDATE " + qualifiedTable + " SET payload = ?, updated_at = ? "
                                + "WHERE aggregate_type = ? AND aggregate_id = ?",
                        payload, updatedAt, type, id);
            }
        }
        return value;
    }

    public long count(String type) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedTable + " WHERE aggregate_type = ?", Long.class, type);
        return value == null ? 0 : value;
    }

    public void ping() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("openGauss聚合序列化失败", exception);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("openGauss聚合反序列化失败：" + type.getSimpleName(), exception);
        }
    }

    private static String safeIdentifier(String value, String label) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("非法openGauss " + label + "标识符");
        }
        return value;
    }
}
