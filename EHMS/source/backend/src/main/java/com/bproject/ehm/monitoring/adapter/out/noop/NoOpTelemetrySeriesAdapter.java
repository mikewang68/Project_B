package com.bproject.ehm.monitoring.adapter.out.noop;

import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Profile("!server")
public class NoOpTelemetrySeriesAdapter implements TelemetrySeriesPort {
    @Override
    public void write(TelemetrySample sample) {
        // 本地Mongo演示仍由原有快照仓储提供数据，不额外要求openGemini。
    }

    @Override
    public List<TelemetrySample> history(String pointCode, Instant from, Instant to, int limit) {
        return List.of();
    }

    @Override
    public void ping() {
        // 本地演示档不启用时序库。
    }
}
