package com.bproject.safety.support.number;

import com.bproject.safety.module.alert.service.AlertNumberGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Step 3: 业务单号 Generator 格式、日切、位宽扩容与时区单测")
class JdbcAlertNumberGeneratorTest {

    static class StubBusinessNumberStore extends JdbcBusinessNumberStore {
        private final Map<String, Long> counter = new HashMap<>();

        StubBusinessNumberStore() {
            // Bypass super JDBC dependencies in unit test
            super(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(
                    new org.springframework.jdbc.datasource.DriverManagerDataSource()),
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager());
        }

        @Override
        public long nextSequence(String numberType, String businessDate) {
            String key = numberType + ":" + businessDate;
            long val = counter.getOrDefault(key, 0L) + 1;
            counter.put(key, val);
            return val;
        }

        void setSequence(String numberType, String businessDate, long val) {
            counter.put(numberType + ":" + businessDate, val);
        }
    }

    @Test
    @DisplayName("Alert 单号格式规范：ALM-yyyyMMdd-NNN，自增递增")
    void testAlertNumberFormatAndIncrement() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-23T04:00:00Z"), ZoneOffset.UTC); // 12:00 Shanghai
        StubBusinessNumberStore store = new StubBusinessNumberStore();
        AlertNumberGenerator generator = new JdbcAlertNumberGenerator(store, fixedClock);

        assertThat(generator.nextAlertNumber()).isEqualTo("ALM-20260923-001");
        assertThat(generator.nextAlertNumber()).isEqualTo("ALM-20260923-002");
        assertThat(generator.nextAlertNumber()).isEqualTo("ALM-20260923-003");
    }

    @Test
    @DisplayName("日切规则：不同自然日各自独立从 001 计数")
    void testDailyResetRule() {
        StubBusinessNumberStore store = new StubBusinessNumberStore();

        // Day 1
        Clock day1 = Clock.fixed(Instant.parse("2026-09-23T04:00:00Z"), ZoneOffset.UTC);
        AlertNumberGenerator gen1 = new JdbcAlertNumberGenerator(store, day1);
        assertThat(gen1.nextAlertNumber()).isEqualTo("ALM-20260923-001");
        assertThat(gen1.nextAlertNumber()).isEqualTo("ALM-20260923-002");

        // Day 2
        Clock day2 = Clock.fixed(Instant.parse("2026-09-24T04:00:00Z"), ZoneOffset.UTC);
        AlertNumberGenerator gen2 = new JdbcAlertNumberGenerator(store, day2);
        assertThat(gen2.nextAlertNumber()).isEqualTo("ALM-20260924-001");
    }

    @Test
    @DisplayName("溢出规则：超过 999 自然扩充为 4 位，绝不静默截断")
    void testSequenceOverflowExpansion() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-23T04:00:00Z"), ZoneOffset.UTC);
        StubBusinessNumberStore store = new StubBusinessNumberStore();
        store.setSequence(JdbcAlertNumberGenerator.NUMBER_TYPE_ALERT, "20260923", 999L);

        AlertNumberGenerator generator = new JdbcAlertNumberGenerator(store, fixedClock);
        assertThat(generator.nextAlertNumber()).isEqualTo("ALM-20260923-1000");
        assertThat(generator.nextAlertNumber()).isEqualTo("ALM-20260923-1001");
    }

    @Test
    @DisplayName("时区规则：严格依据 Asia/Shanghai 判定自然日")
    void testTimezoneBoundary() {
        // UTC 2026-09-22 23:30:00 -> Shanghai 2026-09-23 07:30:00
        Clock clockShanghaiMorning = Clock.fixed(Instant.parse("2026-09-22T23:30:00Z"), ZoneOffset.UTC);
        StubBusinessNumberStore store = new StubBusinessNumberStore();
        AlertNumberGenerator generator = new JdbcAlertNumberGenerator(store, clockShanghaiMorning);

        assertThat(generator.nextAlertNumber()).isEqualTo("ALM-20260923-001");
    }
}
