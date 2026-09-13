package com.bproject.ehm.contract;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void openApiIsValidYamlAndContainsAssetStructureEndpoints() {
        InputStream stream = getClass().getResourceAsStream("/static/openapi/ehm-v1.yaml");
        assertNotNull(stream);
        Map<String, Object> root = new Yaml().load(stream);
        Map<String, Object> info = (Map<String, Object>) root.get("info");
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");

        assertEquals("1.8.0", info.get("version"));
        assertTrue(paths.containsKey("/assets/{assetCode}/components"));
        assertTrue(paths.containsKey("/assets/{assetCode}/measurement-points"));
        assertTrue(paths.containsKey("/data-quality/summary"));
        assertTrue(paths.containsKey("/telemetry/points/{pointCode}/samples"));
        assertTrue(paths.containsKey("/alarms/{alarmNo}/case"));
        assertTrue(paths.containsKey("/alarms/{alarmNo}/case/diagnoses"));
        assertTrue(paths.containsKey("/alarms/{alarmNo}/case/work-order"));
        assertTrue(paths.containsKey("/work-orders/{orderNo}/case/execution-records"));
        assertTrue(paths.containsKey("/work-orders/{orderNo}/case/retests"));
        assertTrue(paths.containsKey("/work-orders/{orderNo}/case/close"));
        assertTrue(paths.containsKey("/devices/{assetCode}/health-assessments/run"));
        assertTrue(paths.containsKey("/devices/{assetCode}/health-assessments/latest"));
        assertTrue(paths.containsKey("/health-assessments/{assessmentId}/review"));
        assertTrue(paths.containsKey("/health-assessments/{assessmentId}/work-order"));
        assertTrue(paths.containsKey("/maintenance-plans"));
        assertTrue(paths.containsKey("/maintenance-plans/{planId}/inspection-tasks"));
        assertTrue(paths.containsKey("/inspection-tasks/{taskNo}/submit"));
        assertTrue(paths.containsKey("/maintenance-defects/{defectNo}/work-order"));
        assertTrue(paths.containsKey("/spare-parts"));
        assertTrue(paths.containsKey("/spare-parts/{partCode}/receipt"));
        assertTrue(paths.containsKey("/spare-stock/alerts"));
        assertTrue(paths.containsKey("/spare-stock/transactions"));
        assertTrue(paths.containsKey("/work-orders/{orderNo}/spare-reservations"));
        assertTrue(paths.containsKey("/spare-reservations/{reservationNo}/issue"));
        assertTrue(paths.containsKey("/spare-reservations/{reservationNo}/release"));
        assertTrue(paths.containsKey("/reliability/failure-modes"));
        assertTrue(paths.containsKey("/reliability/failure-modes/{id}"));
        assertTrue(paths.containsKey("/reliability/fault-codes"));
        assertTrue(paths.containsKey("/reliability/alarm-rules"));
        assertTrue(paths.containsKey("/reliability/alarm-rules/{code}/publish"));
        assertTrue(paths.containsKey("/reliability/alarm-rules/{code}/disable"));
        assertTrue(paths.containsKey("/reliability/knowledge-cases"));
        assertTrue(paths.containsKey("/reliability/knowledge-cases/{caseNo}/verify"));
        assertTrue(paths.containsKey("/reliability/sla-policies"));
        assertTrue(paths.containsKey("/reliability/sla-policies/{severity}"));
        assertTrue(paths.containsKey("/system/capabilities"));
        assertTrue(paths.containsKey("/system/delivery-readiness"));
        assertTrue(paths.containsKey("/system/audit-logs"));
    }
}
