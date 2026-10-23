package com.bproject.safety.config;

import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeEventQueueRepository;
import com.bproject.safety.module.projection.shared.SafetyProjectionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("D-2: SCS 业务指标绑定（告警态势 + 边缘队列深度）")
class SafetyMetricsConfigurationTest {

    private InMemoryAlertRepository alerts;
    private InMemoryEdgeEventQueueRepository edgeQueue;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        alerts = new InMemoryAlertRepository();
        alerts.clearDemoData();
        edgeQueue = new InMemoryEdgeEventQueueRepository();
        registry = new SimpleMeterRegistry();
    }

    private void seedAlert(String status, String risk) {
        DemoAlert a = new DemoAlert();
        a.id = "ALM-METRICS-" + System.nanoTime();
        a.title = "指标测试告警";
        a.statusCode = status;
        a.riskCode = risk;
        a.occurredAt = OffsetDateTime.now();
        alerts.save(a);
    }

    @Test
    @DisplayName("五个业务 gauge 正确注册并反映存储态势")
    void businessGaugesRegistered() {
        seedAlert(AlertStatuses.PENDING_CONFIRM, "SEVERE");
        seedAlert(AlertStatuses.PROCESSING, "SEVERE");
        seedAlert(AlertStatuses.CLOSED, "WARNING");

        new SafetyMetricsConfiguration(registry,
                new SafetyProjectionService(alerts), edgeQueue);

        assertThat(registry.get("scs.alerts.total").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("scs.alerts.open").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("scs.alerts.processing").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("scs.alerts.closed").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("scs.edge.pending.events").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("gauge 为实时回调：数据变化后无需重注册")
    void gaugesAreLiveCallbacks() {
        SafetyProjectionService projection = new SafetyProjectionService(alerts);
        new SafetyMetricsConfiguration(registry, projection, edgeQueue);
        assertThat(registry.get("scs.alerts.total").gauge().value()).isEqualTo(0.0);

        seedAlert(AlertStatuses.PENDING_CONFIRM, "URGENT");
        assertThat(registry.get("scs.alerts.total").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("scs.alerts.open").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("边缘队列深度随补传事件增长")
    void edgeQueueDepthTracked() {
        var projection = new SafetyProjectionService(alerts);
        new SafetyMetricsConfiguration(registry, projection, edgeQueue);

        var e = new com.bproject.safety.module.ops.model.EdgePendingEvent();
        e.eventId = "EDGE-EVT-M1";
        e.edgeNodeId = "EDGE-01";
        e.eventType = "person-intrusion";
        e.edgeOccurredAt = OffsetDateTime.now();
        e.receivedAt = OffsetDateTime.now();
        e.idempotencyKey = "EDGE-01:person-intrusion:1:1";
        e.risk = "严重";
        e.status = "PENDING";
        edgeQueue.save(e);

        assertThat(registry.get("scs.edge.pending.events").gauge().value()).isEqualTo(1.0);
    }
}
