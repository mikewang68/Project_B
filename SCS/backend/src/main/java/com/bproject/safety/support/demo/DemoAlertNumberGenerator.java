package com.bproject.safety.support.demo;

import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.service.AlertNumberGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Demo 告警编号生成器：{@code ALM-yyyyMMdd-NNN}，NNN 为当日已有同前缀告警数 + 1。
 *
 * <p>单 JVM / 单实例口径，<b>不保证多实例唯一</b>；openGauss 阶段替换为数据库日序列或号段实现。
 * 受 Spring Bean 装配保证全局只有一个实例；编号格式与历史 Demo 完全一致。</p>
 */
@Component
public class DemoAlertNumberGenerator implements AlertNumberGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AlertRepository repository;
    private final Clock clock;

    public DemoAlertNumberGenerator(AlertRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public synchronized String nextAlertNumber() {
        String prefix = "ALM-" + OffsetDateTime.now(clock).format(DAY) + "-";
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
        long next = maxSeq + 1;
        String candidate = String.format("%s%03d", prefix, next);
        while (repository.findById(candidate).isPresent()) {
            next++;
            candidate = String.format("%s%03d", prefix, next);
        }
        return candidate;
    }
}
