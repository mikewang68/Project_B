package com.bproject.safety.module.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.collision.repository.InMemoryCollisionRepository;
import com.bproject.safety.module.collision.service.CollisionService;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.personnel.repository.InMemoryPersonnelRepository;
import com.bproject.safety.module.personnel.service.PersonnelPositionService;
import com.bproject.safety.module.personnel.service.UwbStationService;
import com.bproject.safety.module.zone.service.ZoneStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract regression for device inputs present in the deployed application. */
@SpringBootTest(properties = {
    "app.security.enabled=true", "app.security.browser-sso-only=true",
    "app.security.api-key=isolated-ingest-test-only",
    "app.security.iam-base-url=http://127.0.0.1:1",
    "app.security.iam-service-token=isolated-iam-test-only"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceIngestPreservationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.bproject.safety.config.ScsScopedReferenceData scopeReferences;
    @Autowired MockMvc mvc;
    @Autowired CollisionRepository devices;
    @Autowired CollisionService collision;
    @Autowired PersonnelRepository people;
    @Autowired PersonnelPositionService positions;
    @Autowired ZoneStateService zones;
    @Autowired UwbStationService stations;

    @BeforeEach void resetMasterData() {
        // Explicit legacy ownership for this contract fixture; no all-ID fallback.
        org.mockito.Mockito.when(scopeReferences.legacyIngestAllowed(
                com.bproject.safety.config.ScsCentralScope.Root.AREA, "LOADING-A")).thenReturn(true);
        org.mockito.Mockito.when(scopeReferences.legacyIngestAllowed(
                com.bproject.safety.config.ScsCentralScope.Root.RUNTIME, "UWB:contract-A")).thenReturn(true);
        ((InMemoryCollisionRepository) devices).resetDemoData();
        ((InMemoryPersonnelRepository) people).resetDemoData();
        collision.resetAllPairs();
    }

    private void ingest(String endpoint, String json, int applied) throws Exception {
        mvc.perform(post("/api/v1/ingest/" + endpoint)
                .header("X-API-Key", "isolated-ingest-test-only")
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(applied));
    }

    @Test void deviceStatePreservesIdentityAndClampsCoordinatesAndSpeed() throws Exception {
        int before = devices.findAll().size();
        ingest("device-state", """
            {"source":"contract-test","devices":[
              {"code":"VEH-07","speed":90,"x":123,"y":-2,"status":"working"},
              {"code":"missing"},null]}
            """, 1);
        var device = devices.findById("VEH-07").orElseThrow();
        assertThat(device.speed).isEqualTo(60);
        assertThat(device.x).isEqualTo(100);
        assertThat(device.y).isZero();
        assertThat(device.status).isEqualTo("working");
        assertThat(devices.findAll()).hasSize(before);
    }

    @Test void radarKeepsDistanceSpeedQualityAndHealthContracts() throws Exception {
        ingest("radar", """
            {"readings":[{"pairDevice":"VEH-07","distance":7,"relSpeed":-1.5,
              "radarQuality":55.6,"health":"DOWN"},{"pairDevice":"missing","distance":1}]}
            """, 1);
        var pair = collision.pair("VEH-07", null);
        assertThat(pair.distance()).isEqualTo(7);
        assertThat(pair.relSpeed()).isEqualTo(-1.5);
        assertThat(pair.radarQuality()).isEqualTo(56);
        assertThat(pair.radarDown()).isTrue();
        ingest("radar", """
            {"readings":[{"pairDevice":"VEH-07","distance":15,"health":"NORMAL"}]}
            """, 1);
        assertThat(collision.pair("VEH-07", null).radarDown()).isFalse();
    }

    @Test void personnelUpdatesOverlayWithoutCreatingOrChangingMasterRecords() throws Exception {
        var before = people.findById("P-ZHAO").orElseThrow();
        double masterX = before.x;
        ingest("person-position", """
            {"positions":[{"id":"P-ZHAO","x":12,"y":20,"battery":70,"accuracy":0.2},
              {"id":"missing","x":12,"y":20},null]}
            """, 1);
        assertThat(positions.overlay(people.findById("P-ZHAO").orElseThrow()).x).isEqualTo(12);
        assertThat(positions.overlay(people.findById("P-ZHAO").orElseThrow()).battery).isEqualTo(70);
        assertThat(people.findById("P-ZHAO").orElseThrow().x).isEqualTo(masterX);
        assertThat(people.findById("missing")).isEmpty();
    }

    @Test void zonesRetainPartialSignalsAndRejectUnknownCodes() throws Exception {
        ingest("zone-state", """
            {"zones":[{"code":"LOADING-A","personnelWorking":true,"distanceMm":11000},
              {"code":"missing","vehiclePresent":true}]}
            """, 1);
        ingest("zone-state", """
            {"zones":[{"code":"LOADING-A","vehiclePresent":false}]}
            """, 1);
        var zone = zones.list().stream().filter(value -> value.code().equals("LOADING-A")).findFirst().orElseThrow();
        assertThat(zone.personnelWorking()).isTrue();
        assertThat(zone.vehiclePresent()).isFalse();
        assertThat(zone.distanceMm()).isEqualTo(10000);
        assertThat(zones.list()).hasSize(2);
    }

    @Test void uwbKeepsStationHealthAndSummaryResponse() throws Exception {
        ingest("uwb-station", """
            {"stations":[{"stationId":"contract-A","online":true,"clockSynced":false,
                "snrDb":24,"powerOk":true,"tagCount":2},{"stationId":""},null]}
            """, 1);
        var station = stations.list().stream().filter(value -> value.stationId().equals("contract-A")).findFirst().orElseThrow();
        assertThat(station.online()).isTrue();
        assertThat(station.clockSynced()).isFalse();
        assertThat(station.snrDb()).isEqualTo(24);
        assertThat(station.tagCount()).isEqualTo(2);
    }

    @Test void missingOrInvalidMachineCredentialsDoNotChangeDeviceState() throws Exception {
        double before = devices.findById("VEH-07").orElseThrow().speed;
        for (String key : new String[]{"", "invalid-test-key"}) {
            mvc.perform(post("/api/v1/ingest/device-state").header("X-API-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"devices\":[{\"code\":\"VEH-07\",\"speed\":55}]}"))
                .andExpect(status().isUnauthorized());
        }
        assertThat(devices.findById("VEH-07").orElseThrow().speed).isEqualTo(before);
    }

    @Test void machineCredentialsCannotReadBusinessListsOrMutateRules() throws Exception {
        for (String path : new String[]{"/api/v1/zones", "/api/v1/personnel/uwb-stations",
                "/api/v1/collision/devices", "/api/v1/rules"}) {
            mvc.perform(get(path).header("X-API-Key", "isolated-ingest-test-only"))
                .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/rules").header("X-API-Key", "isolated-ingest-test-only")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }
}
