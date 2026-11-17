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
    private OpenGaussJsonStore store;
    @org.springframework.beans.factory.annotation.Autowired
    public SimulationTelemetryRouter(OpenGeminiTelemetryAdapter real,OpenGaussJsonStore store,
            @Value("${ehm.simulation.enabled:false}") boolean enabled,
            @Value("${ehm.simulation.shared-data-approved:false}") boolean approved){
        if(!enabled||!approved)throw new IllegalStateException("Shared synthetic telemetry requires explicit enablement and approval");
        this.store=store;this.real=real;this.synthetic=new SampleTelemetryAdapter(store,true);
    }
    SimulationTelemetryRouter(TelemetrySeriesPort real,TelemetrySeriesPort synthetic){this.real=real;this.synthetic=synthetic;}
    public void write(TelemetrySample sample){
        if(demoPoint(sample.pointCode(),sample.assetCode())){
            var marked=new TelemetrySample(sample.pointCode(),sample.assetCode(),sample.metric(),sample.unit(),sample.value(),
                "SYNTHETIC_"+sample.quality().replace("SYNTHETIC_",""),sample.sourceTimestamp(),sample.receivedAt(),sample.eventId(),true,sample.observationRef());
            store.save("sample-telemetry",sample.pointCode()+":"+sample.sourceTimestamp(),marked);return;
        }
        boolean point=sample.pointCode().startsWith("SAMPLE-LIVE-"),asset=sample.assetCode().startsWith("SAMPLE-LIVE-");
        if(point!=asset)throw new IllegalArgumentException("Synthetic point and asset provenance must match");
        (point?synthetic:real).write(sample);
    }
    public List<TelemetrySample> history(String point,Instant from,Instant to,int limit){
        if(demoPoint(point,null)){
            Instant end=to==null?Instant.now():to,start=from==null?end.minusSeconds(86400):from;
            if(!start.isBefore(end)||limit<1||limit>10000)throw new IllegalArgumentException("Invalid demo history window");
            return store.recent("sample-telemetry","pointCode",point,"sourceTimestamp",start,end,limit,TelemetrySample.class).stream()
                .sorted(java.util.Comparator.comparing(TelemetrySample::sourceTimestamp)).toList();
        }
        return (point.startsWith("SAMPLE-LIVE-")?synthetic:real).history(point,from,to,limit);
    }
    private boolean demoPoint(String code,String asset){
        if(store==null||code==null)return false;
        var worker=SimulationRunScope.current();var central=com.bproject.ehm.platform.security.EhmCentralScope.current();
        boolean regional=worker!=null&&SimulationRunScope.REGION.equals(worker.regionId())
            ||central!=null&&(central.scopeMode()==com.bproject.iam.context.ScopeSql.Mode.GLOBAL
                ||central.activeScope().stream().anyMatch(t->SimulationRunScope.REGION.equals(t.regionId())&&"EHMS".equals(t.subsystemCode())));
        if(!regional)return false;
        var p=store.find("measurement-point",code,com.bproject.ehm.asset.domain.model.MeasurementPoint.class).orElse(null);
        if(p==null||!"SYNTHETIC".equals(p.sourceProtocol())||!p.sourceAddress().equals("sample://"+p.code())||asset!=null&&!asset.equals(p.assetCode()))return false;
        // The store enforces current authorized tuples; no cross-region fallback or real adapter fallback.
        var tuple=store.readTuple("measurement-point",code);
        return tuple!=null&&SimulationRunScope.REGION.equals(tuple.regionId());
    }
    public void ping(){real.ping();synthetic.ping();}
}
