package com.bproject.safety.module.personnel.repository;

import com.bproject.safety.module.personnel.model.BraceletStatuses;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.model.PersonRiskLevels;
import com.bproject.safety.module.personnel.model.PersonStatuses;
import com.bproject.safety.support.masterdata.DemoMasterData;
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
import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 基于 openGauss (PostgreSQL 兼容模式) 的现场人员台账持久化仓储实现。
 * 对应物理表 {@code safety.safety_personnel}。
 *
 * <p>遵循 copy-on-read / copy-on-write 语义，提供与 InMemory 实现完全一致的业务表现。</p>
 */
@Repository
@Profile("server")
public class JdbcPersonnelRepository implements PersonnelRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String BASE_SELECT = """
            SELECT personnel_code, job_no, person_name, team_code, area_code, bracelet_code,
                   battery_pct, online_status_code, bracelet_status_code, person_risk_code,
                   positioning_quality, pos_x, pos_y, coordinate_text, last_seen_at,
                   is_deleted, created_at, updated_at
            FROM safety.safety_personnel
            """;

    private static final String SELECT_ALL = BASE_SELECT + " WHERE is_deleted = false ORDER BY personnel_code ASC";

    private static final String SELECT_BY_CODE = BASE_SELECT + " WHERE personnel_code = :personnelCode AND is_deleted = false";

    private static final String UPDATE_PERSONNEL = """
            UPDATE safety.safety_personnel
            SET job_no = :jobNo,
                person_name = :personName,
                team_code = :teamCode,
                area_code = :areaCode,
                bracelet_code = :braceletCode,
                battery_pct = :batteryPct,
                online_status_code = :onlineStatusCode,
                bracelet_status_code = :braceletStatusCode,
                person_risk_code = :personRiskCode,
                positioning_quality = :positioningQuality,
                pos_x = :posX,
                pos_y = :posY,
                coordinate_text = :coordinateText,
                last_seen_at = :lastSeenAt,
                updated_at = now()
            WHERE personnel_code = :personnelCode AND is_deleted = false
            """;

    private static final String INSERT_PERSONNEL = """
            INSERT INTO safety.safety_personnel (
                personnel_code, job_no, person_name, team_code, area_code, bracelet_code,
                battery_pct, online_status_code, bracelet_status_code, person_risk_code,
                positioning_quality, pos_x, pos_y, coordinate_text, last_seen_at,
                enabled, is_deleted, created_at, updated_at
            ) VALUES (
                :personnelCode, :jobNo, :personName, :teamCode, :areaCode, :braceletCode,
                :batteryPct, :onlineStatusCode, :braceletStatusCode, :personRiskCode,
                :positioningQuality, :posX, :posY, :coordinateText, :lastSeenAt,
                true, false, now(), now()
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DemoMasterData masterData;
    private final Clock clock;
    private final RowMapper<DemoPersonnel> rowMapper;

    public JdbcPersonnelRepository(NamedParameterJdbcTemplate jdbcTemplate,
                                   DemoMasterData masterData,
                                   Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.masterData = masterData;
        this.clock = clock;
        this.rowMapper = new PersonnelRowMapper();
    }

    @Override
    public List<DemoPersonnel> findAll() {
        List<DemoPersonnel> list = jdbcTemplate.query(SELECT_ALL, Collections.emptyMap(), rowMapper);
        return list.stream().map(DemoPersonnel::copy).toList();
    }

    @Override
    public Optional<DemoPersonnel> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            DemoPersonnel person = jdbcTemplate.queryForObject(
                    SELECT_BY_CODE,
                    Map.of("personnelCode", id.trim()),
                    rowMapper
            );
            return Optional.ofNullable(person).map(DemoPersonnel::copy);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public DemoPersonnel save(DemoPersonnel person) {
        Objects.requireNonNull(person, "person must not be null");
        Objects.requireNonNull(person.id, "person.id must not be null");

        DemoPersonnel toPersist = person.copy();
        MapSqlParameterSource params = createParams(toPersist);

        int updated = jdbcTemplate.update(UPDATE_PERSONNEL, params);
        if (updated == 0) {
            jdbcTemplate.update(INSERT_PERSONNEL, params);
        }

        return toPersist.copy();
    }

    private MapSqlParameterSource createParams(DemoPersonnel p) {
        String teamCode = null;
        if (p.team != null && !p.team.isBlank()) {
            teamCode = masterData.teams().stream()
                    .filter(t -> t.name().equals(p.team) || t.code().equals(p.team))
                    .map(DemoMasterData.DemoTeam::code)
                    .findFirst()
                    .orElse(p.team);
        }

        String areaCode = null;
        if (p.area != null && !p.area.isBlank()) {
            areaCode = masterData.areas().stream()
                    .filter(a -> a.name().equals(p.area) || a.code().equals(p.area))
                    .map(DemoMasterData.DemoArea::code)
                    .findFirst()
                    .orElse(p.area);
        }

        Timestamp lastSeenTs = Timestamp.from(OffsetDateTime.now(clock).toInstant());
        if (p.updatedAt != null) {
            lastSeenTs = Timestamp.from(p.updatedAt.toInstant());
        }

        return new MapSqlParameterSource()
                .addValue("personnelCode", p.id)
                .addValue("jobNo", p.jobNo)
                .addValue("personName", p.name != null ? p.name : p.id)
                .addValue("teamCode", teamCode)
                .addValue("areaCode", areaCode)
                .addValue("braceletCode", p.braceletId)
                .addValue("batteryPct", p.battery)
                .addValue("onlineStatusCode", p.statusCode != null ? p.statusCode : PersonStatuses.ONLINE)
                .addValue("braceletStatusCode", p.braceletStatusCode != null ? p.braceletStatusCode : BraceletStatuses.ONLINE)
                .addValue("personRiskCode", p.riskCode != null ? p.riskCode : PersonRiskLevels.NORMAL)
                .addValue("positioningQuality", p.positioningQuality != null ? p.positioningQuality : "优秀")
                .addValue("posX", p.x)
                .addValue("posY", p.y)
                .addValue("coordinateText", p.coordinate)
                .addValue("lastSeenAt", lastSeenTs);
    }

    private class PersonnelRowMapper implements RowMapper<DemoPersonnel> {
        @Override
        public DemoPersonnel mapRow(ResultSet rs, int rowNum) throws SQLException {
            DemoPersonnel p = new DemoPersonnel();
            p.id = rs.getString("personnel_code");
            p.jobNo = rs.getString("job_no");
            p.name = rs.getString("person_name");

            String teamCode = rs.getString("team_code");
            if (teamCode != null) {
                p.team = masterData.teams().stream()
                        .filter(t -> t.code().equals(teamCode) || t.name().equals(teamCode))
                        .map(DemoMasterData.DemoTeam::name)
                        .findFirst()
                        .orElse(teamCode);
            }

            String areaCode = rs.getString("area_code");
            if (areaCode != null) {
                p.area = masterData.areas().stream()
                        .filter(a -> a.code().equals(areaCode) || a.name().equals(areaCode))
                        .map(DemoMasterData.DemoArea::name)
                        .findFirst()
                        .orElse(areaCode);
            }

            p.braceletId = rs.getString("bracelet_code");
            p.battery = rs.getInt("battery_pct");
            p.statusCode = rs.getString("online_status_code");
            p.braceletStatusCode = rs.getString("bracelet_status_code");
            p.riskCode = rs.getString("person_risk_code");
            p.positioningQuality = rs.getString("positioning_quality");
            p.x = rs.getDouble("pos_x");
            p.y = rs.getDouble("pos_y");
            p.coordinate = rs.getString("coordinate_text");
            p.isDeleted = rs.getBoolean("is_deleted");

            Timestamp ts = rs.getTimestamp("last_seen_at");
            if (ts != null) {
                p.updatedAt = ts.toInstant().atZone(ZONE).toOffsetDateTime();
                p.lastUpdated = "刚刚";
            } else {
                Timestamp updated = rs.getTimestamp("updated_at");
                if (updated != null) {
                    p.updatedAt = updated.toInstant().atZone(ZONE).toOffsetDateTime();
                }
                p.lastUpdated = "刚刚";
            }

            if (PersonStatuses.OFFLINE.equals(p.statusCode)) {
                p.state = "offline";
            } else if (PersonRiskLevels.HIGH.equals(p.riskCode)) {
                p.state = "danger";
            } else if (PersonRiskLevels.ATTENTION.equals(p.riskCode)) {
                p.state = "warning";
            } else {
                p.state = "normal";
            }

            return p;
        }
    }
}
