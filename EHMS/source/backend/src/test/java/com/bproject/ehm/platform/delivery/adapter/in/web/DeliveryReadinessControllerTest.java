package com.bproject.ehm.platform.delivery.adapter.in.web;

import com.bproject.ehm.platform.health.ports.ReadinessProbe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeliveryReadinessControllerTest {
    @Test
    void reportsServerAdaptersFromRuntimeConfiguration() {
        ReadinessProbe probe = () -> new ReadinessProbe.ProbeResult(true, "openGauss+openGemini", "UP", null);
        DeliveryReadinessController controller = new DeliveryReadinessController(
                probe, "openGauss", "openGemini", "1.3.0-server",
                false, false, false, false);

        List<Map<String, Object>> capabilities = controller.capabilities();
        assertEquals("DISABLED", status(capabilities, "mongo"));
        assertEquals("ACTIVE", status(capabilities, "opengauss"));
        assertEquals("ACTIVE", status(capabilities, "opengemini"));
        assertEquals("1.3.0-server", controller.deliveryReadiness().get("edition"));
    }

    private Object status(List<Map<String, Object>> capabilities, String id) {
        return capabilities.stream()
                .filter(item -> id.equals(item.get("id")))
                .findFirst()
                .orElseThrow()
                .get("status");
    }
}
