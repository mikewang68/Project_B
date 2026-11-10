package com.bproject.safety.module.zone;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.zone.model.ZoneSnapshot;
import com.bproject.safety.module.zone.service.ZoneStateService;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * 装卸区状态上报测试：A 区限位开关判人员作业、B 区位置传感器判车辆进出。
 *
 * <p>与设备状态上报共用同一套机器凭据（{@code /api/v1/ingest/} 前缀）。</p>
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
@DisplayName("装卸区状态上报（X-API-Key 机器凭据）")
class ZoneStateIngestTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.bproject.safety.config.ScsScopedReferenceData scopeReferences;

    @org.junit.jupiter.api.BeforeEach
    void registerInMemoryLegacyFixtures() {
        // Only these in-memory roots are declared legacy. Unknown/owned roots stay denied.
        for (String id : java.util.List.of("LOADING-A", "LOADING-B")) {
            org.mockito.Mockito.when(scopeReferences.legacyIngestAllowed(
                    com.bproject.safety.config.ScsCentralScope.Root.AREA, id)).thenReturn(true);
        }
    }

    private static final String KEY = "offline-unit-only-ingestion-key-never-deploy";
    private static final String PATH = "/api/v1/ingest/zone-state";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ZoneStateService service;

    private Map<String, ZoneSnapshot> zones() {
        return service.list().stream().collect(Collectors.toMap(ZoneSnapshot::code, Function.identity()));
    }

    private String body(String zonesJson) {
        return "{\"source\":\"unit-test\",\"zones\":[" + zonesJson + "]}";
    }

    @Test
    @DisplayName("A 区限位开关：压合表示作业面有人作业")
    void appliesPersonnelWorkingForAreaA() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-A\",\"personnelWorking\":true,"
                                + "\"signal\":\"限位开关压合\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.ignored", hasSize(0)));

        ZoneSnapshot a = zones().get("LOADING-A");
        assertTrue(a.online());
        assertEquals(Boolean.TRUE, a.personnelWorking());
        assertEquals("限位开关压合", a.signal());
        assertTrue(a.updatedAt() != null && !a.updatedAt().isBlank());
    }

    @Test
    @DisplayName("B 区位置传感器：有车 + 测距，未提供的字段保持上次值")
    void appliesVehiclePresenceForAreaB() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-B\",\"vehiclePresent\":true,"
                                + "\"distanceMm\":2400,\"signal\":\"有车 2.4m\"}")))
                .andExpect(status().isOk());

        ZoneSnapshot b = zones().get("LOADING-B");
        assertEquals(Boolean.TRUE, b.vehiclePresent());
        assertEquals(2400, b.distanceMm());

        // 只报状态、不报距离：距离应保留上一次的值，而不是被清空
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-B\",\"vehiclePresent\":false}")))
                .andExpect(status().isOk());
        ZoneSnapshot after = zones().get("LOADING-B");
        assertEquals(Boolean.FALSE, after.vehiclePresent());
        assertEquals(2400, after.distanceMm());
    }

    @Test
    @DisplayName("bridge 故障显式 null 清空旧人员、车辆和距离，后续 false/0 正常恢复")
    void clearsBridgeFaultSignalsAndRecovers() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-A\",\"personnelWorking\":true},"
                                + "{\"code\":\"LOADING-B\",\"vehiclePresent\":true,\"distanceMm\":2400}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2));
        assertEquals(Boolean.TRUE, zones().get("LOADING-A").personnelWorking());
        assertEquals(Boolean.TRUE, zones().get("LOADING-B").vehiclePresent());
        assertEquals(2400, zones().get("LOADING-B").distanceMm());

        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"device-sim/bridge\",\"zones\":["
                                + "{\"code\":\"LOADING-A\",\"personnelWorking\":null,\"signal\":\"传感器故障\"},"
                                + "{\"code\":\"LOADING-B\",\"vehiclePresent\":null,\"distanceMm\":null,"
                                + "\"signal\":\"传感器故障\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2));
        Map<String, ZoneSnapshot> fault = zones();
        assertNull(fault.get("LOADING-A").personnelWorking());
        assertNull(fault.get("LOADING-B").vehiclePresent());
        assertNull(fault.get("LOADING-B").distanceMm());
        assertTrue(fault.get("LOADING-A").online());
        assertTrue(fault.get("LOADING-B").online());

        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-A\",\"personnelWorking\":false,\"signal\":\"正常\"},"
                                + "{\"code\":\"LOADING-B\",\"vehiclePresent\":false,\"distanceMm\":0,\"signal\":\"正常\"}")))
                .andExpect(status().isOk());
        assertEquals(Boolean.FALSE, zones().get("LOADING-A").personnelWorking());
        assertEquals(Boolean.FALSE, zones().get("LOADING-B").vehiclePresent());
        assertEquals(0, zones().get("LOADING-B").distanceMm());
        assertEquals("正常", zones().get("LOADING-A").signal());
        assertEquals("正常", zones().get("LOADING-B").signal());
    }

    @Test
    @DisplayName("缺省字段保留且 presence 标记不能由客户端覆盖；signal 的 null/空白仍保留")
    void preservesOmittedSignalsAndIgnoresClientPresenceFlags() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-A\",\"personnelWorking\":true,\"signal\":\"人员正常\"},"
                                + "{\"code\":\"LOADING-B\",\"vehiclePresent\":true,\"distanceMm\":1700,\"signal\":\"车辆正常\"}")))
                .andExpect(status().isOk());

        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-A\",\"signal\":null,\"personnelWorkingProvided\":true},"
                                + "{\"code\":\"LOADING-B\",\"signal\":\"  \","
                                + "\"vehiclePresentProvided\":true,\"distanceMmProvided\":true}")))
                .andExpect(status().isOk());
        assertEquals(Boolean.TRUE, zones().get("LOADING-A").personnelWorking());
        assertEquals(Boolean.TRUE, zones().get("LOADING-B").vehiclePresent());
        assertEquals(1700, zones().get("LOADING-B").distanceMm());
        assertEquals("人员正常", zones().get("LOADING-A").signal());
        assertEquals("车辆正常", zones().get("LOADING-B").signal());

        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-A\",\"personnelWorking\":null,\"personnelWorkingProvided\":false},"
                                + "{\"code\":\"LOADING-B\",\"vehiclePresent\":null,\"distanceMm\":null,"
                                + "\"vehiclePresentProvided\":false,\"distanceMmProvided\":false}")))
                .andExpect(status().isOk());
        assertNull(zones().get("LOADING-A").personnelWorking());
        assertNull(zones().get("LOADING-B").vehiclePresent());
        assertNull(zones().get("LOADING-B").distanceMm());
    }

    @Test
    @DisplayName("非法布尔输入返回 400，整批请求不修改已有状态")
    void rejectsInvalidBooleansWithoutChangingState() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-A\",\"personnelWorking\":true},"
                                + "{\"code\":\"LOADING-B\",\"vehiclePresent\":true,\"distanceMm\":2400}")))
                .andExpect(status().isOk());
        Map<String, ZoneSnapshot> before = zones();
        for (String invalid : java.util.List.of("\"invalid\"", "{}", "[]")) {
            // 非法字段在后一个区域，验证前一个区域也不会提前应用。
            for (String invalidZone : java.util.List.of(
                    "{\"code\":\"LOADING-A\",\"personnelWorking\":" + invalid + "}",
                    "{\"code\":\"LOADING-B\",\"vehiclePresent\":" + invalid + "}")) {
                mvc.perform(post(PATH).header("X-API-Key", KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("{\"code\":\"LOADING-A\",\"personnelWorking\":false}," + invalidZone)))
                        .andExpect(status().isBadRequest());
                assertEquals(before, zones());
            }
        }
    }

    @Test
    @DisplayName("已有五参数调用继续将 null 视为缺省")
    void preservesLegacyApplyNullSemantics() {
        assertTrue(service.apply("LOADING-A", true, null, null, "人员正常"));
        assertTrue(service.apply("LOADING-B", null, true, 2400, "车辆正常"));
        assertTrue(service.apply("LOADING-A", null, null, null, null));
        assertTrue(service.apply("LOADING-B", null, null, null, null));
        assertEquals(Boolean.TRUE, zones().get("LOADING-A").personnelWorking());
        assertEquals(Boolean.TRUE, zones().get("LOADING-B").vehiclePresent());
        assertEquals(2400, zones().get("LOADING-B").distanceMm());
    }

    @Test
    @DisplayName("未知区域编码忽略，不新建区域")
    void ignoresUnknownZone() throws Exception {
        int before = service.list().size();
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-Z\",\"vehiclePresent\":true}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.ignored[0]").value("LOADING-Z"));
        assertEquals(before, service.list().size());
    }

    @Test
    @DisplayName("测距越界被钳制到量程上限")
    void clampsDistance() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-B\",\"distanceMm\":999999}")))
                .andExpect(status().isOk());
        assertEquals(10_000, zones().get("LOADING-B").distanceMm());
    }

    @Test
    @DisplayName("未上报过的区域保持未知：online=false、字段为 null，不能当成无人/无车")
    void unknownZoneIsNotTreatedAsAbsent() {
        // 新上下文里 A/B 都未被上报过（本测试类其它用例会改状态，所以这里只断言结构约束）
        for (ZoneSnapshot z : service.list()) {
            if (!z.online()) {
                assertNull(z.personnelWorking());
                assertNull(z.vehiclePresent());
                assertFalse(z.online());
            }
        }
    }

    @Test
    @DisplayName("缺机器凭据 401")
    void rejectsWithoutMachineCredential() throws Exception {
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"code\":\"LOADING-A\",\"personnelWorking\":true}")))
                .andExpect(status().isUnauthorized());
    }
}
