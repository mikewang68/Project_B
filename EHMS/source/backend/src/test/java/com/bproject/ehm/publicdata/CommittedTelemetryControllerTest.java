package com.bproject.ehm.publicdata;
import com.bproject.ehm.monitoring.adapter.in.web.TelemetryController;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CommittedTelemetryControllerTest {
    @SuppressWarnings("unchecked") ObjectProvider<OpenGaussJsonStore> provider(OpenGaussJsonStore store){var p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(store);return p;}
    @Test void publicPointsUseScopedDatabaseHistoryNotRealOrSyntheticFallback(){
        var real=mock(TelemetrySeriesPort.class);var store=mock(OpenGaussJsonStore.class);Instant start=Instant.EPOCH,end=start.plusSeconds(60);
        when(store.recent("committed-observation","pointCode","MQ-SIM-1-EHM-1","sourceTimestamp",start,end,20,com.fasterxml.jackson.databind.JsonNode.class)).thenReturn(List.of());
        var controller=new TelemetryController(real,provider(store));assertEquals(List.of(),controller.history("MQ-SIM-1-EHM-1",start,end,20));
        verify(store).recent("committed-observation","pointCode","MQ-SIM-1-EHM-1","sourceTimestamp",start,end,20,com.fasterxml.jackson.databind.JsonNode.class);verifyNoInteractions(real);
    }
    @Test void realPointsRemainOnOriginalAdapter(){
        var real=mock(TelemetrySeriesPort.class);var store=mock(OpenGaussJsonStore.class);var controller=new TelemetryController(real,provider(store));
        controller.history("P-1",null,null,20);
        var from=org.mockito.ArgumentCaptor.forClass(Instant.class);var to=org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(real).history(eq("P-1"),from.capture(),to.capture(),eq(20));
        assertEquals(java.time.Duration.ofDays(1),java.time.Duration.between(from.getValue(),to.getValue()));verifyNoInteractions(store);
    }
    @Test void missingPublicStoreIsErrorNotSilentFallback(){
        var real=mock(TelemetrySeriesPort.class);var controller=new TelemetryController(real,provider(null));
        assertThrows(IllegalStateException.class,()->controller.history("MQ-SIM-1-EHM-1",null,null,20));verifyNoInteractions(real);
    }
    @Test void publicHistoryPreservesBadNullAndSourceEvent()throws Exception{
        var real=mock(TelemetrySeriesPort.class);var store=mock(OpenGaussJsonStore.class);var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var raw=json.readTree("{\"eventId\":\"EVENT-7\",\"assetCode\":\"EQ-1\",\"originalPointCode\":\"PT-1\",\"simulated\":true,\"sourceTimestamp\":\"1970-01-01T00:00:01Z\",\"receivedAt\":2.5,\"rawPoint\":{\"value\":null,\"quality\":\"BAD\",\"unit\":\"A\"}}");
        when(store.recent(anyString(),anyString(),anyString(),anyString(),any(),any(),anyInt(),eq(com.fasterxml.jackson.databind.JsonNode.class))).thenReturn(List.of(raw));
        var result=new TelemetryController(real,provider(store)).history("MQ-SIM-P",Instant.EPOCH,Instant.EPOCH.plusSeconds(10),20).get(0);
        assertNull(result.value());assertEquals("BAD",result.quality());assertEquals("EVENT-7",result.eventId());assertTrue(result.simulated());assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("EVENT-7\nPT-1".getBytes(java.nio.charset.StandardCharsets.UTF_8))),result.observationRef());assertEquals(Instant.ofEpochMilli(2500),result.receivedAt());verifyNoInteractions(real);
    }
    @Test void originalObservationLookupCannotSubstituteAnotherPoint()throws Exception{
        var store=mock(OpenGaussJsonStore.class);var real=mock(TelemetrySeriesPort.class);String ref="a".repeat(64);
        var raw=new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"pointCode\":\"MQ-SIM-P1\",\"eventId\":\"EVENT-1\",\"rawPoint\":{\"value\":null}}");
        when(store.find("committed-observation",ref,com.fasterxml.jackson.databind.JsonNode.class)).thenReturn(java.util.Optional.of(raw));
        var web=new TelemetryController(real,provider(store));assertEquals(raw,web.observation("MQ-SIM-P1",ref));assertThrows(com.bproject.ehm.shared.error.ResourceNotFoundException.class,()->web.observation("OTHER",ref));verifyNoInteractions(real);
    }
    @Test void invalidRangeAndOversizedRequestsAreExplicitErrors(){
        var real=mock(TelemetrySeriesPort.class);var c=new TelemetryController(real,provider(null));
        assertThrows(com.bproject.ehm.shared.error.ValidationException.class,()->c.history("P",Instant.EPOCH,Instant.EPOCH,20));
        assertThrows(com.bproject.ehm.shared.error.ValidationException.class,()->c.history("P",Instant.EPOCH,Instant.EPOCH.plusSeconds(91*86400),20));
        assertThrows(com.bproject.ehm.shared.error.ValidationException.class,()->c.history("P",null,null,2001));verifyNoInteractions(real);
    }
}
