package com.bproject.trust.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bproject.trust.events.*;
import com.bproject.trust.ports.*;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import com.bproject.trust.wallet.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@org.springframework.context.annotation.Import(com.bproject.trust.support.NoBackgroundScheduling.class)
@SpringBootTest(properties = {"trust.isolation-enabled=false", "spring.liquibase.enabled=false"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TRUST_DB_INTEGRATION", matches = "1")
class WalletDatabaseIntegrationTest {
  @MockitoBean EvidenceStorage ipfs;
  @MockitoBean LedgerGateway ledger;
  @MockitoBean WalletKeyStore keys;
  @Autowired WalletService wallets;
  @Autowired JdbcTemplate db;
  @Autowired EventService events;
  @Autowired MockMvc mvc;
  @Autowired TransactionTemplate tx;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) throws Exception {
    Path root = Path.of(System.getenv("TRUST_ROOT"), "runtime/wallet-integration");
    Files.createDirectories(root);
    p.add("trust.root", () -> root.toString());
    String url = System.getenv("TRUST_TEST_DB_URL");
    if (url == null || !url.matches("jdbc:opengauss://[^/]+/trust_iam_dev_test"))
      throw new IllegalStateException(
          "TRUST_TEST_DB_URL must explicitly select trust_iam_dev_test");
    p.add("spring.datasource.url", () -> url);
    p.add("trust.archiving.enabled", () -> "false");
  }

  String org, ref, fingerprint;

  @BeforeEach
  void setup() throws Exception {
    try (var connection = db.getDataSource().getConnection()) {
      assertTrue(connection.getMetaData().getURL().endsWith("/trust_iam_dev_test"));
    }
    org = "WALLET-" + UUID.randomUUID().toString().substring(0, 8);
    ref = "key-" + UUID.randomUUID();
    fingerprint = Json.sha(ref.getBytes());
    var cert = mock(X509Certificate.class);
    when(cert.getSubjectX500Principal()).thenReturn(new X500Principal("CN=test"));
    when(cert.getNotAfter()).thenReturn(Date.from(Instant.now().plusSeconds(86400)));
    when(keys.load(ref))
        .thenReturn(new WalletKeyStore.Material(cert, null, "TEST CERTIFICATE", fingerprint));
    when(ipfs.add(any())).thenThrow(new IllegalStateException("isolated test dependency"));
  }

  @AfterEach
  void stopPendingFixtureTasks() {
    db.update(
        "UPDATE tasks SET state='FAILED',lease_token=NULL,lease_until=NULL WHERE event_id IN(SELECT"
            + " id FROM events WHERE org_id=?)",
        org);
  }

  String register() {
    var r =
        wallets.propose(
            org,
            "iam:alice",
            "REGISTER",
            new WalletService.Change("test", "Org1MSP", ref, null, null, null),
            "test registration");
    String id = (String) r.get("id");
    wallets.review(org, "iam:bob", id, true);
    return id;
  }

  String bind(String wallet, int revision) {
    var r =
        wallets.propose(
            org,
            "iam:alice",
            "BIND",
            new WalletService.Change(null, null, null, wallet, "WMS", revision),
            "test binding");
    return (String) r.get("id");
  }

  @Test
  void reviewRequiresDifferentActorAndBindingDetectsStaleVersion() {
    String id = register(), first = bind(id, 0), stale = bind(id, 0);
    assertEquals(
        403,
        assertThrows(ApiError.class, () -> wallets.review(org, "iam:alice", first, true)).status);
    wallets.review(org, "iam:bob", first, true);
    assertEquals(
        409,
        assertThrows(ApiError.class, () -> wallets.review(org, "iam:bob", stale, true)).status);
    assertEquals(
        "PENDING",
        db.queryForObject("SELECT state FROM wallet_requests WHERE id=?", String.class, stale));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT revision FROM wallet_bindings WHERE org_id=?", Integer.class, org));
    assertEquals(404, assertThrows(ApiError.class, () -> wallets.wallet(id, "OTHER")).status);
  }

  @Test
  void disableBlocksNewSigningAndKeepsHistoricalVersion() {
    String id = register();
    wallets.review(org, "iam:bob", bind(id, 0), true);
    EventInput input =
        new EventInput(
            "WMS",
            "RECEIPT-1",
            "WAREHOUSE_IN",
            "order",
            "batch",
            Instant.now(),
            null,
            null,
            null,
            null,
            BigDecimal.TEN,
            "t",
            null,
            null,
            null,
            null,
            null);
    String event = (String) events.submit(input, org, "source:wms-test", null).get("id");
    wallets.prepare(event);
    assertEquals(id, events.get(event, org).get("wallet_id"));
    var r =
        wallets.propose(
            org,
            "iam:alice",
            "DISABLE",
            new WalletService.Change(null, null, null, id, null, null),
            "disable test");
    wallets.review(org, "iam:bob", (String) r.get("id"), true);
    assertEquals(409, assertThrows(ApiError.class, () -> wallets.prepare(event)).status);
    assertEquals(fingerprint, events.get(event, org).get("signer_fingerprint"));
  }

  @Test
  void auditFailureRollsBackApproval() {
    var r =
        wallets.propose(
            org,
            "iam:alice",
            "REGISTER",
            new WalletService.Change("test", "Org1MSP", ref, null, null, null),
            "test");
    String id = (String) r.get("id");
    assertThrows(Exception.class, () -> wallets.review(org, "x".repeat(81), id, true));
    assertEquals(
        0, db.queryForObject("SELECT COUNT(*) FROM wallets WHERE id=?", Integer.class, id));
    assertEquals(
        "PENDING",
        db.queryForObject("SELECT state FROM wallet_requests WHERE id=?", String.class, id));
  }

  @Test
  void replacementCertificateCannotReuseApprovedWalletVersion() throws Exception {
    String id = register();
    var previous = keys.load(ref);
    when(keys.load(ref))
        .thenReturn(
            new WalletKeyStore.Material(
                previous.certificate(),
                previous.key(),
                previous.pem(),
                Json.sha("replacement-certificate".getBytes())));
    assertEquals(409, assertThrows(ApiError.class, () -> wallets.active(id, org)).status);
    assertEquals(fingerprint, wallets.wallet(id, org).get("fingerprint"));
  }

  String signingEvent(String number) {
    return (String)
        events
            .submit(
                new EventInput(
                    "WMS",
                    number,
                    "WAREHOUSE_IN",
                    "order",
                    "batch",
                    Instant.now(),
                    null,
                    null,
                    null,
                    null,
                    BigDecimal.TEN,
                    "t",
                    null,
                    null,
                    null,
                    null,
                    Map.of("operator", "warehouse-operator-7")),
                org,
                "source:wms-test",
                null)
            .get("id");
  }

  @Test
  void signingSnapshotSurvivesRebindingAndRecordsRecoveryResult() {
    String id = register(), binding = bind(id, 0);
    wallets.review(org, "iam:bob", binding, true);
    String event = signingEvent("SNAPSHOT-1");
    wallets.prepare(event);
    wallets.prepared(event, "first-" + event);
    String before =
        db.queryForObject(
            "SELECT context_json FROM signing_attempts WHERE event_id=?", String.class, event);
    var context = Json.map(before);
    assertEquals(1, context.get("bindingRevision"));
    assertEquals("source:wms-test", context.get("submittedBy"));
    assertTrue(before.contains("warehouse-operator-7"));
    assertEquals(binding, ((Map<?, ?>) context.get("bindingApproval")).get("requestId"));
    assertEquals("iam:bob", ((Map<?, ?>) context.get("registrationApproval")).get("reviewedBy"));
    String replacement = bind(id, 1);
    wallets.review(org, "iam:carol", replacement, true);
    assertEquals(409, assertThrows(ApiError.class, () -> wallets.policy(org, binding, 1)).status);
    wallets.prepare(event);
    wallets.uncertain(event, "commit timeout");
    assertEquals(
        "UNKNOWN",
        db.queryForObject(
            "SELECT state FROM signing_attempts WHERE event_id=?", String.class, event));
    wallets.prepared(event, "second-" + event);
    assertEquals(
        List.of(before, before),
        db.queryForList(
            "SELECT context_json FROM signing_attempts WHERE event_id=? ORDER BY tx_id",
            String.class,
            event));
    tx.executeWithoutResult(
        s ->
            wallets.committed(
                event,
                Map.of(
                    "txId",
                    "first-" + event,
                    "ledgerIdentity",
                    "verified-identity",
                    "blockNumber",
                    42L)));
    assertEquals(
        "COMMITTED",
        db.queryForObject(
            "SELECT state FROM signing_attempts WHERE tx_id=?", String.class, "first-" + event));
    assertEquals(
        "SUPERSEDED",
        db.queryForObject(
            "SELECT state FROM signing_attempts WHERE tx_id=?", String.class, "second-" + event));
    assertEquals(
        42L,
        db.queryForObject(
            "SELECT block_number FROM signing_attempts WHERE tx_id=?",
            Long.class,
            "first-" + event));
    assertEquals(
        "commit timeout",
        db.queryForObject(
            "SELECT last_error FROM signing_attempts WHERE tx_id=?",
            String.class,
            "first-" + event));
  }

  @Test
  void invalidReceiptRemainsDistinctFromUnknownTransportFailure() {
    String id = register();
    wallets.review(org, "iam:bob", bind(id, 0), true);
    String event = signingEvent("INVALID-1");
    wallets.prepare(event);
    wallets.prepared(event, "invalid-" + event);
    wallets.rejected(event, "invalid-" + event, 40L, "MVCC_READ_CONFLICT");
    wallets.uncertain(event, "worker failure");
    wallets.prepared(event, "retry-" + event);
    wallets.committed(event, Map.of("txId", "retry-" + event, "blockNumber", 41L));
    assertEquals(
        "INVALID",
        db.queryForObject(
            "SELECT state FROM signing_attempts WHERE tx_id=?", String.class, "invalid-" + event));
    assertEquals(
        "MVCC_READ_CONFLICT",
        db.queryForObject(
            "SELECT last_error FROM signing_attempts WHERE tx_id=?",
            String.class,
            "invalid-" + event));
    assertEquals(
        40L,
        db.queryForObject(
            "SELECT block_number FROM signing_attempts WHERE tx_id=?",
            Long.class,
            "invalid-" + event));
  }

  @Test
  void preparedAttemptAndAuditRollBackWhenLeaseTransactionFails() {
    String id = register();
    wallets.review(org, "iam:bob", bind(id, 0), true);
    String event = signingEvent("ROLLBACK-1");
    wallets.prepare(event);
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.executeWithoutResult(
                s -> {
                  wallets.prepared(event, "rolled-back-" + event);
                  throw new IllegalStateException("lost lease");
                }));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM signing_attempts WHERE event_id=?", Integer.class, event));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE object_id=? AND action='SIGN_PREPARED'",
            Integer.class,
            event));
  }

  @Test
  void concurrentBindingApprovalsHaveOneWinnerAndRollbackTheLoser() throws Exception {
    String id = register(), a = bind(id, 0), b = bind(id, 0);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> approveA =
          () -> {
            start.await();
            return reviewStatus(a);
          };
      Callable<Integer> approveB =
          () -> {
            start.await();
            return reviewStatus(b);
          };
      Future<Integer> first = pool.submit(approveA), second = pool.submit(approveB);
      start.countDown();
      var results =
          new ArrayList<>(
              List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
      Collections.sort(results);
      assertEquals(List.of(200, 409), results);
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM wallet_requests WHERE id IN (?,?) AND state='APPROVED'",
              Integer.class,
              a,
              b));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM wallet_requests WHERE id IN (?,?) AND state='PENDING'",
              Integer.class,
              a,
              b));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM audit_log WHERE object_id IN (?,?) AND action='WALLET_REVIEW'",
              Integer.class,
              a,
              b));
    } finally {
      pool.shutdownNow();
    }
  }

  int reviewStatus(String id) {
    try {
      wallets.review(org, "iam:bob", id, true);
      return 200;
    } catch (ApiError failure) {
      return failure.status;
    }
  }

  @Test
  void localAdministratorCannotManageWallets() throws Exception {
    mvc.perform(get("/api/v1/wallets").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/integrations/wms/events")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnauthorized());
  }
}
