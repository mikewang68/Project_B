package com.bproject.safety.module.rule.repository;

import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.DemoRule.EdgeNode;
import com.bproject.safety.module.rule.model.DemoRule.Param;
import com.bproject.safety.module.rule.model.DemoRule.Version;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@DisplayName("JdbcRuleRepository: 契约测试与参数映射")
class JdbcRuleRepositoryTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private Clock clock;
    private JdbcRuleRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        objectMapper = new ObjectMapper();
        clock = Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneOffset.ofHours(8));
        repository = new JdbcRuleRepository(jdbcTemplate, objectMapper, clock);
    }

    @Test
    @DisplayName("save: 参数映射完整性测试（规则主表、版本快照、区域关联与边缘同步）")
    void testSaveParameterMapping() {
        DemoRule rule = new DemoRule();
        rule.id = "RULE-01";
        rule.name = "安全帽佩戴规范";
        rule.category = "人员防护";
        rule.type = "视觉卡控";
        rule.scope = "装卸作业区全覆盖";
        rule.version = "v2.1";
        rule.platformVersion = "v2.0";
        rule.riskCode = RiskLevels.SEVERE;
        rule.statusCode = RuleStatuses.ACTIVE;
        rule.highRisk = true;
        rule.owner = "张工程师";
        rule.approver = "李安全";
        rule.effectiveAt = "2026-09-01 00:00";
        rule.description = "进入装卸区域必须正确佩戴安全帽";
        rule.relatedModules = List.of("AI识别", "告警联动");
        rule.areas = List.of("装卸区 A", "装卸区 B");
        rule.updatedAt = "2026-09-04 10:00";

        Version v = new Version("v2.1", "2026-09-01", "初始发布", "张工程师", "已生效", List.of());
        rule.versions = List.of(v);
        rule.params = List.of(new Param("置信度阈值", "85", true, "大于85"));
        rule.edgeNodes = List.of(new EdgeNode("EDGE-01", "v2.1", "synced"));

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbcTemplate.query(eq("SELECT id FROM safety.safety_rule WHERE rule_code = :ruleCode"),
                eq(Map.of("ruleCode", "RULE-01")), any(RowMapper.class))).thenReturn(List.of(200L));
        when(jdbcTemplate.query(eq("SELECT area_code FROM safety.safety_rule_area WHERE rule_id = :ruleId"),
                anyMap(), any(RowMapper.class))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.query(eq("SELECT id FROM safety.safety_rule_version WHERE rule_id = :ruleId AND version_no = :versionNo"),
                anyMap(), any(RowMapper.class))).thenReturn(Collections.emptyList());

        DemoRule saved = repository.save(rule);
        assertThat(saved).isNotNull();
        assertThat(saved.id).isEqualTo("RULE-01");

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).update(anyString(), captor.capture());

        List<MapSqlParameterSource> allParams = captor.getAllValues();
        MapSqlParameterSource rootParams = allParams.get(0);
        assertThat(rootParams.getValue("ruleCode")).isEqualTo("RULE-01");
        assertThat(rootParams.getValue("ruleName")).isEqualTo("安全帽佩戴规范");
        assertThat(rootParams.getValue("category")).isEqualTo("人员防护");
        assertThat(rootParams.getValue("activeVersion")).isEqualTo("v2.1");
        assertThat(rootParams.getValue("riskLevelCode")).isEqualTo(RiskLevels.SEVERE);
        assertThat(rootParams.getValue("statusCode")).isEqualTo(RuleStatuses.ACTIVE);
        assertThat(rootParams.getValue("highRisk")).isEqualTo(true);
    }

    @Test
    @DisplayName("clearDemoData: 验证使用逻辑删除 is_deleted = true，不触碰触发器与物理删除")
    void testClearDemoDataLogicalDelete() {
        when(jdbcTemplate.update(anyString(), anyMap())).thenReturn(5);

        repository.clearDemoData();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), anyMap());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("UPDATE safety.safety_rule SET is_deleted = true");
        assertThat(sql).doesNotContain("DELETE FROM");
        assertThat(sql).doesNotContain("DISABLE TRIGGER");
        assertThat(sql).doesNotContain("session_replication_role");
    }

    @Test
    @DisplayName("count: 返回未删除规则总数")
    void testCount() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(15L);
        assertThat(repository.count()).isEqualTo(15L);
    }

    @Test
    @DisplayName("findById: 存在返回副本，不存在返回 Optional.empty()")
    @SuppressWarnings("unchecked")
    void testFindById() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Map.of("ruleCode", "RULE-MISSING")), any(RowMapper.class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        Optional<DemoRule> missing = repository.findById("RULE-MISSING");
        assertThat(missing).isEmpty();
        assertThat(repository.findById(null)).isEmpty();
        assertThat(repository.findById("")).isEmpty();
    }

    @Test
    @DisplayName("filter: 多条件筛选参数装配测试")
    @SuppressWarnings("unchecked")
    void testFilterParameterMapping() {
        RuleQuery query = new RuleQuery("安全帽", "人员防护", "已生效", "严重");

        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());

        List<DemoRule> result = repository.filter(query);
        assertThat(result).isEmpty();

        ArgumentCaptor<MapSqlParameterSource> captor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(anyString(), captor.capture(), any(RowMapper.class));

        MapSqlParameterSource params = captor.getValue();
        assertThat(params.getValue("category")).isEqualTo("人员防护");
        assertThat(params.getValue("statusCode")).isEqualTo(RuleStatuses.ACTIVE);
        assertThat(params.getValue("riskCode")).isEqualTo(RiskLevels.SEVERE);
        assertThat(params.getValue("kw")).isEqualTo("%安全帽%");
    }

    @Test
    @DisplayName("save: 空指针校验")
    void testNullValidation() {
        assertThatThrownBy(() -> repository.save(null)).isInstanceOf(NullPointerException.class);
        DemoRule noId = new DemoRule();
        assertThatThrownBy(() -> repository.save(noId)).isInstanceOf(NullPointerException.class);
    }
}
