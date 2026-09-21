package com.bproject.safety.support.demo;

import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.service.AiEventNumberGenerator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Demo AI 事件编号生成器：{@code AI-E-yyyyMMdd-NNN}（上海时区自然日），
 * NNN 为当日已有同前缀事件数 + 1。单实例口径，<b>不保证多实例唯一</b>。
 */
@Component
public class DemoAiEventNumberGenerator implements AiEventNumberGenerator {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AiEventRepository repository;
    private final Clock clock;

    public DemoAiEventNumberGenerator(AiEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public synchronized String nextAiEventNumber() {
        String prefix = "AI-E-" + OffsetDateTime.now(clock.withZone(ZONE)).format(DAY) + "-";
        long maxSeq = 0;
        for (var e : repository.findAll()) {
            if (e.id != null && e.id.startsWith(prefix)) {
                String suffix = e.id.substring(prefix.length());
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
