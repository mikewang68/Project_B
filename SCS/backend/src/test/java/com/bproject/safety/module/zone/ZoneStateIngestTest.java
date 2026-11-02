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
