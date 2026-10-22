package com.bproject.trust.shared;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dedicated capacity transaction. Acquisition, including pool/driver waits, shares the deadline.
 */
public final class DatabaseMutex implements AutoCloseable {
  // No unbounded queue or per-request thread: an unresponsive driver cannot exhaust worker threads.
  private static final ExecutorService ACQUIRE =
      new ThreadPoolExecutor(
          0,
          4,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread t = new Thread(r, "storage-lock-acquire");
            t.setDaemon(true);
            return t;
          });
  private final Connection connection;
  private final long deadline;

  private DatabaseMutex(Connection connection, long deadline) {
    this.connection = connection;
    this.deadline = deadline;
  }

  public static DatabaseMutex acquire(JdbcTemplate db, String key, long deadline) throws Exception {
    if (db == null) return new DatabaseMutex(null, deadline); // Explicit standalone adapter in tests.
    var handoff = new Handoff();
    Future<?> job;
    try {
      job =
          ACQUIRE.submit(
              () -> {
                Connection c = null;
                try {
                  c = db.getDataSource().getConnection();
                  synchronized (handoff) {
                    if (handoff.abandoned) return;
                    handoff.connection = c;
                  }
                  timeout(c, deadline);
                  c.setAutoCommit(true);
                  try (var insert =
                      c.prepareStatement("INSERT INTO storage_mutex(resource_key) VALUES(?)")) {
                    insert.setQueryTimeout(seconds(deadline));
                    insert.setString(1, key);
                    try {
                      insert.executeUpdate();
                    } catch (SQLException e) {
                      if (!"23505".equals(e.getSQLState())) throw e;
                    }
                  }
                  timeout(c, deadline);
                  c.setAutoCommit(false);
                  try (var lock =
                      c.prepareStatement(
                          "SELECT resource_key FROM storage_mutex WHERE resource_key=? FOR"
                              + " UPDATE")) {
                    lock.setQueryTimeout(seconds(deadline));
                    lock.setString(1, key);
                    try (var row = lock.executeQuery()) {
                      if (!row.next()) throw new IllegalStateException("存储协调记录缺失");
                    }
                  }
                  timeout(c, deadline);
                  synchronized (handoff) {
                    if (!handoff.abandoned) {
                      handoff.ready = true;
                      return;
                    }
                  }
                } catch (Exception e) {
                  throw new CompletionException(e);
                } finally {
                  boolean cleanup;
                  synchronized (handoff) {
                    cleanup = !handoff.ready;
                    if (cleanup) handoff.connection = null;
                  }
                  if (cleanup && c != null)
                    try {
                      c.close();
                    } catch (SQLException ignored) {
                      WorkerSignals.failure("DatabaseMutex", "close", ignored);
                    }
                }
              });
    } catch (RejectedExecutionException busy) {
      throw new TimeoutException("存储协调连接繁忙");
    }
    try {
      job.get(remaining(deadline), TimeUnit.NANOSECONDS);
      synchronized (handoff) {
        if (!handoff.ready) throw new IllegalStateException("存储锁未取得");
        Connection owned = handoff.connection;
        handoff.connection = null;
        return new DatabaseMutex(owned, deadline);
      }
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof CompletionException && cause.getCause() != null)
        cause = cause.getCause();
      if (cause instanceof Exception e) throw e;
      throw new IllegalStateException(cause);
    } finally {
      Connection orphan = null;
      synchronized (handoff) {
        handoff.abandoned = true;
        if (handoff.ready && handoff.connection != null) {
          orphan = handoff.connection;
          handoff.connection = null;
        }
      }
      if (orphan != null) orphan.close();
      if (!job.isDone()) job.cancel(true);
    }
  }

  private static final class Handoff {
    Connection connection;
    boolean ready, abandoned;
  }

  private static long remaining(long deadline) throws TimeoutException {
    long n = deadline - System.nanoTime();
    if (n <= 0) throw new TimeoutException("存储容量锁等待超时");
    return n;
  }

  private static int seconds(long deadline) throws TimeoutException {
    return (int)
        Math.min(Integer.MAX_VALUE, Math.max(1, (remaining(deadline) + 999999999) / 1000000000));
  }

  private static void timeout(Connection c, long deadline) throws Exception {
    c.setNetworkTimeout(
        Runnable::run,
        (int)
            Math.min(
                Integer.MAX_VALUE,
                Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining(deadline)))));
  }

  @Override
  public void close() throws SQLException {
    if (connection != null)
      try {
        connection.setNetworkTimeout(Runnable::run, (int)Math.min(Integer.MAX_VALUE, Math.max(1,
            TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));
        connection.rollback();
      } finally {
        connection.close();
      }
  }
}
