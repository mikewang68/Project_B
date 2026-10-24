package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.OpsEventLog;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 openGauss (PostgreSQL 兼容模式) 的运维事件日志持久化仓储实现。
 * 对应物理表 {@code safety.ops_event_log}（append-only，物理主键 seq_ops_event_log）。
 *
 * <p>与内存实现的语义差异（受表约束约束，均为有意为之）：
 * 内存版为 300 条环形缓冲，本实现 append-only 不裁剪（表挂 fn_prevent_physical_delete
 * 触发器），{@code recent()} 的 limit 上限 300 保证读取侧有界；行主键由数据库序列生成，
 * 读回 id 为数值串（内存版为 OPS-LOG-nanoTime 前缀串），仅作展示键使用；
 * 展示字段 {@code time} 不落库，读回时由 occurred_at 按 Asia/Shanghai 重新生成。</p>
 */
@Repository
@Profile("server")
public class JdbcOpsEventLogRepository implements OpsEventLogRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 与内存实现一致：limit 缺省 50、上限 300（环形缓冲容量口径）。 */
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 300;

    private static final String INSERT_LOG = """
            INSERT INTO safety.ops_event_log (
                edge_node_code, log_level, event_type, message, occurred_at
            ) VALUES (
                :nodeId, :level, :type, :text, :occurredAt
            )
            RETURNING id
            """;

    private static final String SELECT_RECENT = """
            SELECT id, edge_node_code, log_level, event_type, message, occurred_at
            FROM safety.ops_event_log
            WHERE (:nodeId IS NULL OR :nodeId = '' OR edge_node_code = :nodeId)
              AND (:level IS NULL OR :level = '' OR LOWER(log_level) = LOWER(:level))
              AND (:type IS NULL OR :type = '' OR event_type = :type)
              AND (CAST(:fromAt AS timestamptz) IS NULL OR occurred_at >= :fromAt)
              AND (CAST(:toAt AS timestamptz) IS NULL OR occurred_at <= :toAt)
            ORDER BY occurred_at DESC NULLS LAST, id DESC
            LIMIT :maxRows
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<OpsEventLog> rowMapper;

    public JdbcOpsEventLogRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = new OpsEventLogRowMapper();
    }

    @Override
    public OpsEventLog append(OpsEventLog log) {
        Objects.requireNonNull(log, "log must not be null");
        Objects.requireNonNull(log.ts, "log.ts must not be null（occurred_at 列 NOT NULL）");

        OpsEventLog persisted = log.copy();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("nodeId", blankToNull(persisted.nodeId))
                .addValue("level", persisted.level)
                .addValue("type", persisted.type)
                .addValue("text", persisted.text)
                .addValue("occurredAt", Timestamp.from(persisted.ts.toInstant()));

        Long generatedId = jdbcTemplate.queryForObject(INSERT_LOG, params, Long.class);
        OpsEventLog returned = persisted.copy();
        if (generatedId != null) {
            returned.id = String.valueOf(generatedId);
        }
        return returned;
    }

    @Override
    public List<OpsEventLog> recent(Integer limit, String nodeId, String level, String type,
                                    String from, String to) {
        int maxRows = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        OffsetDateTime fromAt = parseIso(from);
        OffsetDateTime toAt = parseIso(to);

        // 可选参数显式声明 SQL 类型：绑定为 null 时 openGauss 无法从 "? IS NULL" 上下文推断类型
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("nodeId", normalizeFilter(nodeId), Types.VARCHAR)
                .addValue("level", normalizeFilter(level), Types.VARCHAR)
                .addValue("type", normalizeFilter(type), Types.VARCHAR)
                .addValue("fromAt", fromAt == null ? null : Timestamp.from(fromAt.toInstant()), Types.TIMESTAMP)
                .addValue("toAt", toAt == null ? null : Timestamp.from(toAt.toInstant()), Types.TIMESTAMP)
                .addValue("maxRows", maxRows);

        List<OpsEventLog> list = jdbcTemplate.query(SELECT_RECENT, params, rowMapper);
        return list.stream().map(OpsEventLog::copy).toList();
    }

    /** 与内存实现一致：非法 ISO 时间串静默忽略（不过滤）。 */
    private static OffsetDateTime parseIso(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String normalizeFilter(String filter) {
        return filter == null || filter.isBlank() ? null : filter.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private class OpsEventLogRowMapper implements RowMapper<OpsEventLog> {
        @Override
        public OpsEventLog mapRow(ResultSet rs, int rowNum) throws SQLException {
            OpsEventLog log = new OpsEventLog();
            long id = rs.getLong("id");
            log.id = rs.wasNull() ? null : String.valueOf(id);
            log.nodeId = rs.getString("edge_node_code");
            log.level = rs.getString("log_level");
            log.type = rs.getString("event_type");
            log.text = rs.getString("message");
            Timestamp occurred = rs.getTimestamp("occurred_at");
            log.ts = occurred == null ? null : occurred.toInstant().atZone(ZONE).toOffsetDateTime();
            log.time = log.ts == null ? null : log.ts.format(HMS);
            return log;
        }
    }
}
