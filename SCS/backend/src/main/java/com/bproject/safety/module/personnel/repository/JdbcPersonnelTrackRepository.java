package com.bproject.safety.module.personnel.repository;

import com.bproject.safety.module.personnel.model.TrackPoint;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * openGauss 低频轨迹实现。表为 {@code safety.safety_personnel_track}，
 * 由 {@code db/v21/006-personnel-track.sql} 创建。
 *
 * <p>只存 10 秒降采样点，不改变“实时位置在内存、不逐点落库”的既有边界。
 * 不在本表上做范围列过滤：访问入口先通过 scoped 的人员仓库校验 {@code personnel_code}，
 * 轨迹行本身不构成新的主数据源。</p>
 */
@Repository
@Profile("server")
public class JdbcPersonnelTrackRepository implements PersonnelTrackRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcPersonnelTrackRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(String personnelCode, double x, double y, String area, OffsetDateTime recordedAt) {
        if (personnelCode == null || personnelCode.isBlank() || recordedAt == null) return;
        jdbcTemplate.update("""
                INSERT INTO safety.safety_personnel_track
                    (personnel_code, pos_x, pos_y, area_code, source, recorded_at)
                VALUES (:personnelCode, :x, :y, :area, 'uwb', :recordedAt)
                """, new MapSqlParameterSource()
                .addValue("personnelCode", personnelCode)
                .addValue("x", x)
                .addValue("y", y)
                .addValue("area", area)
                .addValue("recordedAt", Timestamp.from(recordedAt.toInstant())));
    }

    @Override
    public List<TrackPoint> query(String personnelCode, OffsetDateTime from, OffsetDateTime to, int limit) {
        if (personnelCode == null || personnelCode.isBlank() || from == null || to == null) {
            return List.of();
        }
        int capped = Math.max(1, Math.min(limit, 10_000));
        return jdbcTemplate.query("""
                        SELECT pos_x, pos_y, recorded_at
                        FROM safety.safety_personnel_track
                        WHERE personnel_code = :personnelCode
                          AND recorded_at BETWEEN :from AND :to
                        ORDER BY recorded_at ASC
                        LIMIT :limit
                        """,
                Map.of("personnelCode", personnelCode,
                        "from", Timestamp.from(from.toInstant()),
                        "to", Timestamp.from(to.toInstant()),
                        "limit", capped),
                (rs, rowNum) -> new TrackPoint(
                        rs.getDouble("pos_x"),
                        rs.getDouble("pos_y"),
                        rs.getTimestamp("recorded_at").toInstant().atZone(ZONE).format(HM)));
    }
}
