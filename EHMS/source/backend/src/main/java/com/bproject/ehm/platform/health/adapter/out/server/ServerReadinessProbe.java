package com.bproject.ehm.platform.health.adapter.out.server;

import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.platform.health.ports.ReadinessProbe;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("server")
public class ServerReadinessProbe implements ReadinessProbe {
    private final OpenGaussJsonStore openGauss;
    private final TelemetrySeriesPort openGemini;

    public ServerReadinessProbe(OpenGaussJsonStore openGauss, TelemetrySeriesPort openGemini) {
        this.openGauss = openGauss;
        this.openGemini = openGemini;
    }

    @Override
    public ProbeResult check() {
        try {
            openGauss.ping();
        } catch (Exception exception) {
            return new ProbeResult(false, "openGauss", "DOWN", safeMessage(exception));
        }
        try {
            openGemini.ping();
        } catch (Exception exception) {
            return new ProbeResult(false, "openGemini", "DOWN", safeMessage(exception));
        }
        return new ProbeResult(true, "openGauss+openGemini", "UP", null);
    }

    private String safeMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}
