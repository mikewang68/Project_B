package com.bproject.ehm.samples.application;

import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Explicit, persistent synthetic adapter. Never a fallback for a failed openGemini connection. */
@Component @Profile("server")
@ConditionalOnProperty(name="ehm.telemetry.adapter",havingValue="sample")
public class SampleTelemetryAdapter implements TelemetrySeriesPort {
    private final OpenGaussJsonStore store;
    public SampleTelemetryAdapter(OpenGaussJsonStore store,@Value("${ehm.sample-data.enabled:false}") boolean enabled){
        if(!enabled)throw new IllegalStateException("Sample telemetry requires explicit EHM_SAMPLE_DATA_ENABLED=true");
        this.store=store;
    }
    public void write(TelemetrySample sample){
        if(!sample.pointCode().startsWith("SAMPLE-")||!sample.assetCode().startsWith("SAMPLE-"))
            throw new IllegalArgumentException("Synthetic adapter only accepts SAMPLE- points and assets");
        var marked=new TelemetrySample(sample.pointCode(),sample.assetCode(),sample.metric(),sample.unit(),sample.value(),
                "SYNTHETIC_"+sample.quality().replace("SYNTHETIC_",""),sample.sourceTimestamp(),sample.receivedAt());
        store.save("sample-telemetry",sample.pointCode()+":"+sample.sourceTimestamp(),marked);
    }
    public List<TelemetrySample> history(String code,Instant from,Instant to,int limit){
        Instant end=to==null?Instant.now():to,start=from==null?end.minusSeconds(86400):from;
        if(!start.isBefore(end)||limit<1||limit>10000)throw new IllegalArgumentException("Invalid sample history range or limit");
        return store.findAll("sample-telemetry",TelemetrySample.class).stream().filter(s->code.equals(s.pointCode()))
                .filter(s->!s.sourceTimestamp().isBefore(start)&&!s.sourceTimestamp().isAfter(end))
                .sorted(Comparator.comparing(TelemetrySample::sourceTimestamp).reversed()).limit(limit)
                .sorted(Comparator.comparing(TelemetrySample::sourceTimestamp)).toList();
    }
    public void ping(){store.ping();}
}
