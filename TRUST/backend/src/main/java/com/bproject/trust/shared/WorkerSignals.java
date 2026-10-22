package com.bproject.trust.shared;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Bounded labels from code only. Never logs credentials, payloads or raw exception messages. */
public final class WorkerSignals {
  private static final Map<String, LongAdder> failures = new ConcurrentHashMap<>();
  private static final Map<String, AtomicLong> lastLog = new ConcurrentHashMap<>();

  private WorkerSignals() {}

  public static void failure(String worker, String phase, Exception error) {
    String key = worker + "." + phase;
    failures.computeIfAbsent(key, k -> new LongAdder()).increment();
    io.micrometer.core.instrument.Metrics.counter(
            "trust.worker.failures", "worker", worker, "phase", phase)
        .increment();
    var last = lastLog.computeIfAbsent(key, k -> new AtomicLong());
    long now = System.nanoTime(), before = last.get();
    if (now - before > java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
        && last.compareAndSet(before, now))
      org.slf4j.LoggerFactory.getLogger(WorkerSignals.class)
          .warn(
              "TRUST worker={} phase={} failure={} count={}",
              worker,
              phase,
              error.getClass().getSimpleName(),
              failures.get(key).sum());
  }

  public static Map<String, Long> counts() {
    var result = new java.util.TreeMap<String, Long>();
    failures.forEach((k, v) -> result.put(k, v.sum()));
    return result;
  }
}
