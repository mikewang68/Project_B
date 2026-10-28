package com.bproject.safety.module.collision;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.collision.repository.InMemoryCollisionRepository;
import com.bproject.safety.module.collision.service.CollisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 设备状态上报接入测试：只更新已登记设备的白名单字段，未知编码忽略，
 * 数值越界钳制，缺机器凭据拒绝。
 *
 * <p>用与 {@code SecurityRoleHardeningIntegrationTest} 相同的属性打开安全开关，
 * 否则 test profile 默认 {@code app.security.enabled=false}，拦截器不生效、测不到 401。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.security.browser-sso-only=false",
        "app.security.api-key=offline-unit-only-ingestion-key-never-deploy",
        "app.security.jwt-secret=scs-it-hs256-secret-0123456789abcdefghijklmn",
        "app.security.jwt-issuer=iam"
})
@DisplayName("设备运行状态上报（X-API-Key 机器凭据）")
class DeviceStateIngestTest {

    private static final String KEY = "offline-unit-only-ingestion-key-never-deploy";
    private static final String PATH = "/api/v1/ingest/device-state";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CollisionRepository repository;
    @Autowired
    private CollisionService service;

    @BeforeEach
    void reset() {
        ((InMemoryCollisionRepository) repository).resetDemoData();
    }

    private String body(String devicesJson) {
        return "{\"source\":\"unit-test\",\"devices\":[" + devicesJson + "]}";
    }

    @Test
    @DisplayName("已登记设备：白名单字段生效，未提供的字段保持原值")
    void appliesWhitelistedFieldsForKnownDevice() throws Exception {
        String originalType = repository.findById("CRANE-01").orElseThrow().type;

        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"CRANE-01\",\"status\":\"作业中\",\"speed\":2.7,"
                                + "\"direction\":\"向东\",\"controlStatus\":\"PLC 联锁可用\","
                                + "\"communication\":\"在线 · 20ms\",\"radarStatus\":\"正常\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.ignored", hasSize(0)));

        var saved = repository.findById("CRANE-01").orElseThrow();
        assertEquals("作业中", saved.status);
        assertEquals(2.7, saved.speed, 0.001);
        assertEquals("向东", saved.direction);
        assertEquals("PLC 联锁可用", saved.controlStatus);
        assertEquals("在线 · 20ms", saved.communication);
        // 上报方不能改设备主数据：类型/名称保持原值
        assertEquals(originalType, saved.type);
        assertTrue(saved.lastUpdated != null && !saved.lastUpdated.isBlank());
    }

    @Test
    @DisplayName("未知设备编码被忽略并在响应里回传，不新建设备")
    void ignoresUnknownDeviceWithoutCreatingIt() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"GHOST-99\",\"status\":\"作业中\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.ignored[0]").value("GHOST-99"));

        assertTrue(repository.findById("GHOST-99").isEmpty(), "上报方不得成为第二条设备主数据来源");
    }

    @Test
    @DisplayName("越界速度被钳制到上限，负值归零")
    void clampsSpeedIntoRange() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"VEH-07\",\"speed\":9999}")))
                .andExpect(status().isOk());
        assertEquals(60.0, repository.findById("VEH-07").orElseThrow().speed, 0.001);

        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"VEH-07\",\"speed\":-5}")))
                .andExpect(status().isOk());
        assertEquals(0.0, repository.findById("VEH-07").orElseThrow().speed, 0.001);
    }

    @Test
    @DisplayName("数据中断/异常分别映射到断数与待确认，不只显示正常")
    void mapsHealthStatesToPairStatus() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"TIP-02\",\"radarStatus\":\"数据异常\"}")))
                .andExpect(status().isOk());
        assertEquals("数据异常", service.devices(null).stream()
                .filter(d -> "TIP-02".equals(d.id)).findFirst().orElseThrow().radarStatus);

        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"TIP-02\",\"radarStatus\":\"数据中断\"}")))
                .andExpect(status().isOk());
        assertEquals("数据中断", service.devices(null).stream()
                .filter(d -> "TIP-02".equals(d.id)).findFirst().orElseThrow().radarStatus);
    }

    @Test
    @DisplayName("缺机器凭据一律 401，浏览器会话也不能调")
    void rejectsWithoutMachineCredential() throws Exception {
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"CRANE-01\",\"status\":\"作业中\"}")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post(PATH).header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"CRANE-01\",\"status\":\"作业中\"}")))
                .andExpect(status().isUnauthorized());
    }
}
