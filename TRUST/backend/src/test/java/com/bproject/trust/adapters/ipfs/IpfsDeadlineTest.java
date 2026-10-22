package com.bproject.trust.adapters.ipfs;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class IpfsDeadlineTest {
  HttpServer server;
  ExecutorService workers;
  IpfsClient client;

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = Executors.newFixedThreadPool(4);
    server.setExecutor(workers);
    server.start();
    client =
        new IpfsClient(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            10000000,
            null,
            Duration.ofSeconds(1),
            Duration.ofMillis(200),
            Duration.ofMillis(600),
            1024,
            33554432);
  }

  @AfterEach
  void close() {
    server.stop(0);
    workers.shutdownNow();
  }

  @Test
  void fullBodyDeadlineAndRecoveryAfterRepeatedTimeouts() throws Exception {
    var stalls = new java.util.concurrent.atomic.AtomicBoolean(true);
    server.createContext(
        "/api/v0/repo/stat",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if (stalls.get()) {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            try {
              Thread.sleep(700);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          } else {
            byte[] data = "{\"RepoSize\":0}".getBytes();
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
          }
          exchange.close();
        });
    for (int i = 0; i < 3; i++)
      assertTimeoutPreemptively(
          Duration.ofSeconds(1),
          () -> assertThrows(Exception.class, () -> client.add(new byte[] {1})));
    stalls.set(false);
    String cid = "b".repeat(30);
    server.createContext(
        "/api/v0/add",
        e -> {
          e.getRequestBody().readAllBytes();
          byte[] data = ("{\"Hash\":\"" + cid + "\"}").getBytes();
          e.sendResponseHeaders(200, data.length);
          e.getResponseBody().write(data);
          e.close();
        });
    server.createContext(
        "/api/v0/cat",
        e -> {
          e.sendResponseHeaders(200, 1);
          e.getResponseBody().write(1);
          e.close();
        });
    assertEquals(cid, client.add(new byte[] {1}));
  }

  @Test
  void oversizedMetadataCancelsBeforeBufferingEntireResponse() {
    server.createContext(
        "/api/v0/repo/stat",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 0);
          try {
            exchange.getResponseBody().write(new byte[4096]);
          } finally {
            exchange.close();
          }
        });
    assertTimeoutPreemptively(
        Duration.ofSeconds(2), () -> assertThrows(Exception.class, client::stat));
  }

  @Test
  void stalledHeadersAreAlsoBounded() {
    server.createContext(
        "/api/v0/repo/stat",
        exchange -> {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    assertTimeoutPreemptively(
        Duration.ofSeconds(1), () -> assertThrows(Exception.class, client::stat));
  }

  @Test
  void cancellationReleasesAddLockAndDisconnectDoesNotHang() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    server.createContext(
        "/api/v0/repo/stat",
        e -> {
          e.getRequestBody().readAllBytes();
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            e.close();
          }
        });
    var callers = Executors.newSingleThreadExecutor();
    try {
      var call =
          callers.submit(
              () -> {
                try {
                  client.add(new byte[] {1});
                } catch (Exception expected) {
                }
              });
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      call.cancel(true);
      release.countDown();
      callers.shutdown();
      assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
      server.removeContext("/api/v0/repo/stat");
      server.createContext(
          "/api/v0/repo/stat",
          e -> {
            e.getRequestBody().readAllBytes();
            e.sendResponseHeaders(200, 1000);
            e.getResponseBody().write(1);
            e.close();
          });
      assertTimeoutPreemptively(
          Duration.ofSeconds(1),
          () -> assertThrows(Exception.class, () -> client.add(new byte[] {1})));
    } finally {
      release.countDown();
      callers.shutdownNow();
    }
  }

  @Test
  void contentResponsesHaveHardLimit() {
    server.createContext(
        "/api/v0/cat",
        e -> {
          e.sendResponseHeaders(200, 0);
          try {
            byte[] chunk = new byte[65536];
            for (int i = 0; i < 513; i++) e.getResponseBody().write(chunk);
          } catch (java.io.IOException cancelled) {
          } finally {
            e.close();
          }
        });
    var bounded =
        new IpfsClient(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            100000000,
            null,
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(6),
            65536,
            33554432);
    assertTimeoutPreemptively(
        Duration.ofSeconds(6),
        () -> assertThrows(Exception.class, () -> bounded.cat("b".repeat(30))));
  }
}
