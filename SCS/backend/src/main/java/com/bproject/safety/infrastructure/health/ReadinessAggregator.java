package com.bproject.safety.infrastructure.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 就绪检查聚合器：汇总 application 与四项基础设施探针。
 * 任一探针 DOWN 则整体 DOWN；DISABLED 视为未启用、不阻断就绪。
 */
@Component
public class ReadinessAggregator {
    private final List<InfrastructureProbe> probes;

    public ReadinessAggregator(List<InfrastructureProbe> probes) {
        this.probes = probes;
    }

    public ReadinessReport aggregate() {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("application", ProbeResult.Status.UP.name());
        boolean allUp = true;
        for (InfrastructureProbe probe : probes) {
            ProbeResult result = safeProbe(probe);
            components.put(probe.componentName(), result.status().name());
            if (result.status() == ProbeResult.Status.DOWN) {
                allUp = false;
            }
        }
        return new ReadinessReport(allUp ? "UP" : "DOWN", components);
    }

    private ProbeResult safeProbe(InfrastructureProbe probe) {
        try {
            return probe.probe();
        } catch (RuntimeException ex) {
            return ProbeResult.down(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * @param status     UP / DOWN
     * @param components 各组件状态（UP/DOWN/DISABLED）
     */
    public record ReadinessReport(String status, Map<String, String> components) {
    }
}
