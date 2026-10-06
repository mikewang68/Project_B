package com.bproject.safety.support.number;

import com.bproject.safety.module.ai.service.AiEventNumberGenerator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 基于 openGauss 表 {@code safety.sys_business_number} 的 AI 事件业务单号生成器。
 * 格式与业务基线完全对齐：{@code AI-E-yyyyMMdd-NNN}。
 *
 * <ul>
 *   <li>时区：严格依据东八区自然日 {@code Asia/Shanghai} 进行日切。</li>
 *   <li>位宽：最少 3 位前缀补 0（%03d）；大于 999 时自然扩位，绝不截断。</li>
 *   <li>并发：托底于 openGauss 数据库行级排他锁，多 JVM / 多实例安全。</li>
 *   <li>性能：单条 SQL O(1) 原子获取，绝不执行全表扫描。</li>
 * </ul>
 */
@Component
@Profile("server")
public class JdbcAiEventNumberGenerator implements AiEventNumberGenerator {

    public static final String NUMBER_TYPE_AI_EVENT = "AI_EVENT";
    public static final String PREFIX = "AI-E-";
    public static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcBusinessNumberStore numberStore;
    private final Clock clock;

    public JdbcAiEventNumberGenerator(JdbcBusinessNumberStore numberStore, Clock clock) {
        this.numberStore = Objects.requireNonNull(numberStore, "numberStore must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public String nextAiEventNumber() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE_SHANGHAI));
        String dateStr = today.format(DAY_FORMATTER);
        long sequence = numberStore.nextSequence(NUMBER_TYPE_AI_EVENT, dateStr);
        return String.format("%s%s-%03d", PREFIX, dateStr, sequence);
    }
}
