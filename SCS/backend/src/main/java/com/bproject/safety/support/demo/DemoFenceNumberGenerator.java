package com.bproject.safety.support.demo;

import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.fence.service.FenceNumberGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Demo 围栏编号生成器：{@code FENCE-NNN}，NNN 为现有围栏最大序号 + 1（编号已存在则顺延，
 * 防范间隙碰撞与静默覆盖）。单实例口径，<b>不保证多实例唯一</b>。
 */
@Component
public class DemoFenceNumberGenerator implements FenceNumberGenerator {

    private static final Logger log = LoggerFactory.getLogger(DemoFenceNumberGenerator.class);
    private static final String PREFIX = "FENCE-";

    private final FenceRepository repository;

    public DemoFenceNumberGenerator(FenceRepository repository) {
        this.repository = repository;
    }

    @Override
    public synchronized String nextFenceNumber() {
        long maxSeq = 0;
        for (var f : repository.findAll()) {
            if (f.id != null && f.id.startsWith(PREFIX)) {
                String suffix = f.id.substring(PREFIX.length());
                try {
                    long seq = Long.parseLong(suffix);
                    if (seq > maxSeq) {
                        maxSeq = seq;
                    }
                } catch (NumberFormatException ex) {
                    log.warn("ignored malformed fence id: {}", f.id);
                }
            }
        }
        long next = maxSeq + 1;
        String candidate = String.format("%s%03d", PREFIX, next);
        while (repository.findById(candidate).isPresent()) {
            next++;
            candidate = String.format("%s%03d", PREFIX, next);
        }
        return candidate;
    }
}
