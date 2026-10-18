package com.bproject.trust.ingress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.trust.archiving.*;
import com.bproject.trust.audit.AuditService;
import com.bproject.trust.events.*;
import com.bproject.trust.evidence.EvidenceService;
import com.bproject.trust.identity.*;
import com.bproject.trust.ports.*;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.*;
import com.bproject.trust.support.TestDatabase;
import com.bproject.trust.verification.*;
import com.bproject.trust.wallet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real restricted-role SQL/transactions; storage, ledger and certificate material are test doubles.
 */
@EnabledIfEnvironmentVariable(named = "TRUST_GENERIC_DB_TEST", matches = "1")
class GenericDatabaseIntegrationTest {
  @TempDir Path root;
  JdbcTemplate db, migration;
  TransactionTemplate tx;
  GenericEventService service;
  EventService events;
  AuditService audit;
  WalletService wallets;
  WalletKeyStore keys;
  EvidenceStorage ipfs;
  LedgerGateway ledger;
  EvidenceIngressSettings settings;
  GenericEvidenceArchive archive;
  ArchiveWorker worker;
  VerificationService verification;
  EvidenceIngressSettings.Source source;
  String org, namespace;
  Map<String, byte[]> storage = new ConcurrentHashMap<>();
  Map<String, Map<String, Object>> chain = new ConcurrentHashMap<>();
  AtomicBoolean storageDown = new AtomicBoolean(), timeoutOnce = new AtomicBoolean();
  AtomicInteger submissions = new AtomicInteger();

  @BeforeEach
  void setup() throws Exception {
    var ds = TestDatabase.dataSource(false);
    db = new JdbcTemplate(ds);
    migration = new JdbcTemplate(TestDatabase.dataSource(true));
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    org = "GENERIC-" + UUID.randomUUID();
    namespace = "MAINT-" + UUID.randomUUID();
    source =
        new EvidenceIngressSettings.Source(
            "generic-" + UUID.randomUUID(),
            "0".repeat(64),
            org,
            namespace,
            true,
            Set.of("read", "submit", "retry"),
            Set.of("MAINTENANCE_CONFIRMED"));
    audit = new AuditService(db);
    events = new EventService(db, tx, audit, new EventRelations(db));
    service = new GenericEventService(db, tx, audit);
    ipfs = mock(EvidenceStorage.class);
    ledger = mock(LedgerGateway.class);
    keys = mock(WalletKeyStore.class);
    wallets = new WalletService(db, tx, keys, audit);
    settings = mock(EvidenceIngressSettings.class);
    when(settings.permits(anyMap())).thenReturn(true);
    when(settings.processingSources()).thenReturn(List.of(source));
    archive = new GenericEvidenceArchive(db, ipfs);
    var evidence = mock(EvidenceService.class);
    worker = new ArchiveWorker(db, events, evidence, ipfs, ledger, tx, wallets, settings, archive);
    verification = new VerificationService(events, evidence, ipfs, ledger, audit, root.toString());
    when(ipfs.add(any()))
        .thenAnswer(
            call -> {
              if (storageDown.get()) throw new java.io.IOException("injected storage outage");
              byte[] value = call.getArgument(0);
              String cid = "baf" + Json.sha(value);
              storage.put(cid, value.clone());
              return cid;
            });
    when(ipfs.cat(anyString()))
        .thenAnswer(
            call -> {
              if (storageDown.get() || !storage.containsKey(call.getArgument(0)))
                throw new java.io.IOException("injected missing block");
              return storage.get(call.getArgument(0)).clone();
            });
    when(ledger.find(anyString(), anyString())).thenAnswer(call -> chain.get(call.getArgument(1)));
    when(ledger.submit(anyMap(), any()))
        .thenAnswer(
            call -> {
              submissions.incrementAndGet();
              Map<String, Object> value = new TreeMap<>(call.<Map<String, Object>>getArgument(0));
              String tid = UUID.randomUUID().toString();
              ((Consumer<String>) call.getArgument(1)).accept(tid);
              value.put("txId", tid);
              value.put("ledgerIdentity", "synthetic-signer");
              value.put("blockNumber", 7L);
              chain.put((String) value.get("id"), value);
              if (timeoutOnce.getAndSet(false))
                throw new java.io.IOException("injected response loss after commit");
              return value;
            });
  }

