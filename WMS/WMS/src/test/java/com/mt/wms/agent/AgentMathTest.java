package com.mt.wms.agent;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentMathTest {
    @Test void bundleWeightUsesWeightPerBundleAndUnknownIsNotZero() {
        assertEquals(new BigDecimal("2400"),AgentMath.itemWeight(new BigDecimal("8"),new BigDecimal("300"),"捆"));
        assertNull(AgentMath.itemWeight(new BigDecimal("8"),BigDecimal.ZERO,"捆"));
        assertEquals(BigDecimal.ZERO,AgentMath.itemWeight(BigDecimal.ZERO,BigDecimal.ZERO,"捆"));
    }
    @Test void massUnitsDoNotMultiplyByUnitWeight() {
        assertEquals(new BigDecimal("2000"),AgentMath.itemWeight(new BigDecimal("2"),BigDecimal.ZERO,"吨"));
        assertEquals(new BigDecimal("25"),AgentMath.itemWeight(new BigDecimal("25"),new BigDecimal("100"),"kg"));
    }
    @Test void missingDataOrZeroCapacityCannotProduceUtilization() {
        assertNull(AgentMath.percent(BigDecimal.TEN,BigDecimal.ZERO,true));
        assertNull(AgentMath.percent(BigDecimal.TEN,new BigDecimal("20"),false));
        assertEquals(new BigDecimal("120.00"),AgentMath.percent(new BigDecimal("2400"),new BigDecimal("2000"),true));
    }
    @Test void dailyScheduleUsesShanghaiAndDoesNotRepeatCurrentMinute() {
        Clock clock=Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"),ZoneOffset.UTC);
        assertEquals(OffsetDateTime.parse("2026-10-08T08:00:00+08:00"),AgentMath.nextRun("DAILY",60,"08:00",clock,ZoneId.of("Asia/Shanghai")));
        assertEquals(OffsetDateTime.parse("2026-10-07T09:00:00+08:00"),AgentMath.nextRun("INTERVAL",60,"08:00",clock,ZoneId.of("Asia/Shanghai")));
        assertThrows(IllegalArgumentException.class,()->AgentMath.nextRun("SCRIPT",60,"08:00",clock,ZoneId.of("Asia/Shanghai")));
    }
    @Test void keywordsExpandSteelAliasesWithoutSqlWildcards() {
        List<String> words=AgentMath.keywords(List.of("钢材","%螺纹_"));
        assertTrue(words.contains("钢"));assertTrue(words.contains("螺纹"));assertFalse(words.contains("%螺纹_"));
    }
    @Test void notificationAddressesRejectHeaderInjectionAndOversizedLists() {
        assertEquals("a@example.com,b@example.com",AgentPatrolService.emails("a@example.com; b@example.com"));
        assertThrows(IllegalArgumentException.class,()->AgentPatrolService.emails("a@example.com\r\nBcc:x@example.com"));
        assertThrows(IllegalArgumentException.class,()->AgentPatrolService.emails("bad-address"));
        assertEquals("-",AgentPatrolService.emails(""));
    }
}
