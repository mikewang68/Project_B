package com.bproject.safety.module.collision;

import com.bproject.iam.context.ScopeTriple;
import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.config.ScsCentralScope;
import com.bproject.safety.config.ScsMachineRegistry;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.collision.service.CollisionService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 命名雷达来源的最小绑定：只登记上报设备 VEH-07、不登记对端 TIP-02 时，
 * 仍应能用当前设备的归属读取同一配对的对端并建出告警，而不是 500。
 */
class CollisionMachineRadarTest {

    private static final ScopeTriple TRIPLE = new ScopeTriple("PROJECT_B", "SHEN_TIE", "SCS");

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void minimalBindingOwnsCurrentDeviceAndCanReadRelatedLegacyDevice() {
        var binding = mock(ScsMachineRegistry.Binding.class);
        when(binding.owns(ScsCentralScope.Root.COLLISION, "VEH-07")).thenReturn(true);
        when(binding.owns(ScsCentralScope.Root.COLLISION, "TIP-02")).thenReturn(false);
        when(binding.scope()).thenReturn(TRIPLE);
        var request = new MockHttpServletRequest("POST", "/api/v1/ingest/radar");
        ScsMachineRegistry.bind(request, binding);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        var repository = mock(CollisionRepository.class);
        when(repository.findById("VEH-07")).thenReturn(Optional.of(device("VEH-07", "转运车")));
        when(repository.findById("TIP-02")).thenReturn(Optional.empty());
        when(repository.findByIdUnscoped(eq("TIP-02"), eq(TRIPLE)))
                .thenReturn(Optional.of(device("TIP-02", "翻箱机")));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var alertService = mock(AlertService.class);
        var alert = new DemoAlert();
        alert.id = "ALM-RADAR-TEST";
        alert.riskCode = "SEVERE";
        alert.statusCode = "PENDING_CONFIRM";
        when(alertService.findOpenByDedupKeyForMachine(anyString(), any())).thenReturn(null);
        when(alertService.createRiskAlert(any())).thenReturn(alert);

        var service = new CollisionService(repository, alertService,
                mock(DomainLivePublisher.class), Clock.systemUTC(), new LiveEventGate());
        service.resetAllPairs();

        var result = service.applyExternalRadar(List.of(
                new CollisionService.ExternalRadarReading("VEH-07", 5.0, 0.5, 90.0, "NORMAL")));

        assertEquals(1, result.applied());
        assertTrue(result.ignored().isEmpty());
        verify(repository).findByIdUnscoped("TIP-02", TRIPLE);
    }

    private DemoCollisionDevice device(String id, String name) {
        var device = new DemoCollisionDevice();
        device.id = id;
        device.name = name;
        device.area = "龙门吊作业区";
        return device;
    }
}
