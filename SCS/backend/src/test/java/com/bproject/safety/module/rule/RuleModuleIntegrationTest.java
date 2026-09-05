package com.bproject.safety.module.rule;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.rule.repository.InMemoryRuleRepository;
import com.bproject.safety.module.rule.seed.RuleDemoSeeder;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 规则配置后端集成测试：列表 / 生命周期 / 发布同步 / 回滚 / 冲突 / 仿真 / 版本异常 / 非法流转。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleModuleIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private InMemoryRuleRepository ruleRepository;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private Clock clock;

    @BeforeEach
    void reset() {
        ((InMemoryAlertRepository) alertRepository).clear();
        AlertDemoSeeder.buildSeeds(clock).forEach(alertRepository::save);
        ruleRepository.clear();
        RuleDemoSeeder.buildSeeds().forEach(ruleRepository::save);
    }

    @Test
    @DisplayName("规则列表：22 条种子，已生效规则可被 KPI 统计")
    void listAndMetrics() throws Exception {
        mvc.perform(get("/api/v1/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list", hasSize(23)));
        mvc.perform(get("/api/v1/rules/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", greaterThanOrEqualTo(8)))
                .andExpect(jsonPath("$.mismatch", is(1)));
    }

    @Test
    @DisplayName("分类筛选：人员安全 4 条")
    void filterByCategory() throws Exception {
        mvc.perform(get("/api/v1/rules").param("category", "人员安全"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list", hasSize(4)));
    }

    @Test
    @DisplayName("新建草稿 → 提交评审 → 批准 → 发布，4/4 同步后已生效")
    void fullLifecycle() throws Exception {
        String body = "{\"name\":\"测试距离规则\",\"category\":\"设备安全\",\"areas\":[\"车辆通道\"],"
                + "\"risk\":\"预警\",\"owner\":\"设备管理员 周海\",\"params\":[{\"label\":\"预警距离\",\"value\":\"9m\"}],"
                + "\"actions\":[\"通知司机\"],\"highRisk\":false}";
        String resp = mvc.perform(post("/api/v1/rules").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("草稿")))
                .andExpect(jsonPath("$.version", is("v1.0")))
                .andReturn().getResponse().getContentAsString();
        String id = resp.replaceAll(".*\"id\":\"(RULE-[A-Z]+-\\d+)\".*", "$1");

        mvc.perform(post("/api/v1/rules/" + id + "/submit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("待评审")));
        mvc.perform(post("/api/v1/rules/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("已批准")));
        mvc.perform(post("/api/v1/rules/" + id + "/publish").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("已生效")))
                .andExpect(jsonPath("$.edgeNodes", hasSize(4)))
                .andExpect(jsonPath("$.edgeNodes[0].state", is("synced")));
    }

    @Test
    @DisplayName("草稿规则直接发布返回 409 STATE_CONFLICT")
    void illegalPublish() throws Exception {
        mvc.perform(post("/api/v1/rules/RULE-PER-004/publish")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("STATE_CONFLICT")));
    }

    @Test
    @DisplayName("高危规则批准缺少 confirmHighRisk 返回 422")
    void highRiskRequiresConfirm() throws Exception {
        // RULE-DEV-003 已生效，先以新建版本方式改回草稿再提交评审；这里直接对已提交待评审的高危规则校验
        // RULE-PER-003 为待评审但非高危；改用新建高危草稿链路
        String body = "{\"name\":\"高危停机规则\",\"category\":\"联动策略\",\"areas\":[\"全部区域\"],"
                + "\"risk\":\"紧急\",\"owner\":\"安全员 王建国\",\"highRisk\":true,\"submitReview\":true,"
                + "\"params\":[{\"label\":\"设备停机\",\"value\":\"自动执行\",\"danger\":true}],\"actions\":[\"PLC 联动\"]}";
        String resp = mvc.perform(post("/api/v1/rules").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = resp.replaceAll(".*\"id\":\"(RULE-[A-Z]+-\\d+)\".*", "$1");
        mvc.perform(post("/api/v1/rules/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/rules/" + id + "/approve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmHighRisk\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("已批准")));
    }

    @Test
    @DisplayName("回滚不覆盖当前版本：v3.3 选 v3.2 生成 v3.4 并回到待评审")
    void rollbackCreatesNewVersion() throws Exception {
        mvc.perform(post("/api/v1/rules/RULE-PER-001/rollback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\":\"v3.2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newVersion", is("v3.4")))
                .andExpect(jsonPath("$.status", is("待评审")))
                .andExpect(jsonPath("$.rule.version", is("v3.4")))
                .andExpect(jsonPath("$.rule.sourceVersion", is("v3.2")));
    }

    @Test
    @DisplayName("已生效规则以新建版本方式编辑：v2.4 → v2.5 草稿，历史版本保留")
    void editAsNewVersion() throws Exception {
        String body = "{\"name\":\"转运车辆距离预警\",\"asNewVersion\":true,\"risk\":\"严重\","
                + "\"params\":[{\"label\":\"预警距离\",\"value\":\"11m\"}],\"actions\":[\"通知司机\"]}";
        mvc.perform(put("/api/v1/rules/RULE-DEV-003").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is("v2.5")))
                .andExpect(jsonPath("$.status", is("草稿")))
                .andExpect(jsonPath("$.versions[0].version", is("v2.5")));
    }

    @Test
    @DisplayName("冲突检查：DEV-003 与 DEV-009 预警距离冲突且为高危 blocking")
    void conflictCheck() throws Exception {
        mvc.perform(post("/api/v1/rules/conflict-check").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts[0].ruleA", is("RULE-DEV-003")))
                .andExpect(jsonPath("$.conflicts[0].ruleB", is("RULE-DEV-009")))
                .andExpect(jsonPath("$.conflicts[0].blocking", is(true)));
    }

    @Test
    @DisplayName("仿真：距离 2.8m + 晴 → 紧急风险并建议设备停机")
    void simulate() throws Exception {
        String body = "{\"ruleId\":\"RULE-DEV-003\",\"distance\":2.8,\"relSpeed\":1.8,\"direction\":\"接近\","
                + "\"radarQuality\":97,\"weather\":\"晴\"}";
        mvc.perform(post("/api/v1/rules/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level", is("紧急风险")))
                .andExpect(jsonPath("$.actions[0]", is("设备停机")));
    }

    @Test
    @DisplayName("版本异常：RULE-PER-001 的 EDGE-03 mismatch；指定节点重下发后恢复已生效")
    void mismatchAndRedeliver() throws Exception {
        mvc.perform(post("/api/v1/rules/RULE-PER-001/simulate-mismatch")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("版本异常")));
        mvc.perform(post("/api/v1/rules/RULE-PER-001/redeliver").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"EDGE-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("已生效")));
    }

    @Test
    @DisplayName("版本历史：RULE-PER-001 有 3 个版本，当前为 v3.3")
    void versions() throws Exception {
        mvc.perform(get("/api/v1/rules/RULE-PER-001/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions", hasSize(3)))
                .andExpect(jsonPath("$.versions[0].version", is("v3.3")))
                .andExpect(jsonPath("$.versions[0].state", is("当前")));
    }

    @Test
    @DisplayName("不存在的规则详情返回 404")
    void notFound() throws Exception {
        mvc.perform(get("/api/v1/rules/RULE-NOPE-999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }
}
