package com.bproject.trust.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.trust.events.EventInput;
import com.bproject.trust.events.EventService;
import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.ports.LedgerGateway;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.trace.TraceService;
import com.bproject.trust.verification.VerificationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Real openGauss transactions and HTTP authorization; external archive/ledger faults are injected.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TRUST_DB_INTEGRATION", matches = "1")
class DatabaseIntegrationTest {
  @MockitoBean EvidenceStorage ipfs;
  @MockitoBean LedgerGateway fabric;
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired EventService events;
  @Autowired TraceService traces;
  @Autowired VerificationService verification;
  static Path testRoot;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) throws Exception {
    String base = System.getenv("TRUST_ROOT");
    testRoot = Path.of(base, "runtime/db-integration");
    Files.createDirectories(testRoot);
    p.add("trust.root", () -> testRoot.toString());
    p.add("spring.datasource.url", () -> "jdbc:opengauss://127.0.0.1:25432/trust_test");
  }

  final Map<String, byte[]> stored = new ConcurrentHashMap<>();
  final Map<String, Map<String, Object>> ledger = new ConcurrentHashMap<>();
  final AtomicBoolean ipfsUp = new AtomicBoolean(false),
      ledgerUp = new AtomicBoolean(true),
      timeoutOnce = new AtomicBoolean(false);
  final AtomicInteger submitCount = new AtomicInteger();
  String actor = "integration-" + UUID.randomUUID().toString().substring(0, 8);
  String org = "INTEGRATION-" + UUID.randomUUID().toString().substring(0, 8);

  @BeforeEach
  void setup() throws Exception {
    try (var connection = db.getDataSource().getConnection()) {
      assertTrue(connection.getMetaData().getURL().endsWith("/trust_test"));
    }
    db.update("INSERT INTO trust_users VALUES(?,?,?,?)", actor, "unused", "EDITOR", org);
    db.update(
        "INSERT INTO trust_users VALUES(?,?,?,?)",
        actor + "-outside",
        "unused",
        "VIEWER",
        "EXTERNAL-TEST");
    when(ipfs.add(any()))
        .thenAnswer(
            call -> {
              if (!ipfsUp.get()) throw new java.io.IOException("injected IPFS outage");
              byte[] b = call.getArgument(0);
              String cid = "baf" + Json.sha(b);
              stored.put(cid, b);
              return cid;
            });
    when(ipfs.cat(anyString()))
        .thenAnswer(
            call -> {
              if (!ipfsUp.get()) throw new java.io.IOException("injected IPFS outage");
              byte[] b = stored.get(call.getArgument(0));
              if (b == null) throw new java.io.IOException("missing block");
              return b;
            });
    when(fabric.find(anyString(), anyString()))
        .thenAnswer(
            call -> {
              if (!ledgerUp.get()) throw new java.io.IOException("injected ledger outage");
              return ledger.get(call.getArgument(1));
            });
    when(fabric.submit(anyMap(), any()))
        .thenAnswer(
            call -> {
              if (!ledgerUp.get()) throw new java.io.IOException("injected ledger outage");
              submitCount.incrementAndGet();
              Map<String, Object> r = new HashMap<>(call.getArgument(0));
              String tid = UUID.randomUUID().toString();
              Consumer<String> cb = call.getArgument(1);
              cb.accept(tid);
              r.put("txId", tid);
              r.put("ledgerIdentity", "injected-test-identity");
              ledger.put((String) r.get("id"), r);
              if (timeoutOnce.getAndSet(false))
                throw new java.io.IOException("injected timeout after valid ledger commit");
              return r;
            });
  }

  Map<String, Object> response(org.springframework.test.web.servlet.ResultActions action)
      throws Exception {
    return Json.map(action.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  EventInput input(String source, List<String> evidence, List<String> batches, String reference) {
    return new EventInput(
        "TEST",
        source,
        "ARRIVAL",
        source,
        "LOT-" + actor,
        Instant.parse("2026-09-10T01:00:00Z"),
        List.of("BUNDLE-" + actor),
        "HANDOVER-" + actor,
        batches,
        reference == null ? List.of() : List.of(reference),
        new java.math.BigDecimal("100"),
        "吨",
        "测试货场",
        "模拟供应方",
        "模拟接收方",
        evidence,
        Map.of("note", "integration fixture"));
  }

  Map<String, Object> send(EventInput data) throws Exception {
    return response(
        mvc.perform(
                post("/api/v1/events")
                    .with(user(actor).roles("EDITOR"))
                    .with(csrf())
                    .contentType("application/json")
                    .content(Json.write(data)))
            .andExpect(status().isAccepted()));
  }

  Map<String, Object> await(String id, Predicate<Map<String, Object>> condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
    Map<String, Object> r;
    do {
      r = events.get(id, org);
      if (condition.test(r)) return r;
      Thread.sleep(100);
    } while (System.nanoTime() < end);
    throw new AssertionError(r);
  }

  void retry(String id) throws Exception {
    for (int i = 0; i < 100; i++) {
      int code =
          mvc.perform(
                  post("/api/v1/events/" + id + "/retry")
                      .with(user(actor).roles("EDITOR"))
                      .with(csrf()))
              .andReturn()
              .getResponse()
              .getStatus();
      if (code == 200) return;
      assertEquals(409, code);
      Thread.sleep(100);
    }
    fail("task lease did not finish");
  }

  @Test
  void durableEventsAndInjectedFailureRecovery() throws Exception {
    var file =
        new MockMultipartFile(
            "file",
            "proof.pdf",
            "application/pdf",
            "%PDF-1.4 test fixture".getBytes(StandardCharsets.UTF_8));
    String evidenceId =
        (String)
            response(
                    mvc.perform(
                            multipart("/api/v1/evidence")
                                .file(file)
                                .with(user(actor).roles("EDITOR"))
                                .with(csrf()))
                        .andExpect(status().isOk()))
                .get("id");
    EventInput initial =
        input("ARRIVAL-" + actor, List.of(evidenceId), List.of(), "TEST:LATE-" + actor);
    var saved = send(initial);
    String id = (String) saved.get("id");
    assertEquals(id, send(initial).get("id"));
    var changed = Json.map(Json.write(initial));
    changed.put("location", "different");
    mvc.perform(
            post("/api/v1/events")
                .with(user(actor).roles("EDITOR"))
                .with(csrf())
                .contentType("application/json")
                .content(Json.write(changed)))
        .andExpect(status().isConflict());
    await(id, e -> "FAILED".equals(e.get("chain_state")));
    assertEquals("PENDING", events.get(id, org).get("file_state"));
    assertTrue(
        ((List<?>) events.detail(id, org).get("missingReferences")).contains("TEST:LATE-" + actor));
    ipfsUp.set(true);
    ledgerUp.set(false);
    retry(id);
    await(id, e -> "STORED".equals(e.get("file_state")) && "FAILED".equals(e.get("chain_state")));
    Thread.sleep(200);
    ledgerUp.set(true);
    timeoutOnce.set(true);
    retry(id);
    await(id, e -> "COMMITTED".equals(e.get("chain_state")));
    assertEquals(
        1,
        submitCount.get(),
        "Committed-but-timeout transaction must be found before resubmitting");
    var verified = verification.verify(id, org, actor);
    assertEquals(true, verified.get("ok"), Json.write(verified));
    db.update("UPDATE events SET batch_id='REPLACED' WHERE id=?", id);
    assertEquals(false, verification.verify(id, org, actor).get("ok"));
    db.update("UPDATE events SET batch_id=? WHERE id=?", initial.batchId(), id);
    db.update("INSERT INTO event_links VALUES(?,'BATCH','REPLACED')", id);
    assertEquals(false, verification.verify(id, org, actor).get("ok"));
    db.update(
        "DELETE FROM event_links WHERE event_id=? AND kind='BATCH' AND target='REPLACED'", id);
    String originalSha =
        (String) db.queryForMap("SELECT sha256 FROM evidence WHERE id=?", evidenceId).get("sha256");
    db.update("UPDATE evidence SET sha256=? WHERE id=?", "0".repeat(64), evidenceId);
    assertEquals(false, verification.verify(id, org, actor).get("ok"));
    db.update("UPDATE evidence SET sha256=? WHERE id=?", originalSha, evidenceId);
    String cid = db.queryForObject("SELECT cid FROM evidence WHERE id=?", String.class, evidenceId);
    byte[] original = stored.remove(cid);
    assertEquals(false, verification.verify(id, org, actor).get("ok"));
    stored.put(cid, original);
    String snapshot = (String) events.get(id, org).get("canonical_json");
    EventInput correction = input("CORRECTION-" + actor, List.of(evidenceId), List.of(), null);
    var revised =
        response(
            mvc.perform(
                    post("/api/v1/events/" + id + "/corrections")
                        .with(user(actor).roles("EDITOR"))
                        .with(csrf())
                        .contentType("application/json")
                        .content(Json.write(correction)))
                .andExpect(status().isAccepted()));
    await((String) revised.get("id"), e -> "COMMITTED".equals(e.get("chain_state")));
    assertEquals(snapshot, events.get(id, org).get("canonical_json"));
    assertEquals(2, ((Number) revised.get("version")).intValue());
    send(input("LATE-" + actor, List.of(), List.of(), null));
    assertTrue(((List<?>) events.detail(id, org).get("missingReferences")).isEmpty());
    assertTrue(
        ((List<?>) traces.trace(org, "HANDOVER", "HANDOVER-" + actor).get("items")).size() >= 3);
    for (int quantity : List.of(60, 40)) {
      var dispatch =
          new EventInput(
              "TEST",
              "DISPATCH-" + quantity + "-" + actor,
              "DISPATCH",
              "STEEL-ORDER",
              "LOT-" + actor + "-" + quantity,
              Instant.parse("2026-09-10T10:00:00Z"),
              List.of(),
              "DISPATCH-NOTE-" + quantity + "-" + actor,
              List.of("LOT-" + actor),
              List.of("TEST:ARRIVAL-" + actor),
              new java.math.BigDecimal(quantity),
              "吨",
              "发运区",
              "模拟供应方",
              "模拟接收方",
              List.of(evidenceId),
              Map.of());
      var child = send(dispatch);
      await((String) child.get("id"), e -> "COMMITTED".equals(e.get("chain_state")));
      @SuppressWarnings("unchecked")
      var reverse =
          (List<Map<String, Object>>)
              traces.trace(org, "HANDOVER", "DISPATCH-NOTE-" + quantity + "-" + actor).get("items");
      assertTrue(
          reverse.stream().anyMatch(e -> id.equals(e.get("id"))),
          "Each shipment must trace back to its arrival");
    }
    @SuppressWarnings("unchecked")
    var forward =
        (List<Map<String, Object>>) traces.trace(org, "BATCH", "LOT-" + actor).get("items");
    assertEquals(2, forward.stream().filter(e -> "DISPATCH".equals(e.get("event_type"))).count());
    for (String path :
        List.of("/api/v1/events/" + id, "/api/v1/evidence/" + evidenceId + "/download"))
      mvc.perform(get(path).with(user(actor + "-outside").roles("VIEWER")))
          .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/events")
                .with(user(actor + "-outside").roles("VIEWER"))
                .with(csrf())
                .contentType("application/json")
                .content(Json.write(initial)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/events")
                .with(user(actor).roles("EDITOR"))
                .contentType("application/json")
                .content(Json.write(initial)))
        .andExpect(status().isForbidden());
    byte[] exported =
        mvc.perform(
                post("/api/v1/events/" + id + "/export")
                    .with(user(actor).roles("EDITOR"))
                    .with(csrf()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported))) {
      var names = new HashSet<String>();
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
        names.add(entry.getName());
      assertTrue(
          names.containsAll(
              List.of(
                  "event.json", "checksums.json", "evidence-manifest.json", "ledger-query.json")));
    }
    try (var temporary = Files.list(testRoot.resolve("runtime/exports"))) {
      assertEquals(0, temporary.count(), "Download must remove its temporary ZIP");
    }
    var imported =
        response(
            mvc.perform(
                    multipart("/api/v1/imports")
                        .file(
                            new MockMultipartFile(
                                "file",
                                "events.json",
                                "application/json",
                                Json.bytes(List.of(initial))))
                        .with(user(actor).roles("EDITOR"))
                        .with(csrf()))
                .andExpect(status().isOk()));
    assertEquals(1, ((Number) imported.get("accepted")).intValue());
    String csv =
        "sourceSystem,sourceEventId,eventType,businessObjectId,batchId,occurredAt\nTEST,CSV-"
            + actor
            + ",ARRIVAL,CSV-OBJECT,CSV-LOT,2026-09-10T01:00:00Z\n";
    var csvResult =
        response(
            mvc.perform(
                    multipart("/api/v1/imports")
                        .file(
                            new MockMultipartFile(
                                "file",
                                "events.csv",
                                "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .with(user(actor).roles("EDITOR"))
                        .with(csrf()))
                .andExpect(status().isOk()));
    assertEquals(1, ((Number) csvResult.get("accepted")).intValue());
    var current =
        response(
            mvc.perform(get("/api/v1/me").with(user(actor).roles("EDITOR")))
                .andExpect(status().isOk()));
    assertEquals(org, current.get("orgId"));
    for (String endpoint : List.of("/api/v1/tasks", "/api/v1/status", "/api/v1/audit")) {
      mvc.perform(get(endpoint).with(user(actor).roles("ADMIN"))).andExpect(status().isOk());
    }
    var outsideTrace =
        response(
            mvc.perform(
                    get("/api/v1/trace")
                        .param("kind", "BATCH")
                        .param("value", initial.batchId())
                        .with(user(actor + "-outside").roles("VIEWER")))
                .andExpect(status().isOk()));
    assertTrue(((List<?>) outsideTrace.get("items")).isEmpty());
    var spec =
        mvc.perform(get("/v3/api-docs").with(user(actor).roles("EDITOR")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    Path docs = Path.of(System.getenv("TRUST_ROOT"), "docs");
    Files.createDirectories(docs);
    Files.write(docs.resolve("openapi.json"), spec);
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      EventInput concurrent = input("CONCURRENT-" + actor, List.of(), List.of(), null);
      var calls = new ArrayList<Callable<String>>();
      for (int i = 0; i < 6; i++)
        calls.add(() -> (String) events.submit(concurrent, org, actor, null).get("id"));
      Set<String> ids = new HashSet<>();
      for (var future : pool.invokeAll(calls)) ids.add(future.get());
      assertEquals(1, ids.size());
      await(ids.iterator().next(), e -> "COMMITTED".equals(e.get("chain_state")));
    } finally {
      pool.shutdownNow();
    }
  }
}