  @AfterEach
  void isolateFixtures() {
    ReflectionTestUtils.invokeMethod(worker, "close");
    db.update(
        "UPDATE tasks SET state='FAILED',lease_token=NULL,lease_until=NULL WHERE event_id"
            + " IN(SELECT id FROM events WHERE org_id=?)",
        org);
  }

  GenericEventInput input(String number, int value) {
    return GenericEventInputTest.parse(
        GenericEventInputTest.body("{\"value\":" + value + ",\"text\":\"原文字节\"}")
            .replace("item-1", number));
  }

  String submit(String number) {
    return (String) service.submit(source, input(number, 1)).receipt().get("id");
  }

  void run(String id) {
    FixtureArchiveRunner.run(worker, db, id, org);
  }

  void ready(String id) {
    db.update("UPDATE tasks SET next_at=CURRENT_TIMESTAMP WHERE event_id=?", id);
  }

  long count(String table) {
    return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  <T> List<T> concurrent(int n, java.util.function.IntFunction<T> action) throws Exception {
    var pool = Executors.newFixedThreadPool(n);
    var start = new CountDownLatch(1);
    try {
      List<Future<T>> calls = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int index = i;
        calls.add(
            pool.submit(
                () -> {
                  start.await();
                  return action.apply(index);
                }));
      }
      start.countDown();
      List<T> results = new ArrayList<>();
      for (var f : calls) results.add(f.get(30, TimeUnit.SECONDS));
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void sameNumberTwelveWayRaceCreatesExactlyOneImmutableOriginalAndTask() throws Exception {
    var results = concurrent(12, i -> service.submit(source, input("same-number", 1)));
    assertEquals(1, results.stream().filter(r -> r.status() == 202).count());
    assertEquals(1, results.stream().map(r -> r.receipt().get("id")).distinct().count());
    String id = (String) results.get(0).receipt().get("id");
    String original =
        db.queryForObject(
            "SELECT original_utf8 FROM generic_event_data WHERE event_id=?", String.class, id);
    var replay = service.submit(source, GenericEventInputTest.parse(" \n" + original));
    assertEquals(200, replay.status());
    assertEquals(
        original,
        db.queryForObject(
            "SELECT original_utf8 FROM generic_event_data WHERE event_id=?", String.class, id));
    assertEquals(
        1, db.queryForObject("SELECT COUNT(*) FROM tasks WHERE event_id=?", Integer.class, id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE object_id=? AND"
                + " action='GENERIC_EVENT_SUBMIT'",
            Integer.class,
            id));
    assertEquals(
        409,
        assertThrows(IngressError.class, () -> service.submit(source, input("same-number", 2)))
            .status);
  }

  @Test
  void competingDifferentBodiesNeverOverwriteWinner() throws Exception {
    var results =
        concurrent(
            12,
            i -> {
              try {
                return service.submit(source, input("different", i % 2)).status();
              } catch (IngressError e) {
                return e.status;
              }
            });
    assertEquals(1, results.stream().filter(s -> s == 202).count());
    assertEquals(6, results.stream().filter(s -> s == 409).count());
    assertEquals(5, results.stream().filter(s -> s == 200).count());
  }

  @Test
  void allFourWritesAndNamespaceReservationRollbackOnEachInsertFailure() {
    for (String table : List.of("generic_event_data", "tasks", "audit_log")) {
      var before =
          List.of(
              count("events"),
              count("generic_event_data"),
              count("tasks"),
              count("audit_log"),
              count("event_source_routes"));
      migration.execute(
          "CREATE FUNCTION generic_injected_failure() RETURNS trigger LANGUAGE plpgsql AS"
              + " $$ BEGIN RAISE EXCEPTION 'injected transaction failure'; END $$");
      migration.execute(
          "CREATE TRIGGER generic_fail BEFORE INSERT ON "
              + table
              + " FOR EACH ROW EXECUTE PROCEDURE generic_injected_failure()");
      try {
        assertThrows(DataAccessException.class, () -> submit("rollback-" + table));
      } finally {
        migration.execute("DROP TRIGGER generic_fail ON " + table);
        migration.execute("DROP FUNCTION generic_injected_failure()");
      }
      assertEquals(
          before,
          List.of(
              count("events"),
              count("generic_event_data"),
              count("tasks"),
              count("audit_log"),
              count("event_source_routes")));
    }
  }

  @Test
  void sourceScopeAndPermanentNamespaceOwnershipPreventTakeover() {
    String id = submit("scope");
    for (var other :
        List.of(
            new EvidenceIngressSettings.Source(
                source.id(),
                "0".repeat(64),
                org + "-other",
                namespace,
                true,
                source.operations(),
                source.allowedEventTypes()),
            new EvidenceIngressSettings.Source(
                source.id(),
                "0".repeat(64),
                org,
                namespace + "-other",
                true,
                source.operations(),
                source.allowedEventTypes()),
            new EvidenceIngressSettings.Source(
                "other-owner",
                "0".repeat(64),
                org,
                namespace,
                true,
                source.operations(),
                source.allowedEventTypes()))) {
      assertEquals(404, assertThrows(IngressError.class, () -> service.receipt(other, id)).status);
      assertEquals(
          404, assertThrows(IngressError.class, () -> service.byNumber(other, "scope")).status);
      assertEquals(404, assertThrows(IngressError.class, () -> service.retry(other, id)).status);
    }
    var takeover =
        new EvidenceIngressSettings.Source(
            "other-owner",
            "0".repeat(64),
            org,
            namespace,
            true,
            source.operations(),
            source.allowedEventTypes());
    assertEquals(
        "SOURCE_NAMESPACE_CONFLICT",
        assertThrows(IngressError.class, () -> service.submit(takeover, input("new", 1))).code);
    assertThrows(
        IngressError.class,
        () -> events.submit(legacy(namespace, "legacy"), org, "local-actor", null));
    events.submit(legacy("LEGACY-SOURCE", "one"), org, "local-actor", null);
    var collision =
        new EvidenceIngressSettings.Source(
            source.id(),
            source.tokenSha256(),
            org,
            "LEGACY-SOURCE",
            true,
            source.operations(),
            source.allowedEventTypes());
    assertEquals(
        "SOURCE_NAMESPACE_CONFLICT",
        assertThrows(IngressError.class, () -> service.submit(collision, input("two", 1))).code);
  }

  EventInput legacy(String sourceName, String number) {
    return Json.MAPPER.convertValue(
        Map.of(
            "sourceSystem",
            sourceName,
            "sourceEventId",
            number,
            "eventType",
            "WAREHOUSE_IN",
            "businessObjectId",
            "object",
            "batchId",
            "batch",
            "occurredAt",
            "2026-10-12T02:00:00Z"),
        EventInput.class);
  }

  @Test
  void concurrentCorrectionsAppendOnlyOneVersion() throws Exception {
    String id = submit("original");
    String original =
        db.queryForObject("SELECT canonical_json FROM events WHERE id=?", String.class, id);
    var results =
        concurrent(
            8,
            i -> {
              var body = new TreeMap<>(Json.map(input("correction-" + i, 2).original()));
              body.put("supersedesId", id);
              try {
                return service
                    .submit(source, GenericEventInputTest.parse(Json.write(body)))
                    .status();
              } catch (IngressError e) {
                return e.status;
              }
            });
    assertEquals(1, results.stream().filter(s -> s == 202).count());
    assertEquals(7, results.stream().filter(s -> s == 409).count());
    assertEquals(
        original,
        db.queryForObject("SELECT canonical_json FROM events WHERE id=?", String.class, id));
    assertEquals(
        2, db.queryForObject("SELECT MAX(version) FROM events WHERE root_id=?", Integer.class, id));
  }

  @Test
  void explicitRetryHasIndependentPermissionAndLockedStateTransitions() throws Exception {
    String id = submit("retry");
    var readOnly =
        new EvidenceIngressSettings.Source(
            source.id(),
            source.tokenSha256(),
            org,
            namespace,
            true,
            Set.of("read"),
            source.allowedEventTypes());
    var before = db.queryForMap("SELECT * FROM tasks WHERE event_id=?", id);
    assertEquals(403, assertThrows(IngressError.class, () -> service.retry(readOnly, id)).status);
    assertEquals(before, db.queryForMap("SELECT * FROM tasks WHERE event_id=?", id));
    assertEquals(false, service.retry(source, id).receipt().get("queued"));
    db.update(
        "UPDATE tasks SET state='RUNNING',lease_until=CURRENT_TIMESTAMP-INTERVAL '1 hour'"
            + " WHERE event_id=?",
        id);
    assertEquals(409, assertThrows(IngressError.class, () -> service.retry(source, id)).status);
    db.update(
        "UPDATE tasks SET state='FAILED',attempts=10,last_error='private error' WHERE"
            + " event_id=?",
        id);
    db.update("UPDATE events SET tx_id='retained',chain_state='FAILED' WHERE id=?", id);
    assertEquals(200, service.submit(source, input("retry", 1)).status());
    assertEquals("FAILED", service.receipt(source, id).get("taskState"));
    var results = concurrent(12, i -> service.retry(source, id));
    assertEquals(1, results.stream().filter(r -> r.status() == 202).count());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE object_id=? AND"
                + " action='GENERIC_EVENT_RETRY'",
            Integer.class,
            id));
    assertEquals("retained", service.receipt(source, id).get("txId"));
    db.update("UPDATE tasks SET state='DONE' WHERE event_id=?", id);
    assertEquals(
        "INTEGRITY_MISMATCH",
        assertThrows(IngressError.class, () -> service.retry(source, id)).code);
    db.update("UPDATE events SET chain_state='COMMITTED' WHERE id=?", id);
    assertEquals(false, service.retry(source, id).receipt().get("queued"));
  }

