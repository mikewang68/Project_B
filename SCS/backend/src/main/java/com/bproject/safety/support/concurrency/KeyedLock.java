package com.bproject.safety.support.concurrency;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 单 JVM 细粒度分段键值锁（Intern-Free Keyed Lock）。
 * <p>针对相同 key（如告警去重键 dedupKey）做精确串行化互斥保护，不同 key 之间完全并行。
 * 内部基于引用计数管理锁实例，在无等待线程时自动从 Map 移除，杜绝内存泄露。</p>
 */
@Component
public class KeyedLock {

    private final ConcurrentHashMap<String, LockRef> locks = new ConcurrentHashMap<>();

    private static class LockRef {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger refCount = new AtomicInteger(1);
    }

    public void lock(String key) {
        if (key == null) {
            return;
        }
        LockRef ref = locks.compute(key, (k, existing) -> {
            if (existing == null) {
                return new LockRef();
            }
            existing.refCount.incrementAndGet();
            return existing;
        });
        ref.lock.lock();
    }

    public void unlock(String key) {
        if (key == null) {
            return;
        }
        LockRef ref = locks.get(key);
        if (ref != null) {
            ref.lock.unlock();
            locks.computeIfPresent(key, (k, existing) -> {
                if (existing.refCount.decrementAndGet() <= 0) {
                    return null;
                }
                return existing;
            });
        }
    }

    public <T> T execute(String key, Supplier<T> action) {
        if (key == null || key.isBlank()) {
            return action.get();
        }
        lock(key);
        try {
            return action.get();
        } finally {
            unlock(key);
        }
    }
}
