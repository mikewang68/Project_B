package com.bproject.safety.module.personnel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.bproject.safety.module.personnel.service.UwbStationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class UwbStationFreshnessTest {
    @Test void expiredStationsAreOfflineAndNewReportsRecoverThem() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1_000L);
        when(clock.withZone(any(ZoneId.class))).thenReturn(Clock.fixed(Instant.EPOCH, ZoneId.of("UTC")));
        var service = new UwbStationService(clock);
        service.apply(List.of(new UwbStationService.StationReport("A", true, true, 30, true, 1)));
        assertThat(service.summary().online()).isEqualTo(1);
        when(clock.millis()).thenReturn(11_001L);
        assertThat(service.summary().online()).isZero();
        assertThat(service.summary().minSnrDb()).isNull();
        assertThat(service.summary().allClockSynced()).isFalse();
        service.apply(List.of(new UwbStationService.StationReport("A", true, true, 40, true, 2)));
        assertThat(service.summary().total()).isEqualTo(1);
        assertThat(service.summary().online()).isEqualTo(1);
        assertThat(service.summary().minSnrDb()).isEqualTo(40);
    }
}
