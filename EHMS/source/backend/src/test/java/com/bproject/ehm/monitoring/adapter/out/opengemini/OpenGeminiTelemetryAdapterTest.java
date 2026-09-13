package com.bproject.ehm.monitoring.adapter.out.opengemini;

import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort.TelemetrySample;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenGeminiTelemetryAdapterTest {
    private final OpenGeminiTelemetryAdapter adapter = new OpenGeminiTelemetryAdapter(
            RestClient.builder(), new ObjectMapper().findAndRegisterModules(),
            "http://127.0.0.1:8086", "ehm_telemetry", "", "",
            Duration.ofSeconds(1), Duration.ofSeconds(1), false);

    @Test
    void createsEscapedInfluxLineProtocolWithNanosecondTimestamp() {
        TelemetrySample sample = new TelemetrySample(
                "GT 01,VIB=RMS", "GT-01", "vibration", "mm/s", 6.8, "GOOD",
                Instant.parse("2026-09-10T01:02:03.123456789Z"),
                Instant.parse("2026-09-10T01:02:04Z"));

        String line = adapter.lineProtocol(sample);

        assertTrue(line.startsWith("ehm_point_value,point_code=GT\\ 01\\,VIB\\=RMS,asset_code=GT-01"));
        assertTrue(line.contains(" value=6.8,quality=\"GOOD\",unit=\"mm/s\""));
        assertTrue(line.endsWith("1789002123123456789"));
    }

    @Test
    void decodesInfluxV1QueryResponseByColumnName() {
        String response = """
                {"results":[{"series":[{
                  "name":"ehm_point_value",
                  "columns":["time","value","quality","unit","metric","asset_code","source_timestamp","received_at"],
                  "values":[[1789002123123,6.8,"GOOD","mm/s","vibration","GT-01",
                    "2026-09-10T01:02:03.123456789Z","2026-09-10T01:02:04Z"]]
                }]}]}
                """;

        List<TelemetrySample> values = adapter.decodeHistory(response, "GT01-VIB-RMS");

        assertEquals(1, values.size());
        assertEquals("GT01-VIB-RMS", values.get(0).pointCode());
        assertEquals("GT-01", values.get(0).assetCode());
        assertEquals(6.8, values.get(0).value());
        assertEquals(Instant.parse("2026-09-10T01:02:03.123456789Z"), values.get(0).sourceTimestamp());
    }
}
