package com.bproject.trust.provisioning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.ports.*;
import com.bproject.trust.shared.web.ApiError;
import com.bproject.trust.wallet.WalletKeyStore;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real openGauss concurrency/commit tests. CA/Gateway faults are explicitly injected here. */
@EnabledIfEnvironmentVariable(named = "TRUST_IDENTITY_DB_TEST", matches = "1")
class IdentityDatabaseIntegrationTest {
  JdbcTemplate db;
  TransactionTemplate tx;
  FabricIdentityService service;
  IdentityProviderSettings.Client caller;
  String user;

  @BeforeEach
  void setup() {
    String url = System.getenv("TRUST_IDENTITY_DB_URL");
    assertNotNull(url);
    assertTrue(
        url.matches("jdbc:opengauss://127\\.0\\.0\\.1:25432/trust_iam_[a-z0-9_]+_test"),
        "Only a new dedicated test database is permitted");
    var ds =
        new DriverManagerDataSource(
            url,
            System.getenv("TRUST_IDENTITY_DB_USER"),
            System.getenv("TRUST_IDENTITY_DB_PASSWORD"));
    ds.setConnectionProperties(
        new java.util.Properties() {
          {
            setProperty("currentSchema", "trust_data");
          }
        });
    db = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    service = new FabricIdentityService(db, tx);
    user = "fixture-" + UUID.randomUUID();
    caller =
        new IdentityProviderSettings.Client(
            "test-" + UUID.randomUUID(),
            "unused",
            "test-issuer",
            "test-tenant",
            Set.of("ORG1", "ORG2"),
            Set.of("create", "read", "disable", "rotate", "revoke", "retry"));
  }

  @AfterEach
  void quarantineOnlyThisFixture() {
    if (db == null || caller == null || user == null) return;
    tx.executeWithoutResult(t -> {
      for (var row : db.queryForList("SELECT id,revision FROM fabric_identity WHERE issuer=? AND tenant=? AND user_id=? FOR UPDATE", caller.issuer(), caller.tenant(), user)) {
        String id = (String) row.get("id");
        if (db.update("UPDATE fabric_identity SET status='QUARANTINED',last_error='TEST_RUN_COMPLETE' WHERE id=? AND lease_token IS NULL", id) != 1)
          throw new IllegalStateException("Fixture lease remains active");
        service.audit(id, ((Number) row.get("revision")).longValue(), "isolated-test", "TEST_RECORD_QUARANTINED", "Completed fixture; never eligible for automatic provisioning");
      }
    });
  }

  FabricIdentityService.Command command(long revision, String org, String desired, String action) {
    return new FabricIdentityService.Command(
        caller.issuer(), caller.tenant(), user, org, revision, desired, action);
  }

