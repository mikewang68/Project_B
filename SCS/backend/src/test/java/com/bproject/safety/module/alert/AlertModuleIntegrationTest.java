package com.bproject.safety.module.alert;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 告警模块 Web 层集成测试：test profile 下无需 openGauss/Kvrocks 等基础设施，
 * 启动后由 AlertDemoSeeder 灌入 12 条与前端一致的 Demo 数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AlertModuleIntegrationTest {

    private static final String BASE = "/api/v1/alerts";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AlertRepository repository;

    @Autowired
    private Clock clock;

    /** 每个用例前重置为 12 条初始种子，避免用例间共享内存状态互相污染。 */
    @BeforeEach
    void resetSeeds() {
        InMemoryAlertRepository mem = (InMemoryAlertRepository) repository;
        mem.clearDemoData();
        AlertDemoSeeder.buildSeeds(clock).forEach(repository::save);
    }

    @Test
    @DisplayName("启动后自动初始化 12 条告警，分页返回")
    void listSeededWithPaging() throws Exception {
        mvc.perform(get(BASE).param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(12)))
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.list", hasSize(12)));

        mvc.perform(get(BASE).param("page", "1").param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(12)))
                .andExpect(jsonPath("$.list", hasSize(5)));
    }

    @Test
    @DisplayName("多条件筛选：风险/状态/来源/关键词")
    void filtersWork() throws Exception {
        mvc.perform(get(BASE).param("risk", "紧急"))
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.list[0].id", is("ALM-20260904-001")));

        mvc.perform(get(BASE).param("status", "已关闭"))
                .andExpect(jsonPath("$.total", is(3)));

        mvc.perform(get(BASE).param("source", "AI违规"))
                .andExpect(jsonPath("$.total", is(2)));

        mvc.perform(get(BASE).param("keyword", "龙门吊"))
                .andExpect(jsonPath("$.total", is(3)));

        // level 是 risk 的别名
        mvc.perform(get(BASE).param("level", "紧急"))
                .andExpect(jsonPath("$.total", is(1)));
    }

    @Test
    @DisplayName("责任人筛选支持 userCode 与姓名（Phase A）")
    void filterByAssigneeUserCode() throws Exception {
        // 待派单事件派给 USR-004 陈静
        mvc.perform(post(BASE + "/ALM-20260904-004/assign")
                        .header("Idempotency-Key", "filter-assignee-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"陈静\",\"assigneeId\":\"USR-004\",\"assigneeName\":\"陈静\",\"priority\":\"普通\",\"limitMin\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeUserCode", is("USR-004")));

        // userCode 筛选：返回项全部属于该责任人
        mvc.perform(get(BASE).param("assignee", "USR-004"))
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.list[*].assigneeUserCode", everyItem(is("USR-004"))));

        // 姓名同样可筛（快照可能含角色前缀，按 contains 匹配）
        mvc.perform(get(BASE).param("assignee", "陈静"))
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("指标由当前数据实时计算")
    void metricsComputed() throws Exception {
        mvc.perform(get(BASE + "/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(12)))
                .andExpect(jsonPath("$.pending", is(3)))
                .andExpect(jsonPath("$.urgent", is(1)))
                .andExpect(jsonPath("$.severe", is(4)))
                .andExpect(jsonPath("$.closed", is(3)));
    }

    @Test
    @DisplayName("详情包含证据、联动步骤与时间线")
    void detailContainsEvidenceLinkageTimeline() throws Exception {
        mvc.perform(get(BASE + "/ALM-20260904-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.kind", is("personnel")))
                .andExpect(jsonPath("$.evidence.track", hasSize(5)))
                .andExpect(jsonPath("$.linkage", hasSize(7)))
                .andExpect(jsonPath("$.timeline", hasSize(9)))
                .andExpect(jsonPath("$.occurredAt", notNullValue()));
    }

    @Test
    @DisplayName("详情不存在返回 404 统一错误结构")
    void detailMissing404() throws Exception {
        mvc.perform(get(BASE + "/ALM-NOT-EXIST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("NOT_FOUND")))
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }

    @Test
    @DisplayName("完整处置闭环：confirm→assign→start→treatment→review，时间线持续追加")
    void fullDispositionFlow() throws Exception {
        // 002 初始为待确认
        mvc.perform(post(BASE + "/ALM-20260904-002/confirm")
                        .header("Idempotency-Key", "flow-confirm-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"李娜\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("待派单")))
                .andExpect(jsonPath("$.confirmUser", is("李娜")))
                .andExpect(jsonPath("$.timeline", hasSize(3)));

        // 已离开待确认，再次 confirm 属于非法流转 → 409
        mvc.perform(post(BASE + "/ALM-20260904-002/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("STATE_CONFLICT")))
                .andExpect(jsonPath("$.message", containsString("当前状态不允许执行该操作")));

        // 派单：待派单 → 待处理
        mvc.perform(post(BASE + "/ALM-20260904-002/assign")
                        .header("Idempotency-Key", "flow-assign-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"王建国\",\"assigneeId\":\"USR-002\",\"assigneeName\":\"王建国\",\"priority\":\"紧急\",\"limitMin\":10,\"note\":\"尽快处理\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("待处理")))
                .andExpect(jsonPath("$.assignee", is("王建国")))
                .andExpect(jsonPath("$.assigneeUserCode", is("USR-002")))
                .andExpect(jsonPath("$.slaDeadline", notNullValue()))
                .andExpect(jsonPath("$.slaRemainingSec", greaterThanOrEqualTo(590)))
                .andExpect(jsonPath("$.timeline", hasSize(4)));

        // 接单：待处理 → 处理中
        mvc.perform(post(BASE + "/ALM-20260904-002/start")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"handler\":\"王建国\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("处理中")))
                .andExpect(jsonPath("$.acceptTime", notNullValue()))
                .andExpect(jsonPath("$.timeline", hasSize(5)));

        // 提交处置：处理中 → 待复核
        mvc.perform(post(BASE + "/ALM-20260904-002/treatment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measures\":[\"人员已撤离危险区域\",\"现场确认无遗留风险\"],\"result\":\"风险已解除\",\"note\":\"已教育\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("待复核")))
                .andExpect(jsonPath("$.treatment.measures", hasSize(2)))
                .andExpect(jsonPath("$.treatment.handler", is("王建国")))
                .andExpect(jsonPath("$.timeline", hasSize(6)));

        // 严重事件必须经复核：待复核 → 已关闭，SLA 清空
        mvc.perform(post(BASE + "/ALM-20260904-002/review")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"刘志明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("已关闭")))
                .andExpect(jsonPath("$.reviewUser", is("刘志明")))
                .andExpect(jsonPath("$.reviewTime", notNullValue()))
                // NON_NULL 序列化下已关闭事件直接不输出 slaRemainingSec
                .andExpect(jsonPath("$.slaRemainingSec").doesNotExist())
                .andExpect(jsonPath("$.timeline", hasSize(7)));

        // 刷新详情后处理过程仍然存在（后端持久化时间线）
        mvc.perform(get(BASE + "/ALM-20260904-002"))
                .andExpect(jsonPath("$.status", is("已关闭")))
                .andExpect(jsonPath("$.timeline", hasSize(7)))
                .andExpect(jsonPath("$.confirmUser", is("李娜")));
    }

    @Test
    @DisplayName("处置措施为空 → 422")
    void treatmentRequiresMeasures() throws Exception {
        // 005 为待处理，可直接 treatment 前必须先 start
        mvc.perform(post(BASE + "/ALM-20260904-005/start")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + "/ALM-20260904-005/treatment")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"measures\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("UNPROCESSABLE_ENTITY")));
    }

    @Test
    @DisplayName("复核驳回：待复核 → 处理中")
    void reviewReject() throws Exception {
        mvc.perform(post(BASE + "/ALM-20260904-007/review-reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"证据不足\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("处理中")));
    }

    @Test
    @DisplayName("事件升级记录原等级")
    void escalate() throws Exception {
        mvc.perform(post(BASE + "/ALM-20260904-009/escalate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"严重\",\"reason\":\"人员拒不撤离\",\"targets\":[\"调度员\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risk", is("严重")))
                .andExpect(jsonPath("$.upgradedFrom", is("预警")))
                .andExpect(jsonPath("$.status", is("待确认")));
    }

    @Test
    @DisplayName("人工接管仅记录业务状态")
    void takeover() throws Exception {
        mvc.perform(post(BASE + "/ALM-20260904-003/takeover")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"李娜\",\"reason\":\"PLC超时\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.takeover", is(true)));
    }

    @Test
    @DisplayName("联动演示：成功链路七步全部成功；失败链路 PLC failed")
    void linkageModes() throws Exception {
        MvcResult ok = mvc.perform(post(BASE + "/ALM-20260904-001/linkage")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"success\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkageFinished", is(true)))
                .andExpect(jsonPath("$.linkage[*].state", everyItem(is("success"))))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(ok.getResponse().getContentAsString()).contains("安全联动执行完成");

        // 003 具备联动能力，演示失败路径
        mvc.perform(post(BASE + "/ALM-20260904-003/linkage")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"fail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkageFailed", is(true)))
                .andExpect(jsonPath("$.linkage[6].state", is("failed")));
    }

    @Test
    @DisplayName("列表项按时间倒序，且携带 SLA 剩余秒数")
    void listOrderedWithSla() throws Exception {
        mvc.perform(get(BASE))
                .andExpect(jsonPath("$.list[0].time", is("13:21:08")))
                .andExpect(jsonPath("$.list[0].slaRemainingSec", greaterThanOrEqualTo(0)));
    }
}
