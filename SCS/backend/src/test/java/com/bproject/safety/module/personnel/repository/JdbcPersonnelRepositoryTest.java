package com.bproject.safety.module.personnel.repository;

import com.bproject.safety.module.personnel.model.BraceletStatuses;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.model.PersonRiskLevels;
import com.bproject.safety.module.personnel.model.PersonStatuses;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
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

@DisplayName("JdbcPersonnelRepository: 契约测试与参数映射")
class JdbcPersonnelRepositoryTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DemoMasterData masterData;
    private Clock clock;
    private JdbcPersonnelRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        masterData = new DemoMasterData();
        clock = Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneOffset.ofHours(8));
        repository = new JdbcPersonnelRepository(jdbcTemplate, masterData, clock);
    }

    @Test
    @DisplayName("save: 参数映射完整性测试（team/area 映射、状态与定位坐标）")
    void testSaveParameterMapping() {
        DemoPersonnel p = new DemoPersonnel();
        p.id = "P-TEST-01";
        p.jobNo = "J-1001";
        p.name = "张三";
        p.team = "装卸一班"; // 映射为 TEAM_LOADING_1
        p.area = "装卸区 A"; // 映射为 AREA_LOADING_A
        p.braceletId = "WB-001";
        p.battery = 85;
        p.statusCode = PersonStatuses.ONLINE;
        p.braceletStatusCode = BraceletStatuses.ONLINE;
        p.riskCode = PersonRiskLevels.NORMAL;
        p.positioningQuality = "优秀";
        p.x = 120.5;
        p.y = 88.0;
        p.coordinate = "A-01 / 120.5, 88.0";
        p.updatedAt = OffsetDateTime.now(clock);

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        DemoPersonnel saved = repository.save(p);
        assertThat(saved).isNotNull();
        assertThat(saved.id).isEqualTo("P-TEST-01");

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), captor.capture());

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("personnelCode")).isEqualTo("P-TEST-01");
        assertThat(params.getValue("jobNo")).isEqualTo("J-1001");
        assertThat(params.getValue("personName")).isEqualTo("张三");
        assertThat(params.getValue("teamCode")).isEqualTo(DemoMasterData.TEAM_LOADING_1);
        assertThat(params.getValue("areaCode")).isEqualTo(DemoMasterData.AREA_LOADING_A);
        assertThat(params.getValue("braceletCode")).isEqualTo("WB-001");
        assertThat(params.getValue("batteryPct")).isEqualTo(85);
        assertThat(params.getValue("onlineStatusCode")).isEqualTo(PersonStatuses.ONLINE);
        assertThat(params.getValue("braceletStatusCode")).isEqualTo(BraceletStatuses.ONLINE);
        assertThat(params.getValue("personRiskCode")).isEqualTo(PersonRiskLevels.NORMAL);
        assertThat(params.getValue("posX")).isEqualTo(120.5);
        assertThat(params.getValue("posY")).isEqualTo(88.0);
        assertThat(params.getValue("coordinateText")).isEqualTo("A-01 / 120.5, 88.0");
    }

    @Test
    @DisplayName("save: 当 update 返回 0 时触发 insert 插入新人员")
    void testSaveTriggersInsertWhenUpdateMisses() {
        DemoPersonnel p = new DemoPersonnel();
        p.id = "P-NEW";
        p.name = "新人";

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(0)
                .thenReturn(1);

        DemoPersonnel saved = repository.save(p);
        assertThat(saved.id).isEqualTo("P-NEW");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));

        List<String> sqls = sqlCaptor.getAllValues();
        assertThat(sqls.get(0)).contains("UPDATE safety.safety_personnel");
        assertThat(sqls.get(1)).contains("INSERT INTO safety.safety_personnel");
    }

    @Test
    @DisplayName("findById: 存在返回人员，不存在返回 Optional.empty()")
    @SuppressWarnings("unchecked")
    void testFindById() {
        DemoPersonnel mockP = new DemoPersonnel();
        mockP.id = "P-01";
        mockP.name = "李四";

        when(jdbcTemplate.queryForObject(anyString(), eq(Map.of("personnelCode", "P-01")), any(RowMapper.class)))
                .thenReturn(mockP);

        Optional<DemoPersonnel> opt = repository.findById("P-01");
        assertThat(opt).isPresent();
        assertThat(opt.get().name).isEqualTo("李四");

        when(jdbcTemplate.queryForObject(anyString(), eq(Map.of("personnelCode", "P-MISSING")), any(RowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(repository.findById("P-MISSING")).isEmpty();
        assertThat(repository.findById(null)).isEmpty();
        assertThat(repository.findById("  ")).isEmpty();
    }

    @Test
    @DisplayName("findAll: 返回人员列表副本")
    @SuppressWarnings("unchecked")
    void testFindAll() {
        DemoPersonnel p1 = new DemoPersonnel();
        p1.id = "P-01";
        DemoPersonnel p2 = new DemoPersonnel();
        p2.id = "P-02";

        when(jdbcTemplate.query(anyString(), eq(Collections.emptyMap()), any(RowMapper.class)))
                .thenReturn(List.of(p1, p2));

        List<DemoPersonnel> list = repository.findAll();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).id).isEqualTo("P-01");
    }

    @Test
    @DisplayName("getStats: 单 SQL 聚合查询返回正确统计指标")
    @SuppressWarnings("unchecked")
    void testGetStats() {
        PersonnelRepository.PersonnelStats mockStats = new PersonnelRepository.PersonnelStats(10, 2, 1, 3);
        when(jdbcTemplate.queryForObject(anyString(), eq(Collections.emptyMap()), any(RowMapper.class)))
                .thenReturn(mockStats);

        PersonnelRepository.PersonnelStats stats = repository.getStats();
        assertThat(stats.online()).isEqualTo(10);
        assertThat(stats.abnormal()).isEqualTo(2);
        assertThat(stats.bandOffline()).isEqualTo(1);
        assertThat(stats.lowBattery()).isEqualTo(3);
    }

    @Test
    @DisplayName("save: 空指针校验")
    void testNullValidation() {
        assertThatThrownBy(() -> repository.save(null)).isInstanceOf(NullPointerException.class);
        DemoPersonnel noId = new DemoPersonnel();
        assertThatThrownBy(() -> repository.save(noId)).isInstanceOf(NullPointerException.class);
    }
}
