package com.bproject.trust.shared;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseMutexTest {
  @Test
  void blockedConnectionAcquisitionHasDeadlineAndLateConnectionIsClosed() throws Exception {
    var ds = mock(DataSource.class);
    var connection = mock(Connection.class);
    var release = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    when(ds.getConnection())
        .thenAnswer(
            call -> {
              boolean done = false;
              while (!done)
                try {
                  release.await();
                  done = true;
                } catch (InterruptedException ignored) {
                }
              return connection;
            });
    doAnswer(
            call -> {
              closed.countDown();
              return null;
            })
        .when(connection)
        .close();
    try {
      assertTimeoutPreemptively(
          Duration.ofSeconds(1),
          () ->
              assertThrows(
                  TimeoutException.class,
                  () ->
                      DatabaseMutex.acquire(
                          new JdbcTemplate(ds),
                          "test",
                          System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100))));
    } finally {
      release.countDown();
    }
    assertTrue(closed.await(3, TimeUnit.SECONDS));
    verify(connection).close();
    verify(connection, never()).prepareStatement(anyString());
  }
}
