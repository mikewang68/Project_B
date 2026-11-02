package com.bproject.ehm.simulation;

import com.bproject.ehm.monitoring.adapter.out.opengemini.OpenGeminiTelemetryAdapter;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.samples.application.SampleTelemetryAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;

/** Explicit routing, NOT a fallback: real points always use the existing openGemini adapter. */
@Component @Primary @Profile("server")
@ConditionalOnProperty(name="ehm.simulation.mode",havingValue="shared-synthetic")
public class SimulationTelemetryRouter implements TelemetrySeriesPort {
    private final TelemetrySeriesPort real,synthetic;
    @org.springframework.beans.factory.annotation.Autowired
    public SimulationTelemetryRouter(OpenGeminiTelemetryAdapter real,OpenGaussJsonStore store,
            @Value("${ehm.simulation.enabled:false}") boolean enabled,
            @Value("${ehm.simulation.shared-data-approved:false}") boolean approved){
        if(!enabled||!approved)throw new IllegalStateException("Shared synthetic telemetry requires explicit enablement and approval");
        this.real=real;this.synthetic=new SampleTelemetryAdapter(store,true);
    }
    SimulationTelemetryRouter(TelemetrySeriesPort real,TelemetrySeriesPort synthetic){this.real=real;this.synthetic=synthetic;}
    public void write(TelemetrySample sample){
        boolean point=sample.pointCode().startsWith("SAMPLE-LIVE-"),asset=sample.assetCode().startsWith("SAMPLE-LIVE-");
        if(point!=asset)throw new IllegalArgumentException("Synthetic point and asset provenance must match");
        (point?synthetic:real).write(sample);
    }
    public List<TelemetrySample> history(String point,Instant from,Instant to,int limit){
        return (point.startsWith("SAMPLE-LIVE-")?synthetic:real).history(point,from,to,limit);
    }
    public void ping(){real.ping();synthetic.ping();}
}
