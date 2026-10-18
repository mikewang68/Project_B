package com.bproject.trust.logevidence;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LogDeliveryHttpTest {
  @Test
  void receiptBodyHasTotalDeadlineEvenAfterHeadersAndFirstByte() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var release = new CountDownLatch(1);
    var begun = new CountDownLatch(1);
    server.createContext(
        "/",
        exchange -> {
          try {
            exchange.sendResponseHeaders(202, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            begun.countDown();
            release.await(15, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    var calls = Executors.newSingleThreadExecutor();
    try {
      var result = calls.submit(() -> new LogDeliveryWorker(null, null).receive(request(server)));
      assertTrue(begun.await(3, TimeUnit.SECONDS));
      var failure = assertThrows(ExecutionException.class, () -> result.get(7, TimeUnit.SECONDS));
      assertInstanceOf(TimeoutException.class, failure.getCause());
    } finally {
      release.countDown();
      server.stop(0);
      calls.shutdownNow();
    }
  }

  @Test
  void oversizedReceiptIsRejectedBeforeUnboundedBuffering() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            byte[] bytes = new byte[65537];
            exchange.sendResponseHeaders(202, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      assertThrows(
          RuntimeException.class, () -> new LogDeliveryWorker(null, null).receive(request(server)));
    } finally {
      server.stop(0);
    }
  }

  private static HttpRequest request(HttpServer server) {
    return HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
  }
}
