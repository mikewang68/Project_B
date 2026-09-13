package com.bproject.ehm.platform.health.ports;

public interface ReadinessProbe {
    ProbeResult check();

    record ProbeResult(boolean ready, String component, String status, String message) {
    }
}
