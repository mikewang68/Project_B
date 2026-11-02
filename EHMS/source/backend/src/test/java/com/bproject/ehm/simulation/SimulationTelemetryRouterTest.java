package com.bproject.ehm.simulation;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimulationTelemetryRouterTest {
 @Test void realFailuresNeverFallBackToSyntheticAndPrefixesCannotBeMixed(){
  var real=mock(TelemetrySeriesPort.class);var synthetic=mock(TelemetrySeriesPort.class);
  var router=new SimulationTelemetryRouter(real,synthetic);var now=Instant.now();
  var sample=new TelemetrySeriesPort.TelemetrySample("REAL-TEMP","REAL-01","温度","℃",30.0,"GOOD",now,now);
  doThrow(new IllegalStateException("real service unavailable")).when(real).write(sample);
  assertThrows(IllegalStateException.class,()->router.write(sample));verifyNoInteractions(synthetic);
  var invalid=new TelemetrySeriesPort.TelemetrySample("SAMPLE-LIVE-TEMP","REAL-01","温度","℃",30.0,"GOOD",now,now);
  assertThrows(IllegalArgumentException.class,()->router.write(invalid));verifyNoInteractions(synthetic);
 }
 @Test void explicitSyntheticSamplesAndHistoryStaySeparate(){
  var real=mock(TelemetrySeriesPort.class);var synthetic=mock(TelemetrySeriesPort.class);
  var router=new SimulationTelemetryRouter(real,synthetic);var now=Instant.now();
  var sample=new TelemetrySeriesPort.TelemetrySample("SAMPLE-LIVE-X-TEMP","SAMPLE-LIVE-X","温度","℃",30.0,"GOOD",now,now);
  router.write(sample);verify(synthetic).write(sample);verifyNoInteractions(real);
  when(synthetic.history(sample.pointCode(),null,null,10)).thenReturn(List.of(sample));
  assertEquals(1,router.history(sample.pointCode(),null,null,10).size());
  router.history("REAL-TEMP",null,null,10);verify(real).history("REAL-TEMP",null,null,10);
 }
}
