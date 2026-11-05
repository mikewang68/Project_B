import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounded aggregate diagnostics. No SQL, payloads, URLs, identities or credentials. */
final class IngressTiming implements AutoCloseable {
    static final class Counter {
        final LongAdder count = new LongAdder(), nanos = new LongAdder();
        final AtomicLong max = new AtomicLong();
        void add(long elapsed) { count.increment(); nanos.add(elapsed); max.accumulateAndGet(elapsed, Math::max); }
    }
    final boolean enabled;
    final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final OutputStream output;
    private final long limit;
    private long written;
    private boolean stopped;
    private final ScheduledExecutorService flusher;
    IngressTiming() { enabled = false; output = null; limit = 0; flusher = null; }
    IngressTiming(Path file, long limit) throws IOException {
        if (limit < 4096 || limit > 64L * 1024 * 1024) throw new IllegalArgumentException("INVALID_TIMING_CAPACITY");
        this.limit = limit;
        output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        enabled = true;
        flusher = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "ingress-timing"); t.setDaemon(true); return t; });
        flusher.scheduleWithFixedDelay(this::flush, 30, 30, TimeUnit.SECONDS);
    }
    static IngressTiming fromEnvironment() {
        String mode = DeviceIngress.env("DEVICE_TIMING", "off");
        if (mode.equals("off")) return new IngressTiming();
        if (!mode.equals("on")) throw new IllegalArgumentException("INVALID_TIMING_MODE");
        try { return new IngressTiming(Path.of(DeviceIngress.required("DEVICE_TIMING_FILE")), Long.parseLong(DeviceIngress.required("DEVICE_TIMING_MAX_BYTES"))); }
        catch (IOException ex) { throw new IllegalArgumentException("TIMING_FILE_UNAVAILABLE"); }
    }
    long begin() { return enabled ? System.nanoTime() : 0; }
    void end(String category, long start) {
        if (!enabled) return;
        // All callers supply fixed code categories; never label with request values.
        counters.computeIfAbsent(category, ignored -> new Counter()).add(Math.max(0, System.nanoTime() - start));
    }
    Map<String, Object> snapshot() {
        Map<String, Object> result = new TreeMap<>();
        counters.forEach((key, c) -> result.put(key, Map.of("count", c.count.sum(), "total_ns", c.nanos.sum(), "max_ns", c.max.get())));
        return result;
    }
    synchronized void flush() {
        if (!enabled || stopped) return;
        try {
            byte[] bytes = (DeviceIngress.JSON.writeValueAsString(Map.of("kind", "ingress_timing_v1", "at_ms", System.currentTimeMillis(), "cumulative", snapshot())) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (written + bytes.length > limit) { stopped = true; return; }
            output.write(bytes); output.flush(); written += bytes.length;
        } catch (Exception ex) { stopped = true; System.err.println("INGRESS_TIMING_DISABLED_IO_FAILURE"); }
    }
    public synchronized void close() {
        if (!enabled) return;
        flusher.shutdownNow(); flush(); stopped = true;
        try { output.close(); } catch (IOException ignored) { }
    }
}
