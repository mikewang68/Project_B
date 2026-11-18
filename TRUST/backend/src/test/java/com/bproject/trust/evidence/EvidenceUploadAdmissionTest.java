package com.bproject.trust.evidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;

class EvidenceUploadAdmissionTest {
  @TempDir Path root;

  @Test
  void concurrentLargeReadsAreSerializedAndReadFailuresReleaseAdmission() throws Exception {
    var service = new EvidenceService(null, null, root.toString());
    var firstRead = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var started = new CountDownLatch(16);
    var reads = new AtomicInteger();
    var file = mock(MultipartFile.class);
    when(file.getSize()).thenReturn(20L * 1024 * 1024);
    when(file.getBytes()).thenAnswer(invocation -> {
      int order = reads.incrementAndGet();
      byte[] bytes = new byte[20 * 1024 * 1024];
      bytes[bytes.length - 1] = 1;
      if (order == 1) {
        firstRead.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS));
      }
      // Fail before persistence: this test isolates admission from database and IPFS.
      throw new IOException("simulated read failure " + bytes[bytes.length - 1]);
    });
    var pool = Executors.newFixedThreadPool(16);
    var jobs = new ArrayList<Future<?>>();
    try {
      for (int i = 0; i < 16; i++) jobs.add(pool.submit(() -> {
        started.countDown();
        assertThrows(IOException.class, () -> service.upload(file, "org", "actor"));
      }));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      assertTrue(firstRead.await(5, TimeUnit.SECONDS));
      // All contenders have entered; allow them to reach admission while read 1 is held.
      Thread.sleep(250);
      assertEquals(1, reads.get(), "waiting uploads must not allocate whole-file buffers");
      release.countDown();
      for (var job : jobs) job.get(10, TimeUnit.SECONDS);
      assertEquals(16, reads.get());
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void interruptedWaitDoesNotReadOrReleaseAnotherUploadsPermit() throws Exception {
    var service = new EvidenceService(null, null, root.toString());
    var reading = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var ownerFile = mock(MultipartFile.class);
    when(ownerFile.getSize()).thenReturn(10L);
    when(ownerFile.getBytes()).thenAnswer(invocation -> {
      reading.countDown();
      assertTrue(release.await(10, TimeUnit.SECONDS));
      throw new IOException("read failed");
    });
    var waitingFile = mock(MultipartFile.class);
    when(waitingFile.getSize()).thenReturn(10L);
    var pool = Executors.newSingleThreadExecutor();
    try {
      var owner = pool.submit(() ->
          assertThrows(IOException.class, () -> service.upload(ownerFile, "org", "actor")));
      assertTrue(reading.await(5, TimeUnit.SECONDS));
      Thread.currentThread().interrupt();
      assertThrows(InterruptedException.class, () -> service.upload(waitingFile, "org", "actor"));
      verify(waitingFile, never()).getBytes();
      release.countDown();
      owner.get(5, TimeUnit.SECONDS);
      when(waitingFile.getBytes()).thenThrow(new IOException("next read failed"));
      assertThrows(IOException.class, () -> service.upload(waitingFile, "org", "actor"));
    } finally {
      Thread.interrupted();
      release.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }
  }
}
