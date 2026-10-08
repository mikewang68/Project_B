package com.bproject.ehm.monitoring.application;

import com.bproject.ehm.asset.application.*;
import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import com.bproject.ehm.monitoring.domain.model.*;
import com.bproject.ehm.monitoring.ports.*;
import com.bproject.ehm.shared.page.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataQualityApplicationServiceTest {
    @Test void oldGoodSnapshotsBecomeMissingWithoutChangingSourceTimeOrWritingOnRead() {
        var points=mock(MeasurementPointQueryFacade.class);
        var quality=mock(PointQualityRepository.class);
        var assets=mock(AssetQueryFacade.class);
        var telemetry=mock(TelemetrySeriesPort.class);
        var point=mock(MeasurementPoint.class);
        Instant old=Instant.parse("2026-09-14T07:20:29Z"),now=Instant.parse("2026-10-07T07:00:00Z");
        when(assets.exists("GT-01")).thenReturn(true);
        when(point.code()).thenReturn("P-1");when(point.assetCode()).thenReturn("GT-01");when(point.enabled()).thenReturn(true);
        when(point.sampleIntervalSeconds()).thenReturn(5);when(point.upperLimit()).thenReturn(100.0);
        when(points.page(eq("GT-01"),any(),isNull())).thenReturn(PageResult.of(List.of(point),new PageQuery(0,200),1));
        when(quality.findByPointCodes(any())).thenReturn(List.of(new PointQualitySnapshot("P-1","GT-01",QualityStatus.GOOD,50.0,old,old,old,"ok",0,1L)));
        var service=new DataQualityApplicationService(points,quality,assets,telemetry,Clock.fixed(now,ZoneOffset.UTC));
        assertEquals(0,service.summary("GT-01").goodPoints());
        assertFalse(service.summary("GT-01").healthAssessmentAllowed());
        assertEquals(old,service.allPoints("GT-01").get(0).sourceTimestamp());
        assertEquals("GOOD",service.historicalPoints("GT-01").get(0).qualityStatus());
        verify(quality,never()).save(any());verifyNoInteractions(telemetry);
    }
}
