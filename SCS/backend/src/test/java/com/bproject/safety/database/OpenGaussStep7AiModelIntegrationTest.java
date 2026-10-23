package com.bproject.safety.database;

import com.bproject.safety.module.ai.model.AiModelInfo;
import com.bproject.safety.module.ai.repository.JdbcAiModelRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCS Phase 1 / Step 7: openGauss 真实实机集成测试（在 bpoc-node4 上执行）。
 * 验证 AI 工业模型配置（ai_model 表）落库后的选用热切换、参数调优持久化、
 * JSONB 类别清单往返与软删除语义。
 *
 * <p>安全原则：仅在 RUN_OPENGAUSS_STEP7=true 时激活；测试数据使用每轮唯一的
 * {@code scs_step7_it_<millis>} 前缀；表挂防删触发器，清理采用软删除 UPDATE
 * （is_deleted=true），不产生物理删除。</p>
 */
@EnabledIfEnvironmentVariable(named = OpenGaussStep7AiModelIntegrationTest.ENV_STEP7_ENABLED, matches = "true")
@DisplayName("Phase 1 / Step 7: openGauss 6.0.5 AI 模型配置持久化集成验收测试")
public class OpenGaussStep7AiModelIntegrationTest extends OpenGaussSpikeSupport {

    public static final String ENV_STEP7_ENABLED = "RUN_OPENGAUSS_STEP7";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 每轮唯一前缀；前缀以小写 "aa" 起头使编码排序位于预置模型（MODEL-*）之前——
     * 共库环境下 findSelected 的 ORDER BY model_code ASC 首行可确定性落在测试行上，
     * 无需触碰生产预置配置行。
     */
    private final String runPrefix = "aa_scs_step7_it_" + System.currentTimeMillis();

    private NamedParameterJdbcTemplate jdbcTemplate;
    private JdbcAiModelRepository repository;

    @BeforeEach
    void setUp() {
        if (dataSource == null) {
            return;
        }
        jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        repository = new JdbcAiModelRepository(jdbcTemplate, new ObjectMapper(), Clock.system(ZONE));
        cleanupTestData();
    }

    /** 软删除清理：防删触发器下用 is_deleted=true 收敛历史轮次全部测试行（含旧前缀遗留）。 */
    private void cleanupTestData() {
        if (dataSource == null) {
            return;
        }
        jdbcTemplate.update(
                "UPDATE safety.ai_model SET is_deleted = true, updated_at = now() WHERE model_code LIKE :p",
                java.util.Map.of("p", "%scs_step7_it_%"));
    }

    private AiModelInfo newModel(String seq, boolean selected) {
        AiModelInfo m = new AiModelInfo();
        m.id = runPrefix + "_model_" + seq;
        m.name = "TestModel-" + seq;
        m.taskType = "人员防护穿戴检测(PPE)";
        m.version = "v1.0." + seq;
        m.engine = "ONNX Runtime";
        m.accuracyMap = 0.931;
        m.latencyMs = 40.5;
        m.fps = 24.0;
        m.confidenceThreshold = 0.70;
        m.iouThreshold = 0.45;
        m.isSelected = selected;
        m.status = selected ? "ACTIVE" : "READY";
        m.weightSize = "40.0 MB";
        m.supportedClasses = List.of("helmet", "no_helmet", "vest");
        m.description = "Step7 集成测试模型";
        // openGauss TIMESTAMPTZ 微秒精度按四舍五入存储；预截断消除亚微秒抖动
        m.updatedAt = OffsetDateTime.now(ZONE).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return m;
    }

