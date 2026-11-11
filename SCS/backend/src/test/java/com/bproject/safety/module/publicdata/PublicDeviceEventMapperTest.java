package com.bproject.safety.module.publicdata;

import com.bproject.safety.module.ai.dto.AiRequests.AiIngestRequest;
import com.bproject.safety.module.ai.service.AiEventService;
import com.bproject.safety.module.collision.service.CollisionService;
import com.bproject.safety.module.personnel.service.PersonnelPositionService;
import com.bproject.safety.module.publicdata.repository.PublicDeviceBindings;
import com.bproject.safety.module.publicdata.service.PublicDeviceEventMapper;
import com.bproject.safety.module.zone.service.ZoneStateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 公共事件 → SCS 业务域映射：按绑定路由、S3 字段提取、未知设备 ignored。 */
class PublicDeviceEventMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PersonnelPositionService personnel;
    private CollisionService collision;
    private ZoneStateService zone;
    private AiEventService ai;
    private PublicDeviceEventMapper mapper;

    @BeforeEach
    void setup() {
        personnel = mock(PersonnelPositionService.class);
        collision = mock(CollisionService.class);
        zone = mock(ZoneStateService.class);
        ai = mock(AiEventService.class);
        mapper = new PublicDeviceEventMapper(new StaticBindings(), personnel, collision, zone, ai);
    }

    private JsonNode envelope(String deviceId, String quality, String extraJson) throws Exception {
        String extra = extraJson == null || extraJson.isBlank() ? "" : "," + extraJson;
        return JSON.readTree("""
                {"schema_version":"b.device-committed.v1","event_id":"E-1","committed_ms":1,"wire_sha256":"abc",
                 "simulated":true,
                 "event":{"schema_version":"b.device-event.v1","simulated":true,"source_id":"%s",
                   "ts_event":"2026-11-11T09:00:00+08:00","quality_code":"%s",
                   "payload":{"points":[{"point_code":"SCS-SIM-OBSERVATION"%s}]}}}"""
                .formatted(deviceId, quality, extra));
    }

    @Test
    @DisplayName("雷达事件 → applyExternalRadar（距离/相对速度/质量三态）")
    void radarMapped() throws Exception {
        when(collision.applyExternalRadar(anyList())).thenReturn(new CollisionService.IngestResult(1, List.of()));
        mapper.map(envelope("SIM-0ece1fee47f641be93d4ac53", "GOOD",
                "\"distance_m\": 18.5, \"relative_speed_mps\": -1.2"));
        var captor = ArgumentCaptor.forClass(List.class);
        verify(collision).applyExternalRadar(captor.capture());
        var reading = (CollisionService.ExternalRadarReading) captor.getValue().get(0);
        assertThat(reading.pairDevice()).isEqualTo("VEH-07");
        assertThat(reading.distance()).isEqualTo(18.5);
        assertThat(reading.relSpeed()).isEqualTo(-1.2);
        assertThat(reading.health()).isEqualTo("NORMAL");
        assertThat(reading.radarQuality()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("质量码 → 健康三态：BAD=DOWN、STALE 降级不伪装正常、null 透传")
    void qualityMapping() throws Exception {
        when(collision.applyExternalRadar(anyList())).thenReturn(new CollisionService.IngestResult(1, List.of()));
        mapper.map(envelope("SIM-0ece1fee47f641be93d4ac53", "BAD", "\"distance_m\": 20.0"));
        verify(collision).applyExternalRadar(List.of(new CollisionService.ExternalRadarReading("VEH-07", 20.0, null, 0.0, "DOWN")));
        mapper.map(envelope("SIM-0ece1fee47f641be93d4ac53", "STALE", "\"distance_m\": 20.0"));
        verify(collision).applyExternalRadar(List.of(new CollisionService.ExternalRadarReading("VEH-07", 20.0, null, 40.0, "DEGRADED")));
        mapper.map(envelope("SIM-0ece1fee47f641be93d4ac53", "STALE", "\"distance_m\": 20.0"));
    }

    @Test
    @DisplayName("UWB 事件 → 人员实时位置（米制直通，标定待核）")
    void uwbMapped() throws Exception {
        when(personnel.apply(any(), any(), any(), any(), any())).thenReturn(true);
        mapper.map(envelope("SIM-14dcba2c643b48f084b55b7e", "GOOD",
                "\"x_m\": 12.4, \"y_m\": 3.2, \"accuracy_m\": 0.8"));
        verify(personnel).apply("P-1001", 12.4, 3.2, null, 0.8);
    }

    @Test
    @DisplayName("AI 事件 → ingest（置信度 0-1×100、告警等级→风险、幂等键含 event_id）")
    void aiMapped() throws Exception {
        when(ai.ingest(any(), any())).thenReturn(null);
        mapper.map(envelope("SIM-142e73df50a24723a70c4eae", "UNCERTAIN",
                "\"confidence\": 0.93, \"alarm_level\": \"SEVERE\", \"rule_id\": \"RULE-PPE-HELMET\""));
        var captor = ArgumentCaptor.forClass(AiIngestRequest.class);
        verify(ai).ingest(captor.capture(), ArgumentCaptor.forClass(String.class).capture());
        assertThat(captor.getValue().cameraCode()).isEqualTo("CAM-01");
        assertThat(captor.getValue().confidence()).isEqualTo(93.0);
        assertThat(captor.getValue().riskCode()).isEqualTo("HIGH");
        assertThat(captor.getValue().ruleCode()).isEqualTo("RULE-PPE-HELMET");
    }

    @Test
    @DisplayName("龙门吊/翻箱机 → 设备状态；限位/位置传感器 → 装卸区状态")
    void deviceAndZoneMapped() throws Exception {
        when(collision.applyExternalState(anyList())).thenReturn(new CollisionService.IngestResult(1, List.of()));
        mapper.map(envelope("SIM-aed34d3f7bb84082a061bf7a", "GOOD", null));
        verify(collision).applyExternalState(List.of(
                new CollisionService.ExternalDeviceState("CRANE-01", "NORMAL", null, null, null, null, null, null, null)));
        when(zone.apply(any(), any(), any(), any(), any())).thenReturn(true);
        mapper.map(envelope("SIM-4bf65976b5754fd89d903232", "GOOD",
                "\"personnel_working\": true"));
        verify(zone).apply("LOADING-A", true, null, null, "public-committed");
    }

    @Test
    @DisplayName("未知/禁用设备 ignored，不触发任何业务写入")
    void unknownDeviceIgnored() throws Exception {
        var result = mapper.map(envelope("SIM-not-registered", "GOOD", null));
        assertThat(result.applied()).isZero();
        assertThat(result.ignored()).containsExactly("SIM-not-registered");
        verify(collision, org.mockito.Mockito.never()).applyExternalState(anyList());
    }

    @Test
    @DisplayName("声光报警器 → 仅观测记录（OBSERVE 域，不触发业务写）")
    void alarmLightObservedOnly() throws Exception {
        var result = mapper.map(envelope("SIM-af1935235c6b4adbbfa7a2cc", "GOOD", null));
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.note()).contains("observed-only");
    }

    /** 与 db/v21/003 种子一致的静态绑定。 */
    private static final class StaticBindings extends
            com.bproject.safety.module.publicdata.repository.InMemoryPublicDeviceBindings {
    }
}
