package com.bproject.trust.logevidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.audit.AuditService;
import com.bproject.trust.identity.PlatformPrincipal;
import com.bproject.trust.ingress.*;
import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.shared.json.Json;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real local PostgreSQL transactions; IPFS and ledger are test doubles, not real component
 * acceptance.
 */
@EnabledIfEnvironmentVariable(named = "TRUST_LOG_DB_TEST", matches = "1")
class LogDatabaseTest {
  @TempDir Path root;
  JdbcTemplate db, admin;
  TransactionTemplate tx;
  String schema;
  LogSettings config;
  LogSettings.Source source;
  LogEvidenceService service;
  LogArchiveWorker worker;
  LogOperations ops;
  EvidenceStorage storage;
  Map<String, byte[]> blocks;
  EvidenceIngressSettings ingress;
  GenericEventService generic;

  @BeforeEach
  void setup() throws Exception {
    String url = System.getenv("TRUST_LOG_DB_URL");
    if (!"jdbc:postgresql://127.0.0.1:25434/trust_audit_local_test".equals(url))
      throw new IllegalStateException("Only dedicated local audit test database allowed");
    var data = new DriverManagerDataSource(url, "trust_local_test", "");
    admin = new JdbcTemplate(data);
    schema = "audit_test_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE SCHEMA " + schema);
    data.setSchema(schema);
    try (var connection = data.getConnection()) {
      for (String file :
          List.of(
              "001.sql",
              "002-wallet.sql",
              "003-signing-audit.sql",
              "004-identities.sql",
              "005-generic-ingress.sql",
              "006-audit-log.sql",
              "007-audit-optional-text.sql"))
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/" + file));
    }
    db = new JdbcTemplate(data);
    tx = new TransactionTemplate(new DataSourceTransactionManager(data));
    Path ring = root.resolve("keys.json");
    Files.writeString(
        ring,
        Json.write(
            Map.of("keys", Map.of("test-key", Base64.getEncoder().encodeToString(new byte[32])))));
    source =
        new LogSettings.Source(
            "audit-source",
            "0".repeat(64),
            "audit-projection",
            Set.of("submit", "read", "retry", "restore"),
            Set.of(new LogSettings.Scope("TEST_DOMAIN", "TEST_ZONE", "TRUST")));
    config = mock(LogSettings.class);
    when(config.read())
        .thenReturn(
            new LogSettings.Config(
                1,
                true,
                "test-key",
                ring.toString(),
                Set.of("manager"),
                List.of(source),
                new LogSettings.Delivery(false, "", "", Map.of())));
    ingress = mock(EvidenceIngressSettings.class);
    when(ingress.processingSources())
        .thenReturn(
            List.of(
                new EvidenceIngressSettings.Source(
                    "audit-projection",
                    "1".repeat(64),
                    "TEST_ORG",
                    "AUDIT_TEST",
                    true,
                    Set.of("read", "submit", "retry"),
                    Set.of("AUDIT_LOG"))));
    generic = new GenericEventService(db, tx, new AuditService(db));
    storage = mock(EvidenceStorage.class);
    blocks = new HashMap<>();
    when(storage.add(any()))
        .thenAnswer(
            c -> {
              byte[] b = c.getArgument(0);
              String id = "baf" + Json.sha(b);
              blocks.put(id, b);
              return id;
            });
    when(storage.cat(anyString())).thenAnswer(c -> blocks.get(c.getArgument(0)));
    service = new LogEvidenceService(db, tx, new LogCipher(config), generic, ingress, storage);
    worker = new LogArchiveWorker(config, service);
    ops = new LogOperations(db, tx, config);
  }

  @AfterEach
  void cleanup() {
    if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
  }

  LogInput input(String id, String summary) {
    return LogInput.parse(LogContractTest.request(LogContractTest.event(id, summary)));
  }

  @Test
  void acceptanceIsDurableIdempotentAndScopeBounded() {
    var input = input("one", "original");
    assertEquals(202, service.submit(source, input).status());
    assertEquals(200, service.submit(source, input).status());
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM audit_evidence", Integer.class));
    assertEquals(
        409,
        assertThrows(IngressError.class, () -> service.submit(source, input("one", "changed")))
            .status);
    assertEquals(
        "original",
        Json.map((String) service.original(source, input.eventKey(), false).get("canonicalJson"))
            .get("summary"));
    var other = new LogSettings.Source("other", "", "", source.operations(), source.scopes());
    assertEquals(
        404,
        assertThrows(IngressError.class, () -> service.receipt(other, input.eventKey())).status);
    verifyNoInteractions(storage);
  }

  @Test
  void ciphertextOnlyIsArchivedAndProjectionContainsNoPlaintext() throws Exception {
    var input = input("two", "confidential operation");
    service.submit(source, input);
    worker.poll();
    var row = db.queryForMap("SELECT * FROM audit_evidence WHERE event_key=?", input.eventKey());
    assertEquals("STORED", row.get("archive_state"));
    assertNotNull(row.get("chain_event_id"));
    assertEquals(1, blocks.size());
    assertFalse(
        new String(blocks.values().iterator().next(), java.nio.charset.StandardCharsets.UTF_8)
            .contains("confidential operation"));
    assertFalse(
        db.queryForObject("SELECT original_utf8 FROM generic_event_data", String.class)
            .contains("confidential operation"));
    assertEquals(
        input.canonical(), service.original(source, input.eventKey(), true).get("canonicalJson"));
    assertEquals("PENDING", service.receipt(source, input.eventKey()).get("state"));
    // Only a verified DONE+COMMITTED generic task can map to COMMITTED; a bare chain label cannot.
    db.update("UPDATE events SET chain_state='COMMITTED',tx_id='verified-fixture-tx'");
    assertNotEquals("COMMITTED", service.receipt(source, input.eventKey()).get("state"));
    db.update("UPDATE tasks SET state='DONE'");
    assertEquals("COMMITTED", service.receipt(source, input.eventKey()).get("state"));
    worker.poll();
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM events", Integer.class));
  }

  @Test
  void pausesDoNotStopAcceptanceAndFailedArchiveRecoversOnlyByExplicitRetry() throws Exception {
    var input = input("three", "");
    db.update("UPDATE audit_log_settings SET archive_paused=TRUE");
    service.submit(source, input);
    worker.poll();
    verifyNoInteractions(storage);
    db.update("UPDATE audit_log_settings SET archive_paused=FALSE,chain_paused=TRUE");
    worker.poll();
    assertEquals(
        "STORED", db.queryForObject("SELECT archive_state FROM audit_evidence", String.class));
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM events", Integer.class));
    db.update("UPDATE audit_log_settings SET chain_paused=FALSE");
    worker.poll();
    var gate = new LogChainGate(db);
    String event = db.queryForObject("SELECT chain_event_id FROM audit_evidence", String.class);
    assertTrue(gate.allows(event));
    db.update("UPDATE audit_log_settings SET chain_paused=TRUE");
    assertFalse(gate.allows(event));
    assertTrue(gate.allows("unrelated"));
    var second = input("failed", "");
    service.submit(source, second);
    doThrow(new java.io.IOException("offline")).when(storage).add(any());
    db.update("UPDATE audit_evidence SET attempts=9 WHERE event_key=?", second.eventKey());
    worker.poll();
    assertEquals("FAILED", service.receipt(source, second.eventKey()).get("state"));
    service.receipt(source, second.eventKey());
    assertEquals(
        "FAILED",
        db.queryForObject(
            "SELECT archive_state FROM audit_evidence WHERE event_key=?",
            String.class,
            second.eventKey()));
    assertEquals(202, service.retry(source, second.eventKey()).status());
  }

  @Test
  void existingProjectionPauseBlocksSubmissionButAllowsUncertainTransactionReconciliation()
      throws Exception {
    var input = input("pause-projection", "");
    service.submit(source, input);
    worker.poll();
    String id = db.queryForObject("SELECT chain_event_id FROM audit_evidence", String.class);
    db.update(
        "INSERT INTO"
            + " wallets(id,org_id,label,msp_id,key_ref,certificate_pem,fingerprint,subject,not_after)"
            + " VALUES('test-wallet','TEST_ORG','test','TEST','test-key','test',repeat('a',64),'test',CURRENT_TIMESTAMP+INTERVAL"
            + " '1 day')");
    db.update(
        "UPDATE events SET wallet_id='test-wallet',signer_fingerprint=repeat('a',64) WHERE id=?",
        id);
    var ledger = mock(com.bproject.trust.ports.LedgerGateway.class);
    when(ledger.find(anyString(), eq(id))).thenReturn(null);
    var eventService = mock(com.bproject.trust.events.EventService.class);
    when(eventService.get(eq(id), anyString()))
        .thenAnswer(c -> db.queryForMap("SELECT * FROM events WHERE id=?", id));
    when(ingress.permits(anyMap())).thenReturn(true);
    when(ledger.submit(anyMap(), any()))
        .thenAnswer(
            c -> {
              var record = new HashMap<String, Object>(c.getArgument(0));
              record.put("txId", "synthetic-confirmed");
              return record;
            });
    var archive =
        new com.bproject.trust.archiving.ArchiveWorker(
            db,
            eventService,
            mock(com.bproject.trust.evidence.EvidenceService.class),
            storage,
            ledger,
            tx,
            mock(com.bproject.trust.wallet.WalletService.class),
            ingress,
            new GenericEvidenceArchive(db, storage));
    var gate = new LogChainGate(db);
    org.springframework.test.util.ReflectionTestUtils.setField(gate, "settings", config);
    org.springframework.test.util.ReflectionTestUtils.setField(archive, "logChainGate", gate);
    String token = UUID.randomUUID().toString();
    try {
      db.update("UPDATE audit_log_settings SET chain_paused=TRUE");
      lease(id, token);
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(archive, "process", id, token);
      verify(ledger, never()).submit(anyMap(), any());
      assertEquals(
          "READY", db.queryForObject("SELECT state FROM tasks WHERE event_id=?", String.class, id));
      db.update("UPDATE audit_log_settings SET chain_paused=FALSE");
      lease(id, token);
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(archive, "process", id, token);
      verify(ledger, times(1)).submit(anyMap(), any());
      assertEquals(
          "DONE", db.queryForObject("SELECT state FROM tasks WHERE event_id=?", String.class, id));
      // A receipt lost after the transaction was committed may be resolved while paused.
      var committed =
          new HashMap<>(
              com.bproject.trust.archiving.AttestationRecord.record(
                  db.queryForMap("SELECT * FROM events WHERE id=?", id)));
      committed.put("txId", "synthetic-confirmed");
      when(ledger.find(anyString(), eq(id))).thenReturn(committed);
      db.update("UPDATE events SET chain_state='CONFIRMING' WHERE id=?", id);
      db.update("UPDATE audit_log_settings SET chain_paused=TRUE");
      lease(id, token);
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(archive, "process", id, token);
      verify(ledger, times(1)).submit(anyMap(), any());
      assertEquals(
          "DONE", db.queryForObject("SELECT state FROM tasks WHERE event_id=?", String.class, id));
      assertEquals(
          "COMMITTED",
          db.queryForObject("SELECT chain_state FROM events WHERE id=?", String.class, id));
      // A paused ledger outage must not spend the submission failure budget or require manual
      // retry.
      when(ledger.find(anyString(), eq(id))).thenThrow(new java.io.IOException("offline"));
      db.update("UPDATE events SET chain_state='CONFIRMING' WHERE id=?", id);
      db.update("UPDATE tasks SET attempts=9 WHERE event_id=?", id);
      lease(id, token);
      assertDoesNotThrow(
          () ->
              org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                  archive, "process", id, token));
      assertEquals(
          "READY", db.queryForObject("SELECT state FROM tasks WHERE event_id=?", String.class, id));
      assertEquals(
          9, db.queryForObject("SELECT attempts FROM tasks WHERE event_id=?", Integer.class, id));
      verify(ledger, times(1)).submit(anyMap(), any());
    } finally {
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(archive, "close");
    }
  }

  private void lease(String id, String token) {
    db.update(
        "UPDATE tasks SET state='RUNNING',lease_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '60"
            + " seconds',attempts=attempts+1 WHERE event_id=?",
        token,
        id);
  }

  @Test
  void fourPausedOldestProjectionsYieldSlotsToANewerNonLogEvent() throws Exception {
    for (int i = 0; i < 4; i++) service.submit(source, input("paused-oldest-" + i, ""));
    worker.poll();
    var paused = db.queryForList("SELECT chain_event_id FROM audit_evidence", String.class);
    assertEquals(4, paused.size());
    db.update("UPDATE tasks SET next_at=CURRENT_TIMESTAMP-INTERVAL '1 minute'");
    var simulator =
        new EvidenceIngressSettings.Source(
            "sim-test",
            "2".repeat(64),
            "TEST_ORG",
            "SIM_TEST",
            true,
            Set.of("submit", "read"),
            Set.of("SIM_EVENT"));
    var sources = new ArrayList<>(ingress.processingSources());
    sources.add(simulator);
    when(ingress.processingSources()).thenReturn(sources);
    var request =
        Json.write(
            Map.of(
                "schemaVersion",
                1,
                "sourceEventId",
                "ordinary-after-paused",
                "eventType",
                "SIM_EVENT",
                "businessObjectId",
                "ordinary",
                "occurredAt",
                "2026-01-02T03:04:05Z",
                "payload",
                Map.of("value", 1)));
    generic.submit(
        simulator,
        GenericEventInput.parse(request.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    String ordinary =
        db.queryForObject("SELECT id FROM events WHERE source_system='SIM_TEST'", String.class);
    var ledger = mock(com.bproject.trust.ports.LedgerGateway.class);
    when(ingress.permits(anyMap())).thenReturn(true);
    var archive =
        new com.bproject.trust.archiving.ArchiveWorker(
            db,
            mock(com.bproject.trust.events.EventService.class),
            mock(com.bproject.trust.evidence.EvidenceService.class),
            storage,
            ledger,
            tx,
            mock(com.bproject.trust.wallet.WalletService.class),
            ingress,
            new GenericEvidenceArchive(db, storage));
    var gate = new LogChainGate(db);
    org.springframework.test.util.ReflectionTestUtils.setField(gate, "settings", config);
    org.springframework.test.util.ReflectionTestUtils.setField(archive, "logChainGate", gate);
    try {
      db.update("UPDATE audit_log_settings SET chain_paused=TRUE");
      List<String> first =
          org.springframework.test.util.ReflectionTestUtils.invokeMethod(archive, "due", 4);
      assertEquals(new HashSet<>(paused), new HashSet<>(first));
      for (String id : first) {
        String token = UUID.randomUUID().toString();
        lease(id, token);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            archive, "process", id, token);
      }
      List<String> next =
          org.springframework.test.util.ReflectionTestUtils.invokeMethod(archive, "due", 4);
      assertEquals(List.of(ordinary), next);
      assertEquals(
          4,
          db.queryForObject(
              "SELECT COUNT(*) FROM tasks WHERE next_at>CURRENT_TIMESTAMP"
                  + " AND state='READY' AND attempts=0",
              Integer.class));
      verifyNoInteractions(ledger);
    } finally {
      org.springframework.test.util.ReflectionTestUtils.invokeMethod(archive, "close");
    }
  }

  @Test
  void pageActionsAreOwnerBoundAndPersistWithOutbox() {
    var user = auth("user");
    var manager = auth("manager");
    String id = UUID.randomUUID().toString();
    var request = new LogOperations.PageAction(id, "READ", "events", "", "/events", "Read event");
    ops.page(user, request);
    ops.page(user, request);
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM audit_delivery", Integer.class));
    assertEquals(
        404,
        assertThrows(
                IngressError.class,
                () -> ops.result(manager, id, new LogOperations.PageResult("SUCCESS", "")))
            .status);
    ops.result(user, id, new LogOperations.PageResult("SUCCESS", "Client finished"));
    assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM audit_delivery", Integer.class));
    assertEquals(
        403,
        assertThrows(
                IngressError.class,
                () -> ops.controls(user, new LogOperations.Controls(true, true, "pause")))
            .status);
    ops.controls(manager, new LogOperations.Controls(true, true, "maintenance"));
    assertEquals(true, ops.controls(user).get("archivePaused"));
    db.execute("ALTER TABLE audit_delivery ADD CONSTRAINT reject_test CHECK(action<>'NAVIGATE')");
    String failed = UUID.randomUUID().toString();
    assertThrows(
        Exception.class,
        () ->
            ops.page(
                user,
                new LogOperations.PageAction(failed, "NAVIGATE", "events", "", "/events", "")));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_page_action WHERE id=?", Integer.class, failed));
  }

  @Test
  void settingsExposeArchiveBacklogAfterDeliverySucceeded() {
    var input = input("queued", "");
    service.submit(source, input);
    var status = ops.controls(auth("manager"));
    assertEquals(1L, status.get("archivePendingCount"));
    assertEquals(1L, status.get("pendingCount"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ops.controls(auth("manager"), new LogOperations.Controls(null, null, "missing flags")));
  }

  @Test
  void loginPersistsAnonymousAndVerifiedEventsWithSqlNullOptionalFields() {
    var identity = mock(com.bproject.trust.ports.PlatformIdentity.class);
    when(identity.login(anyString(), anyString())).thenReturn("synthetic-login-token");
    when(identity.current(anyString()))
        .thenReturn(
            new com.bproject.trust.ports.PlatformIdentity.User(
                "verified-user", "user", List.of("ORG"), Set.of()));
    var settings = mock(com.bproject.trust.identity.IntegrationSettings.class);
    when(settings.read())
        .thenReturn(
            new com.bproject.trust.identity.IntegrationSettings.Settings(
                "", "real", false, Map.of("ORG", "TEST_ORG"), List.of()));
    var controller =
        new com.bproject.trust.identity.IamLoginController(
            identity, mock(com.bproject.trust.adapters.iam.SimulatedIamClient.class), settings);
    org.springframework.test.util.ReflectionTestUtils.setField(controller, "operationLogs", ops);
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    assertEquals(
        Map.of("ok", true),
        controller.login(
            new com.bproject.trust.identity.IamLoginController.Login(
                "user", "synthetic-test", "TEST_ORG"),
            request,
            new org.springframework.mock.web.MockHttpServletResponse()));
    assertEquals("verified-user", request.getSession(false).getAttribute("IAM_USER"));
    var anonymous = db.queryForMap("SELECT * FROM audit_delivery WHERE outcome='STARTED'");
    assertNull(anonymous.get("actor"));
    assertNull(anonymous.get("object_id"));
    assertNull(anonymous.get("menu_id"));
    assertNull(anonymous.get("summary"));
    assertEquals("UNVERIFIED_SECURITY", anonymous.get("org_id"));
    var verified = db.queryForMap("SELECT * FROM audit_delivery WHERE outcome='SUCCESS'");
    assertEquals("verified-user", verified.get("actor"));
    assertEquals("TEST_ORG", verified.get("org_id"));
  }

  @Test
  void deliveryRejectsFalseAcknowledgementAndExplicitRetryKeepsOriginalEvent() throws Exception {
    Path credential = root.resolve("audit-client-token.txt");
    Files.writeString(credential, "synthetic-outbound-audit-test-credential");
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new ArrayList<String>();
    var valid = new java.util.concurrent.atomic.AtomicBoolean(false);
    server.createContext(
        "/events",
        exchange -> {
          String text =
              new String(
                  exchange.getRequestBody().readAllBytes(),
                  java.nio.charset.StandardCharsets.UTF_8);
          requests.add(text);
          var e = Json.map(text);
          byte[] body =
              Json.bytes(
                  Map.of(
                      "eventId",
                      e.get("eventId"),
                      "appCode",
                      "TRUST",
                      "stored",
                      true,
                      "payloadHash",
                      valid.get() ? LogInput.hash(text) : "wrong"));
          exchange.sendResponseHeaders(202, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    var c = config.read();
    when(config.read())
        .thenReturn(
            new LogSettings.Config(
                c.schemaVersion(),
                c.enabled(),
                c.activeKeyId(),
                c.keyRingFile(),
                c.managerUserIds(),
                c.sources(),
                new LogSettings.Delivery(
                    true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/events",
                    credential.toString(),
                    Map.of(
                        "TEST_ORG", new LogSettings.Scope("TEST_DOMAIN", "TEST_ZONE", "TRUST")))));
    try {
      ops.enqueue("TEST_ORG", "", "READ", "", "SUCCESS", "REQUEST", "", "GET", "/events", "", "");
      var delivery = new LogDeliveryWorker(ops);
      delivery.poll();
      var row = db.queryForMap("SELECT * FROM audit_delivery");
      assertEquals("FAILED", row.get("state"));
      assertEquals("AUDIT_INVALID_RECEIPT", row.get("last_error"));
      delivery.poll();
      assertEquals(1, requests.size());
      ops.retryDelivery(
          auth("manager"),
          (String) row.get("id"),
          new LogOperations.RetryDelivery("Correct endpoint"));
      valid.set(true);
      delivery.poll();
      assertEquals(
          "DELIVERED",
          db.queryForObject(
              "SELECT state FROM audit_delivery WHERE id=?", String.class, row.get("id")));
      assertEquals(requests.get(0), requests.get(1));
      var delivered = Json.map(requests.get(0));
      assertEquals("", delivered.get("userId"));
      assertEquals("ANONYMOUS", delivered.get("actorTrust"));
      for (String field : List.of("objectId", "menuId", "summary", "traceId"))
        assertEquals("", delivered.get(field));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void verificationRequiresMatchingArchiveAndLedgerAndRejectsTampering() throws Exception {
    var input = input("verify", "");
    service.submit(source, input);
    worker.poll();
    String id = db.queryForObject("SELECT chain_event_id FROM audit_evidence", String.class),
        lease = UUID.randomUUID().toString();
    db.update(
        "UPDATE tasks SET state='RUNNING',lease_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '60"
            + " seconds'",
        lease);
    var archive = new GenericEvidenceArchive(db, storage);
    var event = db.queryForMap("SELECT * FROM events WHERE id=?", id);
    String manifest = archive.manifest(event, lease);
    String manifestCid = storage.add(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    db.update(
        "UPDATE events SET"
            + " manifest_json=?,manifest_cid=?,manifest_sha256=?,file_state='STORED',chain_state='COMMITTED',tx_id='fixture-tx'"
            + " WHERE id=?",
        manifest,
        manifestCid,
        LogInput.hash(manifest),
        id);
    db.update("UPDATE tasks SET state='DONE'");
    event = db.queryForMap("SELECT * FROM events WHERE id=?", id);
    var record =
        new TreeMap<String, Object>(com.bproject.trust.archiving.AttestationRecord.record(event));
    record.put("txId", "fixture-tx");
    var ledger = mock(com.bproject.trust.ports.LedgerGateway.class);
    when(ledger.find(anyString(), eq(id))).thenReturn(record);
    var verification = new LogEvidenceVerification(service, archive, ledger);
    assertEquals("MATCH", verification.verify(source, input.eventKey()).get("ledgerRegistration"));
    record.put("eventSha256", "tampered");
    assertEquals(
        503,
        assertThrows(IngressError.class, () -> verification.verify(source, input.eventKey()))
            .status);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "TRUST_AUDIT_HANDOFF_ZIP", matches = ".+")
  void originalAuditChainWorkerAcceptsOurReceiptsAndDoesNotResubmitAcceptedEvents()
      throws Exception {
    Path reference =
        Path.of("../.local/closeout-20261018/reference-test").toAbsolutePath().normalize();
    Files.createDirectories(reference);
    Path classes = Files.createTempDirectory(reference, "compiled-");
    var javaFiles = new ArrayList<String>();
    try (var zip = new java.util.zip.ZipFile(System.getenv("TRUST_AUDIT_HANDOFF_ZIP"))) {
      for (String name :
          List.of(
              "AUDIT/client/src/main/java/com/bproject/audit/client/CanonicalJson.java",
              "AUDIT/client/src/main/java/com/bproject/audit/client/LogEvent.java",
              "AUDIT/service/src/main/java/com/bproject/audit/service/ChainWorker.java")) {
        var entry =
            zip.stream()
                .filter(e -> e.getName().replace('\\', '/').equals(name))
                .findFirst()
                .orElseThrow();
        Path file = classes.resolve(name.substring(name.lastIndexOf('/') + 1));
        try (var stream = zip.getInputStream(entry)) {
          Files.copy(stream, file);
        }
        javaFiles.add(file.toString());
      }
    }
    Path store = classes.resolve("LogStore.java");
    Files.writeString(
        store,
        "package com.bproject.audit.service; public class LogStore { public final"
            + " org.springframework.jdbc.core.JdbcTemplate db; public"
            + " LogStore(org.springframework.jdbc.core.JdbcTemplate db){this.db=db;} }");
    javaFiles.add(store.toString());
    var arguments =
        new ArrayList<>(
            List.of(
                "-encoding",
                "UTF-8",
                "-cp",
                System.getProperty("java.class.path"),
                "-d",
                classes.toString()));
    arguments.addAll(javaFiles);
    assertEquals(
        0,
        javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, arguments.toArray(String[]::new)));
    db.execute(
        "CREATE TABLE log_event(id VARCHAR(64) PRIMARY KEY,event_id VARCHAR(128),payload"
            + " TEXT,payload_hash VARCHAR(64),received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    db.execute(
        "CREATE TABLE log_chain(id VARCHAR(64) PRIMARY KEY,state VARCHAR(30),attempts INTEGER"
            + " DEFAULT 0,next_attempt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,lease_id"
            + " VARCHAR(36),last_error TEXT,transaction_id TEXT)");
    var input = input("original-client", "");
    db.update(
        "INSERT INTO log_event(id,event_id,payload,payload_hash) VALUES(?,?,?,?)",
        input.eventKey(),
        input.eventId(),
        input.canonical(),
        input.payloadHash());
    db.update("INSERT INTO log_chain(id,state) VALUES(?,'PENDING')", input.eventKey());
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new LogIngressController(service))
            .setControllerAdvice(new com.bproject.trust.shared.web.ErrorHandler())
            .build();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var posts = new java.util.concurrent.atomic.AtomicInteger();
    String token = "synthetic-original-audit-client-credential";
    Path tokenFile = classes.resolve("token.txt");
    Files.writeString(tokenFile, token);
    server.createContext(
        LogSecurity.BASE,
        exchange -> {
          try {
            if (!("Bearer " + token)
                .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              exchange.sendResponseHeaders(401, -1);
              return;
            }
            String path = exchange.getRequestURI().getPath();
            boolean post = exchange.getRequestMethod().equals("POST");
            if (post) posts.incrementAndGet();
            var request =
                post
                    ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
            var response =
                mvc.perform(
                        request
                            .requestAttr("LOG_SOURCE", source)
                            .contentType("application/json")
                            .content(exchange.getRequestBody().readAllBytes()))
                    .andReturn()
                    .getResponse();
            byte[] bytes = response.getContentAsByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.getStatus(), bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (Exception error) {
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try (var loader =
        new java.net.URLClassLoader(
            new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
      Class<?> storeClass = loader.loadClass("com.bproject.audit.service.LogStore"),
          workerClass = loader.loadClass("com.bproject.audit.service.ChainWorker");
      Object original =
          workerClass.getConstructors()[0].newInstance(
              storeClass.getConstructor(JdbcTemplate.class).newInstance(db),
              Json.MAPPER,
              true,
              "http://127.0.0.1:" + server.getAddress().getPort() + LogSecurity.BASE,
              tokenFile.toString());
      workerClass.getMethod("runBatch").invoke(original);
      assertEquals("ACCEPTED", db.queryForObject("SELECT state FROM log_chain", String.class));
      assertEquals(1, posts.get());
      db.update("UPDATE log_chain SET next_attempt=CURRENT_TIMESTAMP");
      workerClass.getMethod("runBatch").invoke(original);
      assertEquals(1, posts.get());
      worker.poll();
      db.update("UPDATE events SET chain_state='COMMITTED',tx_id='synthetic-verified-transaction'");
      db.update("UPDATE tasks SET state='DONE'");
      db.update("UPDATE log_chain SET next_attempt=CURRENT_TIMESTAMP");
      workerClass.getMethod("runBatch").invoke(original);
      assertEquals("COMMITTED", db.queryForObject("SELECT state FROM log_chain", String.class));
      assertEquals(1, posts.get());
    } finally {
      server.stop(0);
      try (var files = Files.walk(classes)) {
        for (Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
      }
    }
  }

  static UsernamePasswordAuthenticationToken auth(String id) {
    return UsernamePasswordAuthenticationToken.authenticated(
        new PlatformPrincipal(id, id, "TEST_ORG"), null, List.of());
  }
}
