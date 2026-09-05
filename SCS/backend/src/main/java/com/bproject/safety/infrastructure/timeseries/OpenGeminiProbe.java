package com.bproject.safety.infrastructure.timeseries;

import com.bproject.safety.infrastructure.health.InfrastructureProbe;
import com.bproject.safety.infrastructure.health.ProbeResult;
import org.springframework.stereotype.Component;

/** openGemini 探针：未启用返回 DISABLED，启用则执行 /ping。 */
@Component
public class OpenGeminiProbe implements InfrastructureProbe {
    private final OpenGeminiClient client;

    public OpenGeminiProbe(OpenGeminiClient client) {
        this.client = client;
    }

    @Override
    public String componentName() {
        return "openGemini";
    }

    @Override
    public ProbeResult probe() {
        if (!client.isEnabled()) {
            return ProbeResult.disabled("openGemini 未启用 (app.opengemini.enabled=false)");
        }
        OpenGeminiClient.PingResult result = client.ping();
        return result.ok()
                ? ProbeResult.up(result.detail() + (result.version().isBlank() ? "" : " version=" + result.version()))
                : ProbeResult.down(result.detail());
    }
}
