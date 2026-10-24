package com.bproject.safety.module.ai.repository;

import com.bproject.safety.module.ai.model.AiModelInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Step 7: JdbcAiModelRepository 单元与空表补种测试")
class JdbcAiModelRepositoryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private NamedParameterJdbcTemplate jdbcTemplate;
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        clock = Clock.fixed(Instant.parse("2026-10-24T04:00:00Z"), ZONE);
    }

    private AiModelInfo sample() {
        AiModelInfo m = new AiModelInfo();
        m.id = "MODEL-TEST-99";
        m.name = "YOLOv8-Test";
        m.taskType = "人员防护穿戴检测(PPE)";
        m.version = "v9.9.9";
        m.engine = "TensorRT FP16";
        m.accuracyMap = 0.946;
        m.latencyMs = 38.5;
        m.fps = 26.0;
        m.confidenceThreshold = 0.82;
        m.iouThreshold = 0.45;
        m.isSelected = true;
        m.status = "ACTIVE";
        m.weightSize = "43.5 MB";
        m.supportedClasses = List.of("helmet", "no_helmet");
        m.description = "测试模型";
        m.updatedAt = OffsetDateTime.now(clock);
        return m;
    }

    @Test
    @DisplayName("空表补种: count=0 时自动插入 5 款预置模型")
    void testSeedWhenEmpty() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), any(Class.class))).thenReturn(0L);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);

        new JdbcAiModelRepository(jdbcTemplate, new ObjectMapper(), clock);

        // 5 款预置模型的 INSERT
        verify(jdbcTemplate, times(5)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("非空表不补种: count>0 时零插入")
    void testNoSeedWhenNotEmpty() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), any(Class.class))).thenReturn(7L);

        new JdbcAiModelRepository(jdbcTemplate, new ObjectMapper(), clock);

        verify(jdbcTemplate, times(0)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("save 首次: UPDATE 未命中走 INSERT，JSONB 类别清单与阈值参数正确")
    void testSaveInsertPath() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), any(Class.class))).thenReturn(1L);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);
        JdbcAiModelRepository repository = new JdbcAiModelRepository(jdbcTemplate, new ObjectMapper(), clock);

        AiModelInfo saved = repository.save(sample());

        assertThat(saved.id).isEqualTo("MODEL-TEST-99");
        org.mockito.ArgumentCaptor<MapSqlParameterSource> captor =
                org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        // 1 次补种 count 查询后未插入（count=1）+ save 的 UPDATE + INSERT
        verify(jdbcTemplate, times(2)).update(anyString(), captor.capture());
        MapSqlParameterSource insertParams = captor.getAllValues().get(1);

        assertThat(insertParams.getValue("modelCode")).isEqualTo("MODEL-TEST-99");
        assertThat(insertParams.getValue("confidenceThreshold")).isEqualTo(0.82);
        assertThat(insertParams.getValue("isSelected")).isEqualTo(true);
        assertThat(insertParams.getValue("statusCode")).isEqualTo("ACTIVE");
        PGobject classes = (PGobject) insertParams.getValue("supportedClasses");
        assertThat(classes.getType()).isEqualTo("jsonb");
        assertThat(classes.getValue()).contains("helmet").contains("no_helmet");
    }

    @Test
    @DisplayName("save 已存在: UPDATE 命中不再 INSERT")
    void testSaveUpdatePath() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), any(Class.class))).thenReturn(1L);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        JdbcAiModelRepository repository = new JdbcAiModelRepository(jdbcTemplate, new ObjectMapper(), clock);

        repository.save(sample());

        verify(jdbcTemplate, times(1)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    @DisplayName("save 副本语义: 返回值与入参不共享 supportedClasses 列表")
    void testSaveCopySemantics() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), any(Class.class))).thenReturn(1L);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        JdbcAiModelRepository repository = new JdbcAiModelRepository(jdbcTemplate, new ObjectMapper(), clock);

        AiModelInfo input = sample();
        AiModelInfo saved = repository.save(input);
        assertThat(saved).isNotSameAs(input);
        assertThat(saved.supportedClasses).isNotSameAs(input.supportedClasses);
        assertThat(saved.supportedClasses).containsExactly("helmet", "no_helmet");
    }

    @Test
    @DisplayName("deleteById: 软删除按行数判定，空 id 直接 false")
    void testSoftDelete() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), any(Class.class))).thenReturn(1L);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        JdbcAiModelRepository repository = new JdbcAiModelRepository(jdbcTemplate, new ObjectMapper(), clock);

        assertThat(repository.deleteById("MODEL-TEST-99")).isTrue();
        assertThat(repository.deleteById(null)).isFalse();

        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);
        assertThat(repository.deleteById("MODEL-TEST-99")).isFalse();
    }

    @Test
    @DisplayName("RowMapper: 全字段读回，JSONB 类别清单与时间戳时区转换正确")
    @SuppressWarnings("unchecked")
    void testRowMapper() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), any(Class.class))).thenReturn(1L);
        OffsetDateTime updated = OffsetDateTime.parse("2026-10-24T12:30:05+08:00");

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("model_code")).thenReturn("MODEL-YOLO-PPE-01");
        when(rs.getString("model_name")).thenReturn("YOLOv8-SafetyHelmet-v2.3");
        when(rs.getString("task_type")).thenReturn("人员防护穿戴检测(PPE)");
        when(rs.getString("version_no")).thenReturn("v2.3.1");
        when(rs.getString("engine_type")).thenReturn("TensorRT FP16");
        when(rs.getDouble("accuracy_map")).thenReturn(0.946);
        when(rs.getDouble("latency_ms")).thenReturn(38.5);
        when(rs.getDouble("fps")).thenReturn(26.0);
        when(rs.getDouble("confidence_threshold")).thenReturn(0.70);
        when(rs.getDouble("iou_threshold")).thenReturn(0.45);
        when(rs.getBoolean("is_selected")).thenReturn(true);
        when(rs.getString("status_code")).thenReturn("ACTIVE");
        when(rs.getString("weight_size")).thenReturn("43.5 MB");
        when(rs.getString("supported_classes")).thenReturn("[\"helmet\",\"no_helmet\",\"vest\",\"no_vest\"]");
        when(rs.getString("description")).thenReturn("劳保用品合规检测");
        when(rs.getBoolean("is_deleted")).thenReturn(false);
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(updated.toInstant()));

        org.mockito.ArgumentCaptor<RowMapper<AiModelInfo>> mapperCaptor =
                org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), anyMap(), mapperCaptor.capture()))
                .thenReturn(List.of());

        JdbcAiModelRepository repository = new JdbcAiModelRepository(jdbcTemplate, new ObjectMapper(), clock);
        repository.findAll();

        AiModelInfo m = mapperCaptor.getValue().mapRow(rs, 0);
        assertThat(m.id).isEqualTo("MODEL-YOLO-PPE-01");
        assertThat(m.taskType).isEqualTo("人员防护穿戴检测(PPE)");
        assertThat(m.accuracyMap).isEqualTo(0.946);
        assertThat(m.confidenceThreshold).isEqualTo(0.70);
        assertThat(m.isSelected).isTrue();
        assertThat(m.status).isEqualTo("ACTIVE");
        assertThat(m.supportedClasses).containsExactly("helmet", "no_helmet", "vest", "no_vest");
        assertThat(m.isDeleted).isFalse();
        assertThat(m.updatedAt).isEqualTo(updated);
    }

    @Test
    @DisplayName("预置种子: 5 款模型且默认恰有一个选中")
    void testPresetShape() {
        List<AiModelInfo> presets = AiModelSeedData.presets(OffsetDateTime.now(clock));
        assertThat(presets).hasSize(5);
        assertThat(presets.stream().filter(m -> m.isSelected)).hasSize(1);
        assertThat(presets.stream().map(m -> m.id)).contains(
                "MODEL-YOLO-PPE-01", "MODEL-YOLO-DANGER-02", "MODEL-DETR-FIRE-03",
                "MODEL-POSE-FALL-04", "MODEL-CRANE-SWAY-05");
    }
}