  @Test
  void concurrentRetriesReturnOneIdentityAndRejectSameKeyDifferentPayload() throws Exception {
    String key = "request-" + UUID.randomUUID();
    var pool = Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<Callable<String>>();
      for (int n = 0; n < 12; n++)
        jobs.add(
            () ->
                (String)
                    service
                        .provision(caller, key, command(1, "ORG1", "ACTIVE", "SYNC"))
                        .get("identityId"));
      var ids = new HashSet<String>();
      for (var result : pool.invokeAll(jobs)) ids.add(result.get());
      assertEquals(1, ids.size());
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM fabric_identity WHERE issuer=? AND tenant=? AND user_id=?",
              Integer.class,
              caller.issuer(),
              caller.tenant(),
              user));
      assertEquals(
          409,
          assertThrows(
                  ApiError.class,
                  () -> service.provision(caller, key, command(1, "ORG2", "ACTIVE", "SYNC")))
              .status);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void disableDuringIssuedCertificateProbeCannotBeReactivatedByStaleWorker() throws Exception {
    var allocated =
        service.provision(
            caller, "request-" + UUID.randomUUID(), command(1, "ORG1", "ACTIVE", "SYNC"));
    String id = (String) allocated.get("identityId");
    var settings = mock(IdentityProviderSettings.class);
    var org =
        new IdentityProviderSettings.Organization(
            "Org1MSP",
            "test-ca",
            "org1.business",
            Map.of(),
            "https://unused.invalid",
            "/unused",
            "/unused",
            "/unused");
    when(settings.read())
        .thenReturn(
            new IdentityProviderSettings.Settings(
                "unused", "1.5.22", "unused", List.of(caller), Map.of("ORG1", org)));
    var cert = mock(java.security.cert.X509Certificate.class);
    when(cert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 60000));
    when(cert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 864000000));
    var ca = mock(IdentityAuthority.class);
    when(ca.issue(any(), anyString(), anyString(), anyMap()))
        .thenReturn(new WalletKeyStore.Material(cert, null, "TEST_CERTIFICATE", "a".repeat(64)));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    IdentityNetwork network =
        (m, o, u, material) -> {
          entered.countDown();
          assertTrue(release.await(10, TimeUnit.SECONDS));
          return Map.of("fixture", "fault-injection");
        };
    var worker = new IdentityWorker(db, tx, settings, ca, network, service);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var pending = executor.submit(() -> worker.process(id));
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      service.provision(
          caller, "disable-" + UUID.randomUUID(), command(2, "ORG1", "DISABLED", "SYNC"));
      release.countDown();
      pending.get(10, TimeUnit.SECONDS);
      var after = service.get(caller, id);
      assertEquals("DISABLED", after.get("status"));
      assertEquals(0, ((Number) after.get("certificateVersion")).intValue());
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM fabric_certificate WHERE identity_id=?", Integer.class, id));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void committedTaskSurvivesNewServiceInstanceAndOldRevisionsAreRejected() {
    String key = "restart-" + UUID.randomUUID();
    String id =
        (String)
            service.provision(caller, key, command(1, "ORG1", "ACTIVE", "SYNC")).get("identityId");
    var restarted =
        new FabricIdentityService(db, new TransactionTemplate(tx.getTransactionManager()));
    assertEquals(
        id,
        restarted.provision(caller, key, command(1, "ORG1", "ACTIVE", "SYNC")).get("identityId"));
    restarted.provision(
        caller, "delete-" + UUID.randomUUID(), command(3, "ORG1", "DELETED", "SYNC"));
    assertThrows(
        ApiError.class,
        () ->
            service.provision(
                caller, "late-" + UUID.randomUUID(), command(2, "ORG1", "ACTIVE", "SYNC")));
    assertThrows(
        ApiError.class,
        () ->
            service.provision(
                caller, "restore-" + UUID.randomUUID(), command(4, "ORG1", "ACTIVE", "SYNC")));
  }

  @Test
  void caAndGatewayFaultsRecoverWithoutAllocatingDuplicateVersion() throws Exception {
    String id =
        (String)
            service
                .provision(
                    caller, "fault-" + UUID.randomUUID(), command(1, "ORG1", "ACTIVE", "SYNC"))
                .get("identityId");
    var settings = mock(IdentityProviderSettings.class);
    var org =
        new IdentityProviderSettings.Organization(
            "Org1MSP",
            "test-ca",
            "org1.business",
            Map.of(),
            "https://unused.invalid",
            "/unused",
            "/unused",
            "/unused");
    when(settings.read())
        .thenReturn(
            new IdentityProviderSettings.Settings(
                "unused", "1.5.22", "unused", List.of(caller), Map.of("ORG1", org)));
    var cert = mock(java.security.cert.X509Certificate.class);
    when(cert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 60000));
    when(cert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 864000000));
    var material = new WalletKeyStore.Material(cert, null, "FAULT_FIXTURE_CERT", "a".repeat(64));
    var ca = mock(IdentityAuthority.class);
    when(ca.issue(any(), anyString(), anyString(), anyMap()))
        .thenThrow(new java.io.IOException("injected response loss"))
        .thenReturn(material);
    var network = mock(IdentityNetwork.class);
    when(network.verify(anyString(), anyString(), anyString(), any()))
        .thenThrow(new java.io.IOException("injected Gateway outage"))
        .thenReturn(Map.of("fixture", "injected"));
    new IdentityWorker(db, tx, settings, ca, network, service).process(id);
    assertEquals("RETRY", service.get(caller, id).get("status"));
    new IdentityWorker(db, tx, settings, ca, network, service).process(id);
    assertEquals("RETRY", service.get(caller, id).get("status"));
    assertEquals(
        "ISSUED",
        db.queryForObject(
            "SELECT state FROM fabric_certificate WHERE identity_id=?", String.class, id));
    new IdentityWorker(db, tx, settings, ca, network, service).process(id);
    assertEquals("READY", service.get(caller, id).get("status"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM fabric_certificate WHERE identity_id=?", Integer.class, id));
  }

  @Test
  void lifecycleReplayDoesNotAdvanceRevisionTwice() {
    String id =
        (String)
            service
                .provision(
                    caller, "create-" + UUID.randomUUID(), command(1, "ORG1", "ACTIVE", "SYNC"))
                .get("identityId");
    String key = "disable-" + UUID.randomUUID();
    service.lifecycle(caller, id, key, 1, "disable");
    service.lifecycle(caller, id, key, 1, "disable");
    assertEquals(2L, ((Number) service.get(caller, id).get("revision")).longValue());
    assertThrows(ApiError.class, () -> service.lifecycle(caller, id, key, 1, "rotate"));
  }
}
