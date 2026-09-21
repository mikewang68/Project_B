package com.bproject.safety.module.collision.repository;

import com.bproject.safety.module.collision.model.CollisionRiskLevels;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.model.SensorHealth;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JdbcCollisionRepository: 参数映射、契约与隔离性测试")
class JdbcCollisionRepositoryTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DemoMasterData masterData;
    private Clock clock;
    private JdbcCollisionRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        masterData = new DemoMasterData();
        clock = Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
        repository = new JdbcCollisionRepository(jdbcTemplate, masterData, clock);
    }

    @Test
    @DisplayName("save: 参数映射完整性测试（areaName 转 areaCode，默认值与字段正确绑定）")
    void testSaveParameterMapping() {
        DemoCollisionDevice device = new DemoCollisionDevice();
        device.id = "VEH-07";
        device.name = "转运车辆 07";
        device.type = "转运车辆";
        device.area = "车辆通道"; // 中文区域名称，应映射为 VEHICLE_LANE
        device.status = "运行中";
        device.speed = 5.5;
        device.direction = "正北";
        device.controlStatus = "自动控制可用";
        device.communication = "在线 · 20ms";
        device.radarStatus = "正常";
        device.riskCode = CollisionRiskLevels.WARNING;
        device.healthCode = SensorHealth.NORMAL;
        device.relatedEquipmentId = "TIP-02";
        device.x = 35.0;
        device.y = 50.0;
        device.lastUpdated = OffsetDateTime.now(clock).toString();

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        DemoCollisionDevice saved = repository.save(device);

        assertThat(saved).isNotNull();
        assertThat(saved.id).isEqualTo("VEH-07");

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), captor.capture());

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("deviceCode")).isEqualTo("VEH-07");
        assertThat(params.getValue("deviceName")).isEqualTo("转运车辆 07");
        assertThat(params.getValue("deviceType")).isEqualTo("转运车辆");
        assertThat(params.getValue("areaCode")).isEqualTo(DemoMasterData.AREA_VEHICLE_LANE);
        assertThat(params.getValue("statusCode")).isEqualTo("运行中");
        assertThat(params.getValue("speed")).isEqualTo(5.5);
        assertThat(params.getValue("direction")).isEqualTo("正北");
        assertThat(params.getValue("controlStatus")).isEqualTo("自动控制可用");
        assertThat(params.getValue("communicationStatus")).isEqualTo("在线 · 20ms");
        assertThat(params.getValue("radarStatus")).isEqualTo("正常");
        assertThat(params.getValue("riskLevelCode")).isEqualTo(CollisionRiskLevels.WARNING);
        assertThat(params.getValue("sensorHealthCode")).isEqualTo(SensorHealth.NORMAL);
        assertThat(params.getValue("relatedDeviceCode")).isEqualTo("TIP-02");
        assertThat(params.getValue("posX")).isEqualTo(35.0);
        assertThat(params.getValue("posY")).isEqualTo(50.0);
        assertThat(params.getValue("lastUpdatedAt")).isNotNull();
    }

    @Test
    @DisplayName("save: 当 update 返回 0 时触发 insert 插入新设备")
    void testSaveTriggersInsertWhenUpdateMisses() {
        DemoCollisionDevice device = new DemoCollisionDevice();
        device.id = "VEH-NEW";
        device.name = "新设备";

        // 第一次 update 返回 0，第二次 insert 返回 1
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(0)
                .thenReturn(1);

        DemoCollisionDevice saved = repository.save(device);
        assertThat(saved.id).isEqualTo("VEH-NEW");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));

        List<String> sqls = sqlCaptor.getAllValues();
        assertThat(sqls.get(0)).contains("UPDATE safety.collision_device");
        assertThat(sqls.get(1)).contains("INSERT INTO safety.collision_device");
    }

    @Test
    @DisplayName("findById: 存在时正确返回副本，不存在时返回 Optional.empty()")
    @SuppressWarnings("unchecked")
    void testFindById() {
        DemoCollisionDevice mockDevice = new DemoCollisionDevice();
        mockDevice.id = "VEH-07";
        mockDevice.name = "转运车辆 07";

        when(jdbcTemplate.queryForObject(anyString(), eq(Map.of("deviceCode", "VEH-07")), any(RowMapper.class)))
                .thenReturn(mockDevice);

        Optional<DemoCollisionDevice> opt = repository.findById("VEH-07");
        assertThat(opt).isPresent();
        assertThat(opt.get().id).isEqualTo("VEH-07");

        when(jdbcTemplate.queryForObject(anyString(), eq(Map.of("deviceCode", "NOT-EXIST")), any(RowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<DemoCollisionDevice> missing = repository.findById("NOT-EXIST");
        assertThat(missing).isEmpty();

        assertThat(repository.findById(null)).isEmpty();
        assertThat(repository.findById("")).isEmpty();
    }

    @Test
    @DisplayName("copy 语义验证：修改返回对象不影响持久化状态")
    void testCopySemantics() {
        DemoCollisionDevice d = new DemoCollisionDevice();
        d.id = "VEH-07";
        d.name = "Original";

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        DemoCollisionDevice returned = repository.save(d);
        returned.name = "Modified";

        // 传入对象的 name 依然是 Original
        assertThat(d.name).isEqualTo("Original");
    }

    @Test
    @DisplayName("save: 空参数校验")
    void testNullValidation() {
        assertThatThrownBy(() -> repository.save(null))
                .isInstanceOf(NullPointerException.class);

        DemoCollisionDevice noId = new DemoCollisionDevice();
        assertThatThrownBy(() -> repository.save(noId))
                .isInstanceOf(NullPointerException.class);
    }
}
