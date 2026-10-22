package com.bproject.trust.selfcheck;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.bproject.trust.ingress.*;
import com.bproject.trust.shared.json.Json;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimulatorTest {
  @TempDir Path temporary;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .proxy(
              new java.net.ProxySelector() {
                public List<java.net.Proxy> select(URI uri) {
                  return List.of(java.net.Proxy.NO_PROXY);
                }

                public void connectFailed(
                    URI uri, java.net.SocketAddress address, java.io.IOException error) {}
              })
          .build();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "normal",
        "idempotency",
        "correction",
        "permissions",
        "disconnect",
        "unavailable",
        "timeout",
        "action-required",
        "retry-ready",
        "retry-done",
        "retry-running",
        "retry-failed",
        "retry-invalid"
      })
  void eachScenarioRequiresAndAcceptsItsCompleteSequence(String scene) throws Exception {
    try (Session s = start(scene)) {
      String body = event("one", 1, null), id;
      if (scene.equals("disconnect")) {
        assertThrows(java.io.IOException.class, () -> call(s, "POST", "", body, s.token));
        id = parsed(call(s, "GET", "/by-source-event/one", null, s.token)).get("id").toString();
      } else if (scene.equals("unavailable")) {
        assertEquals(503, call(s, "POST", "", body, s.token).statusCode());
        id = parsed(call(s, "GET", "/by-source-event/one", null, s.token)).get("id").toString();
      } else {
        var response = call(s, "POST", "", body, s.token);
        assertEquals(202, response.statusCode());
        assertEquals("ACCEPTED", parsed(response).get("status"));
        assertEquals("READY", parsed(response).get("taskState"));
        assertEquals(
            "local-simulation", response.headers().firstValue("X-TRUST-Selfcheck").orElseThrow());
        id = parsed(response).get("id").toString();
      }
      switch (scene) {
        case "normal" -> {
          assertEquals(
              "PROCESSING",
              parsed(call(s, "GET", "/by-source-event/one", null, s.token)).get("status"));
          var completed = parsed(call(s, "GET", "/" + id, null, s.token));
          assertEquals("COMMITTED", completed.get("status"));
          assertEquals("STORED", completed.get("fileState"));
          assertReceiptMatchesPublishedOpenApi(completed);
        }
        case "disconnect", "unavailable" ->
            assertEquals(
                "COMMITTED", parsed(call(s, "GET", "/" + id, null, s.token)).get("status"));
        case "timeout" -> {
          assertEquals("ACCEPTED", parsed(call(s, "GET", "/" + id, null, s.token)).get("status"));
          assertEquals("ACCEPTED", parsed(call(s, "GET", "/" + id, null, s.token)).get("status"));
        }
        case "idempotency" -> {
          var replay = call(s, "POST", "", body.replace("1}", "1.0}"), s.token);
          assertEquals(200, replay.statusCode());
          assertEquals(true, parsed(replay).get("replayed"));
          assertEquals(
              Json.sha(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              parsed(replay).get("originalSha256"));
          assertEquals(409, call(s, "POST", "", event("one", 2, null), s.token).statusCode());
        }
        case "correction" -> {
          var corrected = call(s, "POST", "", event("two", 2, id), s.token);
          assertEquals(202, corrected.statusCode());
          assertEquals(id, parsed(corrected).get("rootId"));
          assertEquals(2, parsed(corrected).get("version"));
          assertEquals(409, call(s, "POST", "", event("three", 3, id), s.token).statusCode());
          assertEquals(
              400,
              call(s, "POST", "", event("four", 4, id).replace("object-one", "object-two"), s.token)
                  .statusCode());
        }
        case "permissions" -> {
          assertEquals(401, call(s, "GET", "/" + id, null, null).statusCode());
          assertEquals(401, call(s, "GET", "/" + id, null, "wrong").statusCode());
          assertEquals(
              403, call(s, "POST", "", body, s.info.get("readOnlyToken").toString()).statusCode());
          assertEquals(
              403,
              call(s, "POST", "", body.replace("BUSINESS_EVENT", "OTHER_EVENT"), s.token)
                  .statusCode());
          assertEquals(
              404,
              call(s, "GET", "/" + id, null, s.info.get("otherSourceToken").toString())
                  .statusCode());
          assertEquals(
              404,
              call(s, "GET", "/" + id, null, s.info.get("otherOrgToken").toString()).statusCode());
          assertEquals(404, call(s, "GET", "/" + UUID.randomUUID(), null, s.token).statusCode());
        }
        case "action-required" ->
            assertEquals(
                "ACTION_REQUIRED", parsed(call(s, "GET", "/" + id, null, s.token)).get("status"));
        case "retry-running" ->
            assertEquals(409, call(s, "POST", "/" + id + "/retry", "{}", s.token).statusCode());
        case "retry-failed" -> {
          assertEquals(
              "ACTION_REQUIRED", parsed(call(s, "GET", "/" + id, null, s.token)).get("status"));
          var retry = call(s, "POST", "/" + id + "/retry", "{}", s.token);
          assertEquals(202, retry.statusCode());
          assertEquals(true, parsed(retry).get("queued"));
          assertEquals(
              false, parsed(call(s, "POST", "/" + id + "/retry", "{}", s.token)).get("queued"));
        }
        default -> {
          if (scene.equals("retry-invalid"))
            assertEquals(400, call(s, "POST", "/" + id + "/retry", "[]", s.token).statusCode());
          var retry = call(s, "POST", "/" + id + "/retry", "{}", s.token);
          assertEquals(200, retry.statusCode());
          assertEquals(false, parsed(retry).get("queued"));
        }
      }
      assertEquals(0, s.result.get(5, TimeUnit.SECONDS));
      String report = Files.readString(s.report);
      assertFalse(report.contains(s.token));
      assertFalse(report.contains("object-one"));
    }
  }

  @Test
  void noRequestsAndInterruptedRunRemainIncomplete() throws Exception {
    try (Session s = start("normal")) {
      assertEquals(2, s.result.get(5, TimeUnit.SECONDS));
    }
    try (Session s = start("normal")) {
      s.result.cancel(true);
      Thread.sleep(50);
      assertEquals(2, Json.map(Files.readString(s.report)).get("exitCode"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"field", "path", "renumber", "unbounded", "unsolicited-retry"})
  void brokenClientsCannotPass(String fault) throws Exception {
    try (Session s = start("timeout", fault.equals("unbounded") ? 8 : 2)) {
      if (fault.equals("field"))
        call(
            s,
            "POST",
            "",
            event("one", 1, null).replace("businessObjectId", "unknownField"),
            s.token);
      else if (fault.equals("path")) call(s, "GET", "/wrong/path", null, s.token);
      else {
        String id =
            parsed(call(s, "POST", "", event("one", 1, null), s.token)).get("id").toString();
        if (fault.equals("renumber")) call(s, "POST", "", event("two", 1, null), s.token);
        else if (fault.equals("unsolicited-retry"))
          call(s, "POST", "/" + id + "/retry", "{}", s.token);
        else
          for (int i = 0; i < 65; i++) {
            if (s.result.isDone()) break;
            try {
              call(s, "GET", "/" + id, null, s.token);
            } catch (java.io.IOException closed) {
              if (!s.result.isDone()) throw closed;
            }
          }
      }
      assertEquals(1, s.result.get(10, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"duplicate", "oversize", "canonical-oversize", "media"})
  void simulatorAndActualHttpControllerRejectSameBytesAndTransport(String kind) throws Exception {
    String body = event("one", 1, null), media = "application/json";
    if (kind.equals("duplicate")) body = "{\"schemaVersion\":1," + body.substring(1);
    if (kind.equals("oversize")) body = " ".repeat(GenericEventInput.LIMIT + 1);
    if (kind.equals("media")) media = "application/json;charset=UTF-16";
    if (kind.equals("canonical-oversize")) {
      var input = Json.map(body);
      var payload = new LinkedHashMap<String, Object>();
      for (int i = 0; i < 16; i++) payload.put("item" + i, "a".repeat(16340));
      input.put("payload", payload);
      body = Json.write(input);
      assertTrue(
          body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= GenericEventInput.LIMIT);
    }
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var source =
        new EvidenceIngressSettings.Source(
            "selfcheck",
            "unused",
            "synthetic-org",
            "synthetic-system",
            true,
            Set.of("read", "submit", "retry"),
            Set.of("BUSINESS_EVENT"));
    final IngressError expected;
    if (kind.equals("canonical-oversize")) {
      expected =
          assertThrows(
              IngressError.class,
              () ->
                  GenericEventRules.canonical(
                      source,
                      GenericEventInput.parse(bytes),
                      UUID.randomUUID().toString(),
                      UUID.randomUUID().toString(),
                      1));
    } else {
      var controller =
          new GenericIngressController(mock(GenericEventService.class), "synthetic", true);
      var request = new org.springframework.mock.web.MockHttpServletRequest();
      request.setAttribute("EVIDENCE_SOURCE", source);
      request.setContentType(media);
      request.setContent(bytes);
      expected = assertThrows(IngressError.class, () -> controller.submit(request));
    }
    try (Session s = start("normal")) {
      var request =
          HttpRequest.newBuilder(URI.create(s.info.get("baseUrl").toString()))
              .header("Authorization", "Bearer " + s.token)
              .header("Content-Type", media)
              .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(expected.status, response.statusCode());
      assertEquals(expected.body(), parsed(response));
      assertEquals(1, s.result.get(5, TimeUnit.SECONDS));
    }
  }

  private static void assertReceiptMatchesPublishedOpenApi(Map<String, Object> receipt)
      throws Exception {
    var document = Json.MAPPER.readTree(Files.readAllBytes(Path.of("../docs/openapi.json")));
    var schema = document.at("/components/schemas/GenericEvidenceReceipt");
    Set<String> required = new HashSet<>(), properties = new HashSet<>();
    schema.get("required").forEach(n -> required.add(n.asText()));
    schema.get("properties").fieldNames().forEachRemaining(properties::add);
    assertTrue(receipt.keySet().containsAll(required));
    assertTrue(properties.containsAll(receipt.keySet()));
    for (String name : List.of("status", "taskState")) {
      Set<String> values = new HashSet<>();
      schema.get("properties").get(name).get("enum").forEach(n -> values.add(n.asText()));
      assertTrue(values.contains(receipt.get(name)));
    }
  }

  private Session start(String scene) throws Exception {
    return start(scene, 2);
  }

  private Session start(String scene, int lifetime) throws Exception {
    Path folder =
        Files.createDirectories(temporary.resolve(".local").resolve(UUID.randomUUID().toString()));
    Path config = folder.resolve("profile.json"),
        connection = folder.resolve("connection.json"),
        report = folder.resolve("report.json");
    Files.writeString(
        config,
        "{\"sourceId\":\"selfcheck\",\"orgId\":\"synthetic-org\",\"sourceSystem\":\"synthetic-system\",\"operations\":[\"read\",\"submit\",\"retry\"],\"allowedEventTypes\":[\"BUSINESS_EVENT\"]}");
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Integer> result =
        executor.submit(() -> Simulator.run(config, scene, lifetime, connection, report));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!Files.exists(connection) && System.nanoTime() < deadline) {
      if (result.isDone()) result.get();
      Thread.sleep(10);
    }
    var info = Json.map(Files.readString(connection));
    return new Session(info, result, executor, report);
  }

  private HttpResponse<String> call(
      Session s, String method, String path, String body, String token) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(s.info.get("baseUrl") + path))
            .timeout(Duration.ofSeconds(3));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (body != null) builder.header("Content-Type", "application/json");
    return client.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private Map<String, Object> parsed(HttpResponse<String> response) {
    return Json.map(response.body());
  }

  private static String event(String number, int amount, String prev) {
    var event = new LinkedHashMap<String, Object>();
    event.put("schemaVersion", 1);
    event.put("sourceEventId", number);
    event.put("eventType", "BUSINESS_EVENT");
    event.put("businessObjectId", "object-one");
    event.put("occurredAt", "2026-01-01T00:00:00Z");
    event.put("payload", Map.of("amount", amount));
    if (prev != null) event.put("supersedesId", prev);
    return Json.write(event);
  }

  private static class Session implements AutoCloseable {
    final Map<String, Object> info;
    final String token;
    final Future<Integer> result;
    final ExecutorService executor;
    final Path report;

    Session(
        Map<String, Object> info, Future<Integer> result, ExecutorService executor, Path report) {
      this.info = info;
      this.token = info.get("token").toString();
      this.result = result;
      this.executor = executor;
      this.report = report;
    }

    public void close() {
      result.cancel(true);
      executor.shutdownNow();
    }
  }
}
