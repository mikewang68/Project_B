package com.bproject.trust.archiving;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.trust.config.WorkerScheduling;
import com.bproject.trust.shared.BoundedWork;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

class WorkerSchedulingTest {
  @Test void noQueuedWorkAndOnlyOneBatchWhileDependencyBlocks() throws Exception {
    var hold = new CountDownLatch(1);
    var entered = new CountDownLatch(4);
    try (var work = new BoundedWork("test-bounded", 4)) {
      for (int i=0; i<4; i++) assertTrue(work.execute(() -> {
        entered.countDown();
        try { hold.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertFalse(work.execute(() -> fail("must remain in durable queue")));
      hold.countDown();
    } finally { hold.countDown(); }
    var calls = new AtomicInteger();
    var release = new CountDownLatch(1);
    try (var work = new BoundedWork("test-batch", 1)) {
      for (int i=0; i<100; i++) work.batch(() -> {
        calls.incrementAndGet();
        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      });
      release.countDown();
    } finally { release.countDown(); }
    assertEquals(1, calls.get());
  }

  @Test void blockedTriggerCannotStarveActualArchiveLeaseRenewal() throws Exception {
    var config = new WorkerScheduling();
    var triggers = config.taskScheduler(); var leases = config.leaseScheduler();
    triggers.initialize(); leases.initialize();
    var blocked = new CountDownLatch(2); var release = new CountDownLatch(1);
    var renewed = new CountDownLatch(3);
    var db = mock(JdbcTemplate.class);
    when(db.update(anyString(), any(Object[].class))).thenAnswer(call -> { renewed.countDown(); return 1; });
    var worker = new ArchiveWorker(db,null,null,null,null,null,null,null,null);
    worker.active.put("event", "lease");
    try {
      for (int i=0;i<2;i++) triggers.execute(() -> {
        blocked.countDown();
        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      });
      assertTrue(blocked.await(2,TimeUnit.SECONDS));
      assertEquals("leaseScheduler", ArchiveWorker.class.getMethod("renew").getAnnotation(Scheduled.class).scheduler());
      leases.scheduleAtFixedRate(worker::renew, Duration.ofMillis(50));
      assertTrue(renewed.await(2,TimeUnit.SECONDS), "actual renewal must progress while all triggers are blocked");
    } finally { release.countDown(); leases.destroy(); triggers.destroy(); worker.close(); }
  }
}
