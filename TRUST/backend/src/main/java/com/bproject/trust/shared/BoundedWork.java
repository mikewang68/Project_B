package com.bproject.trust.shared;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** No waiting work queue: the database remains the durable backlog. */
public final class BoundedWork implements AutoCloseable {
  private final ThreadPoolExecutor executor;
  private final AtomicBoolean batch = new AtomicBoolean();

  public BoundedWork(String name, int concurrency) {
    var sequence = new AtomicInteger();
    executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS,
        new SynchronousQueue<>(), task -> {
          var thread = new Thread(task, name + "-" + sequence.incrementAndGet());
          thread.setDaemon(false);
          return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
  }

  public boolean execute(Runnable task) {
    try {
      executor.execute(task);
      return true;
    } catch (RejectedExecutionException fullOrClosed) {
      return false;
    }
  }

  public void batch(Runnable task) {
    if (!batch.compareAndSet(false, true)) return;
    if (!execute(() -> {
      try { task.run(); } finally { batch.set(false); }
    })) batch.set(false);
  }

  public boolean closing() {
    return executor.isShutdown() || Thread.currentThread().isInterrupted();
  }

  @Override
  public void close() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(25, TimeUnit.SECONDS)) executor.shutdownNow();
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
