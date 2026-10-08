package com.bproject.ehm.health.application;
import com.bproject.ehm.asset.application.*;
import com.bproject.ehm.health.ports.*;
import com.bproject.ehm.maintenance.application.*;
import com.bproject.ehm.monitoring.application.*;
import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class HealthAssessmentApplicationServiceTest {
    @Test void replayUsesActualInputTimesAndNeverTreatsAssetRulAsPrediction() {
        var repository=mock(HealthAssessmentRepository.class);var assets=mock(AssetQueryFacade.class);
        var quality=mock(DataQualityApplicationService.class);var work=mock(WorkOrderApplicationService.class);
        var asset=mock(DeviceView.class);when(asset.name()).thenReturn("测试起重机");when(asset.rulDays()).thenReturn(24);
        when(assets.get("GT-01")).thenReturn(asset);
        Instant old=Instant.parse("2026-09-14T07:20:29Z"),now=Instant.parse("2026-10-07T07:00:00Z");
        var point=new DataQualityPointView("P-1","GT-01",null,"温度","温度","℃","人工",null,10,0.0,100.0,true,"GOOD","正常",70.0,old,old,old,"ok",0);
        when(quality.historicalPoints("GT-01")).thenReturn(List.of(point));
        when(quality.summarize(eq("GT-01"),any())).thenReturn(new DataQualitySummary("GT-01",1,1,1,0,0,0,0,0,100,true,"ok",old));
        when(repository.save(any())).thenAnswer(call->call.getArgument(0));
        var service=new HealthAssessmentApplicationService(repository,assets,quality,work,Clock.fixed(now,ZoneOffset.UTC));
        var result=service.replay("GT-01");
        assertTrue(result.method().contains("历史回放"));assertEquals(old,result.inputWindowStart());assertEquals(old,result.inputWindowEnd());
        assertEquals(now,result.generatedAt());assertNull(result.prediction().expectedDays());assertEquals("UNAVAILABLE",result.prediction().status());
        when(quality.allPoints("GT-01")).thenReturn(List.of());
        when(quality.summarize(eq("GT-01"),any())).thenReturn(new DataQualitySummary("GT-01",1,1,0,0,1,0,0,0,0,false,"过期",now));
        assertThrows(DomainConflictException.class,()->service.run("GT-01"));
    }
}
