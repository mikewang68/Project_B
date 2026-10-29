package com.bproject.safety.module.personnel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.personnel.service.PersonnelPositionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.StreamSupport;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * 人员位置上报测试：UWB 定位引擎经网关把标签坐标推到 {@code /api/v1/ingest/person-position}，
 * 上报值覆盖首页地图与人员模块的台账坐标。
 *
 * <p>与设备/装卸区上报共用同一套机器凭据（{@code /api/v1/ingest/} 前缀）。坐标口径是首页底图的
 * 百分比（0~100），换算由网关按站场尺寸完成，后端不做米制换算。</p>
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
@DisplayName("人员位置上报（UWB → X-API-Key 机器凭据）")
class PersonPositionIngestTest {

    private static final String KEY = "offline-unit-only-ingestion-key-never-deploy";
    private static final String PATH = "/api/v1/ingest/person-position";
    private static final String JWT_SECRET = "scs-it-hs256-secret-0123456789abcdefghijklmn";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PersonnelPositionService service;
    @Autowired
    private ObjectMapper objectMapper;

    private String body(String positionsJson) {
        return "{\"source\":\"unit-test\",\"positions\":[" + positionsJson + "]}";
    }

    private void push(String positionsJson) throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(positionsJson)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("上报坐标落入装卸区 A：位置生效并按底图矩形推导区域")
    void appliesPositionAndDerivesArea() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"id\":\"P-ZHAO\",\"x\":20,\"y\":22,\"battery\":66,"
                                + "\"accuracy\":0.2}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.ignored", hasSize(0)))
                .andExpect(jsonPath("$.source").value("unit-test"));

        PersonnelPositionService.LivePosition live = service.get("P-ZHAO");
        assertThat(live).isNotNull();
        assertThat(live.x()).isEqualTo(20.0);
        assertThat(live.y()).isEqualTo(22.0);
        assertThat(live.area()).isEqualTo("装卸区 A");
        assertThat(live.battery()).isEqualTo(66);
        // 精度 0.2m 落在优秀档；阈值是按 UWB 引擎标称精度构造的，现场可调
        assertThat(live.quality()).isEqualTo("优秀");
        assertThat(live.updatedAt()).isNotBlank();
    }

    @Test
    @DisplayName("未登记人员编码忽略：上报方不能成为第二条人员主数据来源")
    void ignoresUnknownPerson() throws Exception {
        mvc.perform(post(PATH).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"id\":\"P-999\",\"x\":50,\"y\":50}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0))
                .andExpect(jsonPath("$.ignored[0]").value("P-999"));
        assertThat(service.get("P-999")).isNull();
    }

    @Test
    @DisplayName("坐标越界钳制到 0~100：底图外的位置不能把点位画到画布外")
    void clampsCoordinates() throws Exception {
        push("{\"id\":\"P-002\",\"x\":-30,\"y\":140}");

        PersonnelPositionService.LivePosition live = service.get("P-002");
        assertThat(live.x()).isEqualTo(0.0);
        assertThat(live.y()).isEqualTo(100.0);
        // 钳制后落在作业区之外，归入底图剩余可通行区域
        assertThat(live.area()).isEqualTo("车辆通道");
    }

    @Test
    @DisplayName("上报后首页地图与人员实时位置接口都返回新坐标（不是只写进内存没人看）")
    void ingestDrivesMapAndLiveEndpoints() throws Exception {
        push("{\"id\":\"P-004\",\"x\":60,\"y\":20,\"battery\":88}");

        String token = jwt("admin_01", new String[]{"ADMIN"}, "safety", now() + 3600);

        String mapJson = mvc.perform(get("/api/v1/overview/map").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode people = objectMapper.readTree(mapJson).path("people");
        JsonNode chen = StreamSupport.stream(people.spliterator(), false)
                .filter(p -> "P-004".equals(p.path("id").asText()))
                .findFirst().orElseThrow();
        assertThat(chen.path("x").asDouble()).isEqualTo(60.0);
        assertThat(chen.path("y").asDouble()).isEqualTo(20.0);

        String liveJson = mvc.perform(get("/api/v1/personnel/live").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode list = objectMapper.readTree(liveJson).path("list");
        JsonNode chenLive = StreamSupport.stream(list.spliterator(), false)
                .filter(p -> "P-004".equals(p.path("id").asText()))
                .findFirst().orElseThrow();
        assertThat(chenLive.path("x").asDouble()).isEqualTo(60.0);
        assertThat(chenLive.path("area").asText()).isEqualTo("龙门吊作业区");
    }

    @Test
    @DisplayName("缺机器凭据 401")
    void rejectsWithoutMachineCredential() throws Exception {
        mvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"id\":\"P-ZHAO\",\"x\":20,\"y\":22}")))
                .andExpect(status().isUnauthorized());
    }

    // ---- helpers ----

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String jwt(String sub, String[] roles, String aud, long exp) {
        StringBuilder rolesJson = new StringBuilder("[");
        for (int i = 0; i < roles.length; i++) {
            if (i > 0) {
                rolesJson.append(",");
            }
            rolesJson.append("\"").append(roles[i]).append("\"");
        }
        rolesJson.append("]");
        String payload = "{\"sub\":\"" + sub + "\",\"iss\":\"iam\",\"aud\":\"" + aud
                + "\",\"roles\":" + rolesJson + ",\"exp\":" + exp + "}";

        String signingInput = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." + b64(payload);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
            return signingInput + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
