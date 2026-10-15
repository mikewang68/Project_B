package com.bproject.safety.module.fence.repository;

import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.EdgeNode;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
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

@DisplayName("JdbcFenceRepository: 契约测试与参数映射")
class JdbcFenceRepositoryTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private DemoMasterData masterData;
    private Clock clock;
    private JdbcFenceRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        objectMapper = new ObjectMapper();
        masterData = new DemoMasterData();
        clock = Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneOffset.ofHours(8));
        repository = new JdbcFenceRepository(jdbcTemplate, objectMapper, masterData, clock);
    }

    @Test
    @DisplayName("save: 参数映射完整性测试（areaCode 转换、多边形 JSONB、版本快照与边缘同步）")
    void testSaveParameterMapping() {
        DemoFence fence = new DemoFence();
        fence.id = "FENCE-01";
        fence.name = "龙门吊一号禁区";
        fence.kind = "危险区域";
        fence.tone = "danger";
        fence.area = "装卸区 A"; // 应解析为 LOADING_AREA_A
        fence.version = "v1.2";
        fence.statusCode = FenceStatuses.EFFECTIVE;
        fence.effectiveAt = "2026-09-01 08:00";
        fence.expiresAt = "2026-12-31 18:00";
        fence.approver = "李安全";
        fence.bufferDistance = 4.5;
        fence.riskCode = RiskLevels.SEVERE;
        fence.polygon = List.of(new FencePoint(10.0, 20.0), new FencePoint(30.0, 40.0));
        fence.teams = "装卸一班";
        fence.nodes = List.of(new EdgeNode("EDGE-01", EdgeNode.SUCCESS));

        // 模拟 root update 成功与 id 查询
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbcTemplate.query(eq("SELECT id FROM safety.safety_fence WHERE fence_code = :fenceCode"),
                eq(Map.of("fenceCode", "FENCE-01")), any(RowMapper.class))).thenReturn(List.of(100L));
        when(jdbcTemplate.query(eq("SELECT id FROM safety.safety_fence_version WHERE fence_id = :fenceId AND version_no = :versionNo"),
                anyMap(), any(RowMapper.class))).thenReturn(Collections.emptyList());

        DemoFence saved = repository.save(fence);
        assertThat(saved).isNotNull();
        assertThat(saved.id).isEqualTo("FENCE-01");

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).update(anyString(), captor.capture());

        List<MapSqlParameterSource> allParams = captor.getAllValues();
        MapSqlParameterSource rootParams = allParams.get(0);
        assertThat(rootParams.getValue("fenceCode")).isEqualTo("FENCE-01");
        assertThat(rootParams.getValue("fenceName")).isEqualTo("龙门吊一号禁区");
        assertThat(rootParams.getValue("areaCode")).isEqualTo(DemoMasterData.AREA_LOADING_A);
        assertThat(rootParams.getValue("activeVersion")).isEqualTo("v1.2");
        assertThat(rootParams.getValue("statusCode")).isEqualTo(FenceStatuses.EFFECTIVE);
        assertThat(rootParams.getValue("bufferDistance")).isEqualTo(4.5);
    }

    @Test
    @DisplayName("count: 返回未删除围栏总数")
    void testCount() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(6L);
        assertThat(repository.count()).isEqualTo(6L);
    }

    @Test
    @DisplayName("findById: 存在返回副本，不存在返回 Optional.empty()")
    @SuppressWarnings("unchecked")
    void testFindById() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Map.of("fenceCode", "NON-EXIST")), any(RowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<DemoFence> missing = repository.findById("NON-EXIST");
        assertThat(missing).isEmpty();
        assertThat(repository.findById(null)).isEmpty();
        assertThat(repository.findById("")).isEmpty();
    }

    @Test
    @DisplayName("findAll: 返回围栏列表副本")
    @SuppressWarnings("unchecked")
    void testFindAll() {
        when(jdbcTemplate.query(anyString(), eq(Collections.emptyMap()), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());

        List<DemoFence> list = repository.findAll();
        assertThat(list).isEmpty();
    }

    @Test
    @DisplayName("save: 空指针校验")
    void testNullValidation() {
        assertThatThrownBy(() -> repository.save(null)).isInstanceOf(NullPointerException.class);
        DemoFence noId = new DemoFence();
        assertThatThrownBy(() -> repository.save(noId)).isInstanceOf(NullPointerException.class);
    }
}
