package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgeNodeStatuses;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

@DisplayName("JdbcEdgeNodeRepository: 参数映射、契约与隔离性测试")
class JdbcEdgeNodeRepositoryTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private DemoMasterData masterData;
    private JdbcEdgeNodeRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        masterData = new DemoMasterData();
        repository = new JdbcEdgeNodeRepository(jdbcTemplate, masterData);
    }

    @Test
    @DisplayName("save: 参数映射完整性测试（areaName 转 areaCode，TIMESTAMPTZ 转换与各指标映射）")
    void testSaveParameterMapping() {
        DemoEdgeNode node = new DemoEdgeNode();
        node.id = "EDGE-01";
        node.name = "1 号边缘节点 · 装卸区 A";
        node.area = "装卸区 A"; // 应解析为 LOADING_AREA_A
        node.ip = "10.24.1.11";
        node.status = EdgeNodeStatuses.ONLINE;
        node.cloudConnected = true;
        node.autonomyActive = false;
        node.agentVersion = "edge-agent 1.4.2";
        node.activeRuleVersion = "RULE-V1.2";
        node.expectedRuleVersion = "RULE-V1.2";
        node.activeFenceVersion = "FENCE-V1.0";
        node.expectedFenceVersion = "FENCE-V1.0";
        node.clockOffsetMs = 35L;
        node.latencyMs = 24;
        node.cpuUsage = 32;
        node.memoryUsage = 46;
        node.diskUsage = 41;
        node.temperature = 42;
        node.queueDepth = 0;
        node.cachedEventCount = 1284;
        node.uptimeSec = 86400L;
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        node.lastHeartbeat = now;
        node.lastSyncAt = now;
        node.lastError = null;

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        DemoEdgeNode saved = repository.save(node);

        assertThat(saved).isNotNull();
        assertThat(saved.id).isEqualTo("EDGE-01");

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(anyString(), captor.capture());

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("nodeCode")).isEqualTo("EDGE-01");
        assertThat(params.getValue("nodeName")).isEqualTo("1 号边缘节点 · 装卸区 A");
        assertThat(params.getValue("areaCode")).isEqualTo(DemoMasterData.AREA_LOADING_A);
        assertThat(params.getValue("ipAddress")).isEqualTo("10.24.1.11");
        assertThat(params.getValue("statusCode")).isEqualTo(EdgeNodeStatuses.ONLINE);
        assertThat(params.getValue("cloudConnected")).isEqualTo(true);
        assertThat(params.getValue("autonomyActive")).isEqualTo(false);
        assertThat(params.getValue("agentVersion")).isEqualTo("edge-agent 1.4.2");
        assertThat(params.getValue("activeRuleVersion")).isEqualTo("RULE-V1.2");
        assertThat(params.getValue("expectedRuleVersion")).isEqualTo("RULE-V1.2");
        assertThat(params.getValue("clockOffsetMs")).isEqualTo(35L);
        assertThat(params.getValue("latencyMs")).isEqualTo(24);
        assertThat(params.getValue("cpuUsage")).isEqualTo(32);
        assertThat(params.getValue("memoryUsage")).isEqualTo(46);
        assertThat(params.getValue("diskUsage")).isEqualTo(41);
        assertThat(params.getValue("temperature")).isEqualTo(42);
        assertThat(params.getValue("queueDepth")).isEqualTo(0);
        assertThat(params.getValue("cachedEventCount")).isEqualTo(1284);
        assertThat(params.getValue("uptimeSec")).isEqualTo(86400L);
        assertThat(params.getValue("lastHeartbeatAt")).isNotNull();
        assertThat(params.getValue("lastSyncAt")).isNotNull();
    }

    @Test
    @DisplayName("save: 当 update 返回 0 时触发 insert 插入新边缘节点")
    void testSaveTriggersInsertWhenUpdateMisses() {
        DemoEdgeNode node = new DemoEdgeNode();
        node.id = "EDGE-NEW";
        node.name = "新边缘节点";

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(0)
                .thenReturn(1);

        DemoEdgeNode saved = repository.save(node);
        assertThat(saved.id).isEqualTo("EDGE-NEW");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));

        List<String> sqls = sqlCaptor.getAllValues();
        assertThat(sqls.get(0)).contains("UPDATE safety.edge_node");
        assertThat(sqls.get(1)).contains("INSERT INTO safety.edge_node");
    }

    @Test
    @DisplayName("count: 返回总节点数")
    void testCount() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(4L);
        assertThat(repository.count()).isEqualTo(4L);
    }

    @Test
    @DisplayName("findById: 存在返回副本，不存在返回 Optional.empty()")
    @SuppressWarnings("unchecked")
    void testFindById() {
        DemoEdgeNode mockNode = new DemoEdgeNode();
        mockNode.id = "EDGE-01";
        mockNode.name = "1 号边缘节点";

        when(jdbcTemplate.queryForObject(anyString(), eq(Map.of("nodeCode", "EDGE-01")), any(RowMapper.class)))
                .thenReturn(mockNode);

        Optional<DemoEdgeNode> opt = repository.findById("EDGE-01");
        assertThat(opt).isPresent();
        assertThat(opt.get().id).isEqualTo("EDGE-01");

        when(jdbcTemplate.queryForObject(anyString(), eq(Map.of("nodeCode", "NON-EXIST")), any(RowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<DemoEdgeNode> missing = repository.findById("NON-EXIST");
        assertThat(missing).isEmpty();

        assertThat(repository.findById(null)).isEmpty();
        assertThat(repository.findById("")).isEmpty();
    }

    @Test
    @DisplayName("copy 语义验证：修改返回对象不影响持久化状态")
    void testCopySemantics() {
        DemoEdgeNode n = new DemoEdgeNode();
        n.id = "EDGE-01";
        n.name = "Original";

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        DemoEdgeNode returned = repository.save(n);
        returned.name = "Modified";

        assertThat(n.name).isEqualTo("Original");
    }

    @Test
    @DisplayName("save: 空参数校验")
    void testNullValidation() {
        assertThatThrownBy(() -> repository.save(null))
                .isInstanceOf(NullPointerException.class);

        DemoEdgeNode noId = new DemoEdgeNode();
        assertThatThrownBy(() -> repository.save(noId))
                .isInstanceOf(NullPointerException.class);
    }
}
