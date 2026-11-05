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
    private String scope(){return com.bproject.ehm.platform.security.EhmCentralScope.readPredicate();}
    private com.bproject.iam.context.ScopeTriple tuple(){return com.bproject.ehm.platform.security.EhmCentralScope.writeTuple(project());}
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String qualifiedTable;
    private final String schema;
    private final boolean autoDdl;
    @Value("${ehm.project.default-id:UNASSIGNED}") private String defaultProject="UNASSIGNED";
    private String project(){return com.bproject.ehm.platform.security.ProjectContext.require(defaultProject);}

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
                    project_id VARCHAR(64) NOT NULL,
                    region_id VARCHAR(64) NOT NULL DEFAULT 'UNASSIGNED',
                    subsystem_code VARCHAR(64) NOT NULL DEFAULT 'EHMS',
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(192) NOT NULL,
                    payload TEXT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (project_id, region_id, subsystem_code, aggregate_type, aggregate_id)
                )
                """.formatted(qualifiedTable));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ehm_aggregate_updated ON "
                + qualifiedTable + " (project_id, aggregate_type, updated_at DESC)");
    }

    public <T> Optional<T> find(String type, String id, Class<T> valueType) {
        List<String> rows = jdbc.query(
                "SELECT payload FROM " + qualifiedTable + " o WHERE project_id = ? AND aggregate_type = ? AND aggregate_id = ?"+scope(),
                (result, rowNum) -> result.getString(1), project(), type, id);
        if(rows.size()>1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Select a single region to resolve this aggregate ID");
        return rows.stream().findFirst().map(value -> decode(value, valueType));
    }

    public <T> List<T> findAll(String type, Class<T> valueType) {
        return jdbc.query(
                "SELECT payload FROM " + qualifiedTable + " o WHERE project_id = ? AND aggregate_type = ?"+scope()+" ORDER BY updated_at DESC",
                (result, rowNum) -> decode(result.getString(1), valueType), project(), type);
    }

    /** Restrict synthetic history reads to one point; timestamps are still filtered in the adapter. */
    public <T> List<T> findByIdPrefix(String type,String prefix,Class<T> valueType){
        String escaped=prefix.replace("!","!!").replace("%","!%").replace("_","!_");
        return jdbc.query("SELECT payload FROM "+qualifiedTable+" o WHERE project_id=? AND aggregate_type=? AND aggregate_id LIKE ? ESCAPE '!'"+scope(),
                (r,n)->decode(r.getString(1),valueType),project(),type,escaped+"%");
    }

    public <T> T save(String type, String id, T value) {
        var tuple=tuple();
        String payload = encode(value);
        Timestamp updatedAt = Timestamp.from(Instant.now());
        int updated = jdbc.update(
                "UPDATE " + qualifiedTable + " SET payload = ?, updated_at = ? "
                        + "WHERE project_id = ? AND aggregate_type = ? AND aggregate_id = ? AND region_id = ? AND subsystem_code = ?",
                payload, updatedAt, project(), type, id,tuple.regionId(),tuple.subsystemCode());
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO " + qualifiedTable
                                + " (project_id, aggregate_type, aggregate_id, payload, updated_at,region_id,subsystem_code) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        project(), type, id, payload, updatedAt,tuple.regionId(),tuple.subsystemCode());
            } catch (DuplicateKeyException concurrentInsert) {
                jdbc.update(
                        "UPDATE " + qualifiedTable + " SET payload = ?, updated_at = ? "
                                + "WHERE project_id = ? AND aggregate_type = ? AND aggregate_id = ? AND region_id = ? AND subsystem_code = ?",
                        payload, updatedAt, project(), type, id,tuple.regionId(),tuple.subsystemCode());
            }
        }
        return value;
    }

    public long count(String type) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + qualifiedTable + " o WHERE project_id = ? AND aggregate_type = ?"+scope(), Long.class, project(), type);
        return value == null ? 0 : value;
    }

    /** Insert-only helper for opt-in sample bundles: never resets an existing business aggregate. */
    public boolean insertIfAbsent(String type, String id, Object value) {
        var tuple=tuple();
        if (find(type, id, Object.class).isPresent()) return false;
        try {
            jdbc.update("INSERT INTO " + qualifiedTable
                    + " (project_id, aggregate_type, aggregate_id, payload, updated_at,region_id,subsystem_code) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    project(), type, id, encode(value), Timestamp.from(Instant.now()),tuple.regionId(),tuple.subsystemCode());
            return true;
        } catch (DuplicateKeyException race) {
            throw new IllegalStateException("Concurrent sample import; retry after the current import finishes");
        }
    }

    public void ping() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }

    /** Caller must hold a transaction. Serializes a persisted simulation controller across JVMs. */
    public void lockSimulationControl() {
        var tuple=tuple();
        jdbc.queryForObject("SELECT aggregate_id FROM " + qualifiedTable
                + " WHERE project_id=? AND region_id=? AND subsystem_code=?"
                + " AND aggregate_type='simulation-control' AND aggregate_id='control' FOR UPDATE",
                String.class, project(),tuple.regionId(),tuple.subsystemCode());
    }

    /** Retention applies ONLY to this simulator's raw samples; never business/audit records. */
    public int pruneSimulationSamples(Instant before) {
        if(!project().startsWith("SIM_"))throw new IllegalStateException("Not a simulation project");
        var tuple=tuple();
        return jdbc.update("DELETE FROM " + qualifiedTable
                + " WHERE project_id=? AND region_id=? AND subsystem_code=? AND aggregate_type='sample-telemetry'"
                + " AND aggregate_id LIKE 'SAMPLE-LIVE-%' AND updated_at<?",
                project(),tuple.regionId(),tuple.subsystemCode(),Timestamp.from(before));
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
