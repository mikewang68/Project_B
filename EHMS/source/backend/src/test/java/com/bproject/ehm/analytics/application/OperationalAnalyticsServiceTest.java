package com.bproject.ehm.analytics.application;

import com.bproject.ehm.asset.application.*;
import com.bproject.ehm.alarm.application.*;
import com.bproject.ehm.maintenance.application.*;
import com.bproject.ehm.shared.page.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationalAnalyticsServiceTest {
    @Test void aggregatesEveryPageAndDoesNotInventHistoricalHealth() {
        var assets=mock(AssetQueryFacade.class);var alarms=mock(AlarmQueryFacade.class);var orders=mock(WorkOrderQueryFacade.class);
        var asset=mock(DeviceView.class);when(asset.area()).thenReturn("测试区域");when(asset.health()).thenReturn(90);when(asset.type()).thenReturn("门吊");
        when(assets.list(any(),isNull(),isNull(),isNull())).thenAnswer(call -> {PageQuery p=call.getArgument(0);return new PageResult<>(List.of(asset),p.page(),200,201,2);});
        when(alarms.list(any())).thenReturn(PageResult.of(List.of(),new PageQuery(0,200),0));
        when(orders.list(any())).thenReturn(PageResult.of(List.of(),new PageQuery(0,200),0));
        var service=new OperationalAnalyticsService(assets,alarms,orders,Clock.fixed(Instant.parse("2026-10-07T06:00:00Z"),ZoneOffset.UTC));
        var view=service.overview(7);
        assertEquals(2,view.devices());assertEquals(2,view.areaDistribution().get("测试区域"));assertEquals(7,view.dailyEvents().size());
        assertEquals("2026-10-07",view.to());assertEquals(0,view.dailyEvents().get(0).alarmsCreated());
        assertThrows(IllegalArgumentException.class,()->service.overview(0));
        verify(assets,times(2)).list(any(),isNull(),isNull(),isNull());
    }
}
