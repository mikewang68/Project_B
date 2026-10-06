package com.bproject.safety.support.number;

import com.bproject.safety.module.alert.service.AlertNumberGenerator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 基于 openGauss 表 {@code safety.sys_business_number} 的告警业务单号生成器。
 * 格式与业务基线完全对齐：{@code ALM-yyyyMMdd-NNN}。
 *
 * <ul>
 *   <li>时区：严格依据东八区自然日 {@code Asia/Shanghai} 进行日切。</li>
 *   <li>位宽：最少 3 位前缀补 0（%03d）；大于 999 时自然扩位（1000、1001），绝不截断。</li>
 *   <li>并发：托底于 openGauss 数据库行级排他锁，多 JVM / 多实例安全。</li>
 *   <li>性能：单条 SQL O(1) 原子获取，绝不执行 {@code AlertRepository.findAll()} 或全表扫描。</li>
 * </ul>
 */
@Component
@Profile("server")
public class JdbcAlertNumberGenerator implements AlertNumberGenerator {

    public static final String NUMBER_TYPE_ALERT = "ALERT";
    public static final String PREFIX = "ALM-";
    public static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcBusinessNumberStore numberStore;
    private final Clock clock;

    public JdbcAlertNumberGenerator(JdbcBusinessNumberStore numberStore, Clock clock) {
        this.numberStore = Objects.requireNonNull(numberStore, "numberStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public String nextAlertNumber() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE_SHANGHAI));
        String dateStr = today.format(DAY_FORMATTER);
        long sequence = numberStore.nextSequence(NUMBER_TYPE_ALERT, dateStr);
        return String.format("%s%s-%03d", PREFIX, dateStr, sequence);
    }
}