    @Test
    @DisplayName("全字段往返: 含 JSONB 类别清单的完整持久化与读回")
    void testFullRoundtrip() {
        AiModelInfo m = newModel("01", false);
        repository.save(m);

        Optional<AiModelInfo> loaded = repository.findById(m.id);
        assertThat(loaded).isPresent();
        AiModelInfo model = loaded.get();
        assertThat(model.name).isEqualTo("TestModel-01");
        assertThat(model.taskType).isEqualTo("人员防护穿戴检测(PPE)");
        assertThat(model.engine).isEqualTo("ONNX Runtime");
        assertThat(model.accuracyMap).isEqualTo(0.931);
        assertThat(model.latencyMs).isEqualTo(40.5);
        assertThat(model.fps).isEqualTo(24.0);
        assertThat(model.confidenceThreshold).isEqualTo(0.70);
        assertThat(model.iouThreshold).isEqualTo(0.45);
        assertThat(model.isSelected).isFalse();
        assertThat(model.status).isEqualTo("READY");
        assertThat(model.weightSize).isEqualTo("40.0 MB");
        assertThat(model.supportedClasses).containsExactly("helmet", "no_helmet", "vest");
        assertThat(model.description).isEqualTo("Step7 集成测试模型");
        assertThat(model.updatedAt).isEqualTo(m.updatedAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("热切换与参数调优: 选用切换 + 阈值调整重启后（新实例读回）仍生效")
    void testHotSwitchAndTuningSurvivesRestart() {
        AiModelInfo a = newModel("10", true);
        AiModelInfo b = newModel("11", false);
        repository.save(a);
        repository.save(b);

        // 热切换：b 选用、a 让位（应用层 selectModel 的仓储侧等价操作序列）
        AiModelInfo reloadedA = repository.findById(a.id).orElseThrow();
        reloadedA.isSelected = false;
        reloadedA.status = "READY";
        reloadedA.confidenceThreshold = 0.88; // 参数在线调优
        reloadedA.iouThreshold = 0.50;
        repository.save(reloadedA);

        AiModelInfo reloadedB = repository.findById(b.id).orElseThrow();
        reloadedB.isSelected = true;
        reloadedB.status = "ACTIVE";
        repository.save(reloadedB);

        // 模拟重启：全新仓储实例（跳过补种逻辑影响——表非空）
        JdbcAiModelRepository revived = new JdbcAiModelRepository(
                jdbcTemplate, new ObjectMapper(), Clock.system(ZONE));

        AiModelInfo selected = revived.findSelected().orElseThrow();
        assertThat(selected.id).isEqualTo(b.id);

        AiModelInfo tuned = revived.findById(a.id).orElseThrow();
        assertThat(tuned.isSelected).isFalse();
        assertThat(tuned.confidenceThreshold).isEqualTo(0.88);
        assertThat(tuned.iouThreshold).isEqualTo(0.50);

        // findAll 排序契约：选中的在前
        List<AiModelInfo> visible = revived.findAll();
        long myCount = visible.stream().filter(x -> x.id.startsWith(runPrefix)).count();
        assertThat(myCount).isEqualTo(2);
        String firstMyId = visible.stream().filter(x -> x.id.startsWith(runPrefix)).findFirst().orElseThrow().id;
        assertThat(firstMyId).isEqualTo(b.id);
    }

    @Test
    @DisplayName("软删除: deleteById 后 findAll/findById 不可见，重复删除返回 false")
    void testSoftDeleteSemantics() {
        AiModelInfo m = newModel("20", false);
        repository.save(m);

        assertThat(repository.deleteById(m.id)).isTrue();
        assertThat(repository.findById(m.id)).isEmpty();
        assertThat(repository.findAll().stream().noneMatch(x -> x.id.equals(m.id))).isTrue();
        assertThat(repository.deleteById(m.id)).isFalse();
    }

    @Test
    @DisplayName("预置补种不误伤: 既有配置行存在时构造不产生额外插入")
    void testNoReseedWhenTableNotEmpty() {
        // 本轮先写一行（表已非空），再构造新实例——findAll 中本轮行数不因补种增长
        repository.save(newModel("30", false));
        int before = repository.findAll().size();

        new JdbcAiModelRepository(jdbcTemplate, new ObjectMapper(), Clock.system(ZONE));

        int after = repository.findAll().size();
        assertThat(after).isEqualTo(before);
    }
}
