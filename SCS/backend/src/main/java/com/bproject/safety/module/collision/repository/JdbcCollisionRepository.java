package com.bproject.safety.module.collision.repository;

import com.bproject.safety.module.collision.model.CollisionRiskLevels;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.model.SensorHealth;
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
 * 基于 openGauss (PostgreSQL 兼容模式) 的防碰撞设备持久化仓储实现。
 * 对应物理表 {@code safety.collision_device}。
 *
 * <p>遵循 copy-on-read / copy-on-write 语义，提供与 InMemory 实现完全一致的业务表现。</p>
 */
@Repository
@Profile("server")
public class JdbcCollisionRepository implements CollisionRepository {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String BASE_SELECT = """
            SELECT device_code, device_name, device_type, area_code, status_code, speed, direction,
                   control_status, communication_status, radar_status, risk_level_code, sensor_health_code,
                   related_device_code, pos_x, pos_y, last_updated_at
            FROM safety.collision_device
            """;

    private static final String SELECT_ALL = BASE_SELECT + " WHERE is_deleted = false ORDER BY device_code ASC";

    private static final String SELECT_BY_CODE = BASE_SELECT + " WHERE device_code = :deviceCode AND is_deleted = false";

    private static final String UPDATE_DEVICE = """
            UPDATE safety.collision_device
            SET device_name = :deviceName,
                device_type = :deviceType,
                area_code = :areaCode,
                status_code = :statusCode,
                speed = :speed,
                direction = :direction,
                control_status = :controlStatus,
                communication_status = :communicationStatus,
                radar_status = :radarStatus,
                risk_level_code = :riskLevelCode,
                sensor_health_code = :sensorHealthCode,
                related_device_code = :relatedDeviceCode,
                pos_x = :posX,
                pos_y = :posY,
                last_updated_at = :lastUpdatedAt,
                updated_at = now()
            WHERE device_code = :deviceCode AND is_deleted = false
            """;

    private static final String INSERT_DEVICE = """
            INSERT INTO safety.collision_device (
                device_code, device_name, device_type, area_code, status_code, speed, direction,
                control_status, communication_status, radar_status, risk_level_code, sensor_health_code,
                related_device_code, pos_x, pos_y, last_updated_at, is_deleted, created_at, updated_at
            ) VALUES (
                :deviceCode, :deviceName, :deviceType, :areaCode, :statusCode, :speed, :direction,
                :controlStatus, :communicationStatus, :radarStatus, :riskLevelCode, :sensorHealthCode,
                :relatedDeviceCode, :posX, :posY, :lastUpdatedAt, false, now(), now()
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final DemoMasterData masterData;
    private final Clock clock;
    private final RowMapper<DemoCollisionDevice> rowMapper;

    public JdbcCollisionRepository(NamedParameterJdbcTemplate jdbcTemplate,
                                   DemoMasterData masterData,
                                   Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.masterData = masterData;
        this.clock = clock;
        this.rowMapper = new CollisionDeviceRowMapper();
    }

    @Override
    public List<DemoCollisionDevice> findAll() {
        List<DemoCollisionDevice> list = jdbcTemplate.query(SELECT_ALL, Collections.emptyMap(), rowMapper);
        return list.stream().map(DemoCollisionDevice::copy).toList();
    }

    @Override
    public Optional<DemoCollisionDevice> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            DemoCollisionDevice device = jdbcTemplate.queryForObject(
                    SELECT_BY_CODE,
                    Map.of("deviceCode", id.trim()),
                    rowMapper
            );
            return Optional.ofNullable(device).map(DemoCollisionDevice::copy);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    public DemoCollisionDevice save(DemoCollisionDevice device) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(device.id, "device.id must not be null");

        DemoCollisionDevice toPersist = device.copy();
        MapSqlParameterSource params = createParams(toPersist);

        int updated = jdbcTemplate.update(UPDATE_DEVICE, params);
        if (updated == 0) {
            jdbcTemplate.update(INSERT_DEVICE, params);
        }

        return toPersist.copy();
    }

    private MapSqlParameterSource createParams(DemoCollisionDevice d) {
        String areaCode = null;
        if (d.area != null && !d.area.isBlank()) {
            areaCode = masterData.areas().stream()
                    .filter(a -> a.name().equals(d.area) || a.code().equals(d.area))
                    .map(DemoMasterData.DemoArea::code)
                    .findFirst()
                    .orElse(d.area);
        }

        OffsetDateTime lastUpdated = null;
        if (d.lastUpdated != null && !d.lastUpdated.isBlank()) {
            try {
                lastUpdated = OffsetDateTime.parse(d.lastUpdated);
            } catch (Exception ignored) {
                lastUpdated = OffsetDateTime.now(clock);
            }
        } else {
            lastUpdated = OffsetDateTime.now(clock);
        }
        Timestamp lastUpdatedTs = Timestamp.from(lastUpdated.toInstant());

        return new MapSqlParameterSource()
                .addValue("deviceCode", d.id)
                .addValue("deviceName", d.name != null ? d.name : d.id)
                .addValue("deviceType", d.type != null ? d.type : "未知类型")
                .addValue("areaCode", areaCode)
                .addValue("statusCode", d.status != null ? d.status : "NORMAL")
                .addValue("speed", d.speed)
                .addValue("direction", d.direction)
                .addValue("controlStatus", d.controlStatus)
                .addValue("communicationStatus", d.communication)
                .addValue("radarStatus", d.radarStatus)
                .addValue("riskLevelCode", d.riskCode != null ? d.riskCode : CollisionRiskLevels.SAFE)
                .addValue("sensorHealthCode", d.healthCode != null ? d.healthCode : SensorHealth.NORMAL)
                .addValue("relatedDeviceCode", d.relatedEquipmentId)
                .addValue("posX", d.x)
                .addValue("posY", d.y)
                .addValue("lastUpdatedAt", lastUpdatedTs);
    }

    private class CollisionDeviceRowMapper implements RowMapper<DemoCollisionDevice> {
        @Override
        public DemoCollisionDevice mapRow(ResultSet rs, int rowNum) throws SQLException {
            DemoCollisionDevice d = new DemoCollisionDevice();
            d.id = rs.getString("device_code");
            d.name = rs.getString("device_name");
            d.type = rs.getString("device_type");

            String areaCode = rs.getString("area_code");
            if (areaCode != null) {
                d.area = masterData.areas().stream()
                        .filter(a -> a.code().equals(areaCode))
                        .map(DemoMasterData.DemoArea::name)
                        .findFirst()
                        .orElse(areaCode);
            }

            d.status = rs.getString("status_code");
            d.speed = rs.getDouble("speed");
            d.direction = rs.getString("direction");
            d.controlStatus = rs.getString("control_status");
            d.communication = rs.getString("communication_status");
            d.radarStatus = rs.getString("radar_status");
            d.riskCode = rs.getString("risk_level_code");
            d.healthCode = rs.getString("sensor_health_code");
            d.relatedEquipmentId = rs.getString("related_device_code");
            d.x = rs.getDouble("pos_x");
            d.y = rs.getDouble("pos_y");

            Timestamp ts = rs.getTimestamp("last_updated_at");
            if (ts != null) {
                d.lastUpdated = ts.toInstant().atZone(ZONE).toOffsetDateTime().toString();
            }

            return d;
        }
    }
}