  @Test
  void iamPrincipalCannotReadGenericThroughLegacyVerifyExportOrCorrection() throws Exception {
    String id = submit("private-original");
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            new PlatformPrincipal("user", "user", org), null, List.of());
    var controller = new EventController(new CurrentIdentity(db), events, audit);
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      ReflectionTestUtils.setField(controller, "validator", factory.getValidator());
      var http =
          MockMvcBuilders.standaloneSetup(
                  new VerificationController(new CurrentIdentity(db), verification), controller)
              .setControllerAdvice(new ErrorHandler())
              .build();
      for (String operation : List.of("verify", "export")) {
        var result =
            http.perform(post("/api/v1/events/" + id + "/" + operation).principal(auth))
                .andExpect(status().isNotFound())
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("原文字节"));
      }
      http.perform(
              post("/api/v1/events/" + id + "/corrections")
                  .principal(auth)
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("EVENT_SCHEMA_MISMATCH"));
      assertEquals(
          404, assertThrows(ApiError.class, () -> verification.verify(id, org, "iam:user")).status);
      assertEquals(
          404, assertThrows(ApiError.class, () -> verification.export(id, org, "iam:user")).status);
      assertEquals(0, ((Number) events.list(org, "", 0, 30).get("total")).intValue());
      verifyNoInteractions(ipfs, ledger);
      try (var files = Files.list(root.resolve("runtime/exports"))) {
        assertEquals(0, files.count());
      }
    }
  }

  @Test
  void legacyCorrectionsKeepValidRequestsAndRejectMalformedTypes() throws Exception {
    String id =
        (String) events.submit(legacy("LEGACY", "base"), org, "local-actor", null).get("id");
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            new PlatformPrincipal("user", "user", org), null, List.of());
    var controller = new EventController(new CurrentIdentity(db), events, audit);
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      ReflectionTestUtils.setField(controller, "validator", factory.getValidator());
      var http =
          MockMvcBuilders.standaloneSetup(controller)
              .setControllerAdvice(new ErrorHandler())
              .build();
      String valid = Json.write(legacy("LEGACY", "next"));
      for (String invalid :
          List.of(
              "null",
              "[]",
              "{}",
              valid.replace("\"batchId\":\"batch\"", "\"batchId\":{}"),
              valid.replace("\"batchId\"", "\"unknown\"")))
        http.perform(
                post("/api/v1/events/" + id + "/corrections")
                    .principal(auth)
                    .contentType("application/json")
                    .content(invalid))
            .andExpect(status().isBadRequest());
      http.perform(
              post("/api/v1/events/" + id + "/corrections")
                  .principal(auth)
                  .contentType("application/json")
                  .content(valid))
          .andExpect(status().isAccepted());
    }
  }

  void approveWallet() throws Exception {
    String ref = "synthetic-" + UUID.randomUUID();
    var cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=synthetic"));
    when(cert.getNotAfter()).thenReturn(Date.from(Instant.now().plusSeconds(86400)));
    when(keys.load(ref))
        .thenReturn(
            new WalletKeyStore.Material(
                cert,
                null,
                "SYNTHETIC CERTIFICATE",
                Json.sha(ref.getBytes(StandardCharsets.UTF_8))));
    String registration =
        (String)
            wallets
                .propose(
                    org,
                    "iam:proposer",
                    "REGISTER",
                    new WalletService.Change("synthetic", "Org1MSP", ref, null, null, null),
                    "isolated fixture")
                .get("id");
    wallets.review(org, "iam:reviewer", registration, true);
    String binding =
        (String)
            wallets
                .propose(
                    org,
                    "iam:proposer",
                    "BIND",
                    new WalletService.Change(null, null, null, registration, namespace, 0),
                    "isolated fixture")
                .get("id");
    wallets.review(org, "iam:reviewer", binding, true);
  }

  @Test
  void exactOriginalSurvivesLostLedgerReceiptAndRecoveryDoesNotResubmit() throws Exception {
    approveWallet();
    String id = submit("lost-response");
    timeoutOnce.set(true);
    run(id);
    assertEquals(true, service.receipt(source, id).get("outcomeUnknown"));
    assertEquals(1, submissions.get());
    ready(id);
    run(id);
    assertEquals(1, submissions.get());
    assertEquals("COMMITTED", service.receipt(source, id).get("status"));
    assertEquals(false, service.receipt(source, id).get("outcomeUnknown"));
    assertArrayEquals(
        input("lost-response", 1).original().getBytes(StandardCharsets.UTF_8),
        archive.verify(events.get(id, org)));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM event_signing_context WHERE event_id=?", Integer.class, id));
    assertEquals("2", chain.get(id).get("contractVersion"));
    String rawCid = (String) service.receipt(source, id).get("originalCid");
    storage.put(rawCid, "tampered".getBytes(StandardCharsets.UTF_8));
    assertThrows(IngressError.class, () -> archive.verify(events.get(id, org)));
  }

  @Test
  void storageFailureAndMissingWalletCannotFallBackToLegacySigner() {
    String id = submit("dependency");
    storageDown.set(true);
    run(id);
    assertEquals("RETRY_WAIT", service.receipt(source, id).get("status"));
    assertEquals(0, submissions.get());
    storageDown.set(false);
    ready(id);
    run(id);
    assertEquals("ACTION_REQUIRED", service.receipt(source, id).get("status"));
    assertEquals("SIGNING_NOT_READY", service.receipt(source, id).get("errorCode"));
    assertEquals(0, submissions.get());
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM signing_attempts WHERE event_id=?", Integer.class, id));
  }

  @Test
  void disabledSourceAndLostLeaseDoNotPublish() throws Exception {
    String id = submit("disabled");
    when(settings.permits(anyMap())).thenReturn(false);
    run(id);
    assertEquals("READY", service.receipt(source, id).get("taskState"));
    assertEquals(0, submissions.get());
    verifyNoInteractions(ipfs, ledger);
    when(settings.permits(anyMap())).thenReturn(true);
    doAnswer(
            call -> {
              db.update("UPDATE tasks SET lease_token='new-owner' WHERE event_id=?", id);
              return "baf-lost";
            })
        .when(ipfs)
        .add(any());
    run(id);
    assertNull(service.receipt(source, id).get("originalCid"));
    assertEquals(0, submissions.get());
  }

  @Test
  void expiredLeaseWithoutTakeoverCannotPublishOrPersistRawCid() throws Exception {
    String id = submit("expired-without-takeover");
    doAnswer(
            call -> {
              db.update(
                  "UPDATE tasks SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1"
                      + " second' WHERE event_id=?",
                  id);
              return "baf-expired";
            })
        .when(ipfs)
        .add(any());
    run(id);
    assertNull(service.receipt(source, id).get("originalCid"));
    assertEquals("RUNNING", service.receipt(source, id).get("taskState"));
    assertEquals(0, submissions.get());
    verifyNoInteractions(ledger);
  }

  @Test
  void actualJvmCrashAfterCommitRecoversWithoutSecondLedgerSubmission() throws Exception {
    Files.writeString(root.resolve("source.json"), Json.write(source));
    Files.writeString(
        root.resolve("request.json"),
        input("process-restart", 1).original(),
        StandardCharsets.UTF_8);
    assertEquals(23, child("crash"));
    String id = (String) Json.map(Files.readString(root.resolve("accepted.json"))).get("id");
    assertEquals("RUNNING", service.receipt(source, id).get("taskState"));
    assertEquals(true, service.receipt(source, id).get("outcomeUnknown"));
    byte[] ledgerBefore = Files.readAllBytes(root.resolve("ledger.json"));
    db.update(
        "UPDATE tasks SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE" + " event_id=?",
        id);
    assertEquals(0, child("recover"));
    assertNotEquals(
        Files.readString(root.resolve("crash-pid.txt")),
        Files.readString(root.resolve("recover-pid.txt")));
    assertArrayEquals(ledgerBefore, Files.readAllBytes(root.resolve("ledger.json")));
    assertEquals("COMMITTED", service.receipt(source, id).get("status"));
    assertEquals(false, service.receipt(source, id).get("outcomeUnknown"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM signing_attempts WHERE event_id=?", Integer.class, id));
    System.out.println(
        "PROCESS_RESTART_PROOF crashPid="
            + Files.readString(root.resolve("crash-pid.txt"))
            + " recoverPid="
            + Files.readString(root.resolve("recover-pid.txt"))
            + " status=COMMITTED signingAttempts=1");
  }

  int child(String operation) throws Exception {
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    var process =
        new ProcessBuilder(
                executable,
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                GenericProcessFixture.class.getName(),
                operation,
                root.toString())
            .redirectErrorStream(true)
            .redirectOutput(root.resolve(operation + ".log").toFile())
            .start();
    if (!process.waitFor(45, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      fail("Fixture process did not finish");
    }
    return process.exitValue();
  }

  @Test
  void legacyArchiveAndExportPreserveOriginalSchemaAndBytes() throws Exception {
    String id =
        (String) events.submit(legacy("LEGACY-GOLDEN", "one"), org, "local-actor", null).get("id");
    byte[] before =
        ((String) events.get(id, org).get("canonical_json")).getBytes(StandardCharsets.UTF_8);
    run(id);
    var after = events.get(id, org);
    assertEquals("COMMITTED", after.get("chain_state"));
    assertArrayEquals(
        before, ((String) after.get("canonical_json")).getBytes(StandardCharsets.UTF_8));
    assertEquals(1, Json.map((String) after.get("manifest_json")).get("schemaVersion"));
    assertEquals(true, verification.verify(id, org, "iam:test").get("ok"));
    Path zip = verification.export(id, org, "iam:test");
    try (var contents = new java.util.zip.ZipFile(zip.toFile())) {
      assertArrayEquals(
          before, contents.getInputStream(contents.getEntry("event.json")).readAllBytes());
      assertNull(contents.getEntry("source-request.json"));
      assertEquals(
          Set.of(
              "event.json",
              "evidence-manifest.json",
              "ledger-query.json",
              "checksums.json",
              "README.txt"),
          contents.stream()
              .map(java.util.zip.ZipEntry::getName)
              .collect(java.util.stream.Collectors.toSet()));
    } finally {
      Files.delete(zip);
    }
  }

  @Test
  void disabledGenericBacklogCannotStarveLegacyQueue() {
    for (int i = 0; i < 6; i++) {
      String id = submit("disabled-backlog-" + i);
      db.update("UPDATE tasks SET next_at=TIMESTAMP '1900-01-01' WHERE event_id=?", id);
    }
    String legacy =
        (String) events.submit(legacy("OLD-QUEUE", "ready"), org, "local-actor", null).get("id");
    db.update("UPDATE tasks SET next_at=TIMESTAMP '1900-01-02' WHERE event_id=?", legacy);
    when(settings.processingSources()).thenReturn(List.of());
    List<String> due = ReflectionTestUtils.invokeMethod(worker, "due", 1);
    assertEquals(List.of(legacy), due);
    assertEquals(
        0,
        db.queryForObject(
            "SELECT SUM(attempts) FROM tasks WHERE event_id IN(SELECT id FROM events"
                + " WHERE org_id=? AND record_kind='GENERIC_V1')",
            Integer.class,
            org));
  }
}
