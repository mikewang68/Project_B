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
    @Test void ordinaryPointsAlsoRequireRealThresholds(){
        var repository=mock(HealthAssessmentRepository.class);var assets=mock(AssetQueryFacade.class);var quality=mock(DataQualityApplicationService.class);var work=mock(WorkOrderApplicationService.class);var now=Instant.now();
        when(assets.get("EQ-1")).thenReturn(mock(DeviceView.class));
        when(quality.allPoints("EQ-1")).thenReturn(List.of(new DataQualityPointView("P1","EQ-1",null,"电流","电流","A","TEST",null,5,null,null,true,"GOOD","正常",3d,now,now,now,"ok",0)));
        when(quality.summarize(eq("EQ-1"),any())).thenReturn(new DataQualitySummary("EQ-1",1,1,1,0,0,0,0,0,100,true,"ok",now));
        assertThrows(DomainConflictException.class,()->new HealthAssessmentApplicationService(repository,assets,quality,work).run("EQ-1"));verifyNoInteractions(repository);
    }
    @Test void publicRawPointWithoutApprovedThresholdCannotProduceHealthScore() {
        var repository=mock(HealthAssessmentRepository.class);var assets=mock(AssetQueryFacade.class);
        var quality=mock(DataQualityApplicationService.class);var work=mock(WorkOrderApplicationService.class);
        when(assets.get("MQ-SIM-TEST")).thenReturn(mock(DeviceView.class));Instant now=Instant.now();
        var point=new DataQualityPointView("MQ-SIM-TEST-EHM-PT-1","MQ-SIM-TEST",null,"电流","电流","A","PUBLIC_COMMITTED_SIM",null,5,null,null,true,"GOOD","正常",12.0,now,now,now,"ok",0);
        when(quality.allPoints("MQ-SIM-TEST")).thenReturn(List.of(point));
        when(quality.summarize(eq("MQ-SIM-TEST"),any())).thenReturn(new DataQualitySummary("MQ-SIM-TEST",1,1,1,0,0,0,0,0,100,true,"ok",now));
        var service=new HealthAssessmentApplicationService(repository,assets,quality,work);
        assertThrows(DomainConflictException.class,()->service.run("MQ-SIM-TEST"));verifyNoInteractions(repository);
    }
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
