package com.bproject.safety.support.demo;

import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.service.AlertNumberGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Demo 告警编号生成器：{@code ALM-yyyyMMdd-NNN}，NNN 为当日原子递增序列。
 *
 * <p>单 JVM / 单实例口径，通过内存原子计数器结合既有存储序列，避免并发建单时单号冲突；
 * openGauss 阶段替换为数据库日序列或号段实现（{@code JdbcAlertNumberGenerator}）。
 * 受 Spring Bean 装配保证全局只有一个实例；编号格式与历史 Demo 完全一致。</p>
 */
@Component
@Profile("!server")
public class DemoAlertNumberGenerator implements AlertNumberGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AlertRepository repository;
    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicLong> dayCounters = new ConcurrentHashMap<>();

    public DemoAlertNumberGenerator(AlertRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public synchronized String nextAlertNumber() {
        String dateStr = OffsetDateTime.now(clock).format(DAY);
        String prefix = "ALM-" + dateStr + "-";

        AtomicLong counter = dayCounters.computeIfAbsent(dateStr, d -> new AtomicLong(scanMaxSequence(prefix)));

        // 若外部有新数据直接落库且编号大于当前计数器，保持向前对齐
        long currentDbMax = scanMaxSequence(prefix);
        counter.accumulateAndGet(currentDbMax, Math::max);

        long next = counter.incrementAndGet();
        String candidate = String.format("%s%03d", prefix, next);
        while (repository.findById(candidate).isPresent()) {
            next = counter.incrementAndGet();
            candidate = String.format("%s%03d", prefix, next);
        }
        return candidate;
    }

    private long scanMaxSequence(String prefix) {
        long maxSeq = 0;
        for (var a : repository.findAll()) {
            if (a.id != null && a.id.startsWith(prefix)) {
                String suffix = a.id.substring(prefix.length());
                try {
                    long seq = Long.parseLong(suffix);
                    if (seq > maxSeq) {
                        maxSeq = seq;
                    }
                } catch (NumberFormatException ignored) {
                    // 忽略不符合规范的后缀
                }
            }
        }
        return maxSeq;
    }
}

