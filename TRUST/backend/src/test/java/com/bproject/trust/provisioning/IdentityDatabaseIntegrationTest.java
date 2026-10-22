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
    com.bproject.trust.support.TestDatabase.require(url);
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
    tx.executeWithoutResult(
        t -> {
          for (var row :
              db.queryForList(
                  "SELECT id,revision FROM fabric_identity_read WHERE issuer=? AND tenant=? AND"
                      + " user_id=? FOR UPDATE",
                  caller.issuer(),
                  caller.tenant(),
                  user)) {
            String id = (String) row.get("id");
            if (db.update(
                    "UPDATE fabric_identity_read SET"
                        + " status='QUARANTINED',last_error='TEST_RUN_COMPLETE' WHERE id=? AND"
                        + " lease_token IS NULL",
                    id)
                != 1) throw new IllegalStateException("Fixture lease remains active");
            service.audit(
                id,
                ((Number) row.get("revision")).longValue(),
                "isolated-test",
                "TEST_RECORD_QUARANTINED",
                "Completed fixture; never eligible for automatic provisioning");
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
              "SELECT COUNT(*) FROM fabric_identity_read WHERE issuer=? AND tenant=? AND user_id=?",
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
              "SELECT COUNT(*) FROM fabric_certificate_read WHERE identity_id=?",
              Integer.class,
              id));
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
            "SELECT state FROM fabric_certificate_read WHERE identity_id=?", String.class, id));
    new IdentityWorker(db, tx, settings, ca, network, service).process(id);
    assertEquals("READY", service.get(caller, id).get("status"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM fabric_certificate_read WHERE identity_id=?", Integer.class, id));
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

  @Test
  void deletedRevocationAllowsAuthorizedRetryButKeepsPermissionAndTerminalStateChecks() {
    String id =
        (String)
            service
                .provision(
                    caller, "delete-" + UUID.randomUUID(), command(1, "ORG1", "DELETED", "SYNC"))
                .get("identityId");
    var limited =
        new IdentityProviderSettings.Client(
            caller.id(),
            caller.tokenSha256(),
            caller.issuer(),
            caller.tenant(),
            caller.orgs(),
            Set.of("read", "retry"));
    var before = db.queryForMap("SELECT * FROM fabric_identity_read WHERE id=?", id);
    assertEquals(
        403,
        assertThrows(
                ApiError.class,
                () -> service.lifecycle(limited, id, "forbidden-" + UUID.randomUUID(), 1, "retry"))
            .status);
    assertEquals(before, db.queryForMap("SELECT * FROM fabric_identity_read WHERE id=?", id));
    String key = "retry-deleted-" + UUID.randomUUID();
    service.lifecycle(caller, id, key, 1, "retry");
    service.lifecycle(caller, id, key, 1, "retry");
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM identity_audit WHERE identity_id=? AND action='RETRY'",
            Integer.class,
            id));
    assertEquals("DELETED", service.get(caller, id).get("desiredState"));
    assertEquals(
        409,
        assertThrows(
                ApiError.class,
                () ->
                    service.lifecycle(
                        caller, id, "rotate-deleted-" + UUID.randomUUID(), 1, "rotate"))
            .status);
    db.update("UPDATE fabric_identity_read SET status='CRL_PENDING' WHERE id=?", id);
    assertEquals(
        409,
        assertThrows(
                ApiError.class,
                () -> service.lifecycle(caller, id, "done-retry-" + UUID.randomUUID(), 1, "retry"))
            .status);
  }

  record RevocationFixture(
      String id, IdentityProviderSettings settings, IdentityAuthority ca, IdentityWorker worker) {}

  RevocationFixture issuedFixture() throws Exception {
    String id =
        (String)
            service
                .provision(
                    caller, "initial-" + UUID.randomUUID(), command(1, "ORG1", "ACTIVE", "SYNC"))
                .get("identityId");
    var settings = mock(IdentityProviderSettings.class);
    var organization =
        new IdentityProviderSettings.Organization(
            "Org1MSP",
            "test-ca",
            "business",
            Map.of(),
            "https://unused.invalid",
            "/unused",
            "/unused",
            "/unused");
    when(settings.read())
        .thenReturn(
            new IdentityProviderSettings.Settings(
                "unused", "1.5.22", "unused", List.of(caller), Map.of("ORG1", organization)));
    var cert = mock(java.security.cert.X509Certificate.class);
    when(cert.getNotBefore()).thenReturn(new Date(System.currentTimeMillis() - 60000));
    when(cert.getNotAfter()).thenReturn(new Date(System.currentTimeMillis() + 864000000));
    var ca = mock(IdentityAuthority.class);
    when(ca.issue(any(), anyString(), anyString(), anyMap()))
        .thenReturn(
            new WalletKeyStore.Material(cert, null, "SYNTHETIC REVOCATION CERT", "b".repeat(64)));
    var network = mock(IdentityNetwork.class);
    when(network.verify(anyString(), anyString(), anyString(), any()))
        .thenReturn(Map.of("synthetic", true));
    var worker = new IdentityWorker(db, tx, settings, ca, network, service);
    worker.process(id);
    assertEquals("READY", service.get(caller, id).get("status"));
    service.provision(
        caller, "revoke-" + UUID.randomUUID(), command(2, "ORG1", "DISABLED", "REVOKE"));
    return new RevocationFixture(id, settings, ca, worker);
  }

  @Test
  void supersededRevocationBeforeIntentCannotCallCaOrAlterCertificate() throws Exception {
    var f = issuedFixture();
    var configuration = f.settings().read();
    when(f.settings().read())
        .thenAnswer(
            call -> {
              service.provision(
                  caller, "restore-" + UUID.randomUUID(), command(3, "ORG1", "ACTIVE", "SYNC"));
              return configuration;
            });
    f.worker().process(f.id());
    verify(f.ca(), never()).revoke(any(), anyString());
    assertEquals(
        "ISSUED",
        db.queryForObject(
            "SELECT state FROM fabric_certificate_read WHERE identity_id=?", String.class, f.id()));
    when(f.settings().read()).thenReturn(configuration);
    f.worker().process(f.id());
    assertEquals("READY", service.get(caller, f.id()).get("status"));
  }

  @Test
  void restorationIsAcceptedButDrainsRevocationBeforeNewVersionBecomesReady() throws Exception {
    var f = issuedFixture();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var first = new java.util.concurrent.atomic.AtomicBoolean(true);
    String oldEnrollment =
        db.queryForObject(
            "SELECT enrollment_id FROM fabric_certificate_read WHERE identity_id=?",
            String.class,
            f.id());
    doAnswer(
            call -> {
              if (first.getAndSet(false)) {
                started.countDown();
                assertTrue(release.await(20, TimeUnit.SECONDS));
              }
              return null;
            })
        .when(f.ca())
        .revoke(any(), anyString());
    var executor = Executors.newSingleThreadExecutor();
    try {
      var work = executor.submit(() -> f.worker().process(f.id()));
      assertTrue(started.await(10, TimeUnit.SECONDS));
      assertEquals(
          "REVOKING",
          db.queryForObject(
              "SELECT crl_state FROM fabric_certificate_read WHERE identity_id=?",
              String.class,
              f.id()));
      assertEquals(
          "PENDING",
          service
              .provision(
                  caller, "restore-race-" + UUID.randomUUID(), command(3, "ORG1", "ACTIVE", "SYNC"))
              .get("status"));
      db.update(
          "UPDATE fabric_identity_read SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE"
              + " id=?",
          f.id());
      f.worker().process(f.id());
      assertEquals("READY", service.get(caller, f.id()).get("status"));
      assertEquals(2, service.get(caller, f.id()).get("certificateVersion"));
      release.countDown();
      work.get(10, TimeUnit.SECONDS);
      assertEquals(
          "CA_REVOKED",
          db.queryForObject(
              "SELECT state FROM fabric_certificate_read WHERE identity_id=? AND version=1",
              String.class,
              f.id()));
      assertEquals(
          "ISSUED",
          db.queryForObject(
              "SELECT state FROM fabric_certificate_read WHERE identity_id=? AND version=2",
              String.class,
              f.id()));
      assertEquals("READY", service.get(caller, f.id()).get("status"));
      verify(f.ca(), times(2)).revoke(any(), eq(oldEnrollment));
      assertEquals(
          1,
          db.queryForObject(
              "SELECT COUNT(*) FROM identity_audit WHERE identity_id=? AND action='CA_REVOKED'",
              Integer.class,
              f.id()));
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void lostRevocationLeaseCannotWriteResultAndIntentSurvivesUntilRecovery() throws Exception {
    var f = issuedFixture();
    var once = new java.util.concurrent.atomic.AtomicBoolean(true);
    doAnswer(
            call -> {
              if (once.getAndSet(false))
                db.update(
                    "UPDATE fabric_identity_read SET"
                        + " lease_token='replacement-lease',lease_until=CURRENT_TIMESTAMP-INTERVAL"
                        + " '1 second' WHERE id=?",
                    f.id());
              return null;
            })
        .when(f.ca())
        .revoke(any(), anyString());
    f.worker().process(f.id());
    assertEquals(
        "ISSUED",
        db.queryForObject(
            "SELECT state FROM fabric_certificate_read WHERE identity_id=?", String.class, f.id()));
    assertEquals(
        "REVOKING",
        db.queryForObject(
            "SELECT crl_state FROM fabric_certificate_read WHERE identity_id=?",
            String.class,
            f.id()));
    assertEquals(
        "PENDING",
        service
            .provision(
                caller, "restore-lost-" + UUID.randomUUID(), command(3, "ORG1", "ACTIVE", "SYNC"))
            .get("status"));
    f.worker().process(f.id());
    assertEquals("READY", service.get(caller, f.id()).get("status"));
    assertEquals(2, service.get(caller, f.id()).get("certificateVersion"));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM identity_audit WHERE identity_id=? AND action='CA_REVOKED'",
            Integer.class,
            f.id()));
  }

  @Test
  void disabledRevisionStillDrainsDurableRevocationWithoutReactivatingSubject() throws Exception {
    var f = issuedFixture();
    doThrow(new java.io.IOException("unknown CA outcome")).when(f.ca()).revoke(any(), anyString());
    f.worker().process(f.id());
    service.provision(
        caller, "disabled-" + UUID.randomUUID(), command(3, "ORG1", "DISABLED", "SYNC"));
    assertEquals("DISABLED", service.get(caller, f.id()).get("status"));
    assertTrue(f.worker().due().stream().anyMatch(r -> f.id().equals(r.get("id"))));
    doNothing().when(f.ca()).revoke(any(), anyString());
    f.worker().process(f.id());
    assertEquals("DISABLED", service.get(caller, f.id()).get("status"));
    assertEquals(
        "CA_REVOKED",
        db.queryForObject(
            "SELECT state FROM fabric_certificate_read WHERE identity_id=?", String.class, f.id()));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT COUNT(*) FROM fabric_certificate_read WHERE identity_id=?",
            Integer.class,
            f.id()));
    assertFalse(f.worker().due().stream().anyMatch(r -> f.id().equals(r.get("id"))));
  }

  @Test
  void expiredActiveWorkerCannotPersistIssuedMaterialOrReady() throws Exception {
    var f = issuedFixture();
    service.provision(caller, "active-" + UUID.randomUUID(), command(3, "ORG1", "ACTIVE", "SYNC"));
    var issued =
        f.ca().issue(f.settings().read().organizations().get("ORG1"), "unused", "unused", Map.of());
    when(f.ca().issue(any(), anyString(), anyString(), anyMap()))
        .thenAnswer(
            call -> {
              db.update(
                  "UPDATE fabric_identity_read SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1"
                      + " second' WHERE id=?",
                  f.id());
              return issued;
            });
    int before =
        db.queryForObject(
            "SELECT COUNT(*) FROM identity_audit WHERE identity_id=? AND action='CA_ISSUED'",
            Integer.class,
            f.id());
    f.worker().process(f.id());
    assertEquals("PENDING", service.get(caller, f.id()).get("status"));
    assertEquals(
        before,
        db.queryForObject(
            "SELECT COUNT(*) FROM identity_audit WHERE identity_id=? AND action='CA_ISSUED'",
            Integer.class,
            f.id()));
  }

  @Test
  void queryNeverReportsRevokedOrUnverifiedCurrentCertificateAsReady() throws Exception {
    var f = issuedFixture();
    db.update("UPDATE fabric_identity_read SET status='READY' WHERE id=?", f.id());
    db.update(
        "UPDATE fabric_certificate_read SET"
            + " state='CA_REVOKED',network_state='REVOCATION_UNVERIFIED' WHERE identity_id=?",
        f.id());
    assertEquals("RETRY", service.get(caller, f.id()).get("status"));
    assertEquals("CERTIFICATE_NOT_USABLE", service.get(caller, f.id()).get("errorCode"));
    var browser =
        new IdentityManagementController(db, new com.bproject.trust.identity.CurrentIdentity(db));
    var auth =
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken
            .authenticated(
                new com.bproject.trust.identity.PlatformPrincipal(
                    "synthetic-user", "synthetic-user", "ORG1"),
                null,
                List.of());
    assertEquals(
        "RETRY",
        browser.list(auth).stream()
            .filter(r -> f.id().equals(r.get("id")))
            .findFirst()
            .orElseThrow()
            .get("status"));
  }

  @Test
  void caRevokedButResponseLostIsReconciledBeforeRestorationCanBecomeReady() throws Exception {
    var f = issuedFixture();
    var revoked = new HashSet<String>();
    var once = new java.util.concurrent.atomic.AtomicBoolean(true);
    String oldEnrollment =
        db.queryForObject(
            "SELECT enrollment_id FROM fabric_certificate_read WHERE identity_id=?",
            String.class,
            f.id());
    doAnswer(
            call -> {
              revoked.add(call.getArgument(1));
              if (once.getAndSet(false))
                throw new java.io.IOException("CA performed revocation but response was lost");
              return null;
            })
        .when(f.ca())
        .revoke(any(), anyString());
    f.worker().process(f.id());
    assertTrue(revoked.contains(oldEnrollment));
    assertEquals(
        "REVOKING",
        db.queryForObject(
            "SELECT crl_state FROM fabric_certificate_read WHERE identity_id=?",
            String.class,
            f.id()));
    assertEquals(
        "PENDING",
        service
            .provision(
                caller,
                "restore-uncertain-" + UUID.randomUUID(),
                command(3, "ORG1", "ACTIVE", "SYNC"))
            .get("status"));
    f.worker().process(f.id());
    assertEquals("READY", service.get(caller, f.id()).get("status"));
    assertEquals(2, service.get(caller, f.id()).get("certificateVersion"));
    assertEquals(
        "CA_REVOKED",
        db.queryForObject(
            "SELECT state FROM fabric_certificate_read WHERE identity_id=? AND version=1",
            String.class,
            f.id()));
    verify(f.ca(), times(2)).revoke(any(), eq(oldEnrollment));
  }

  @Test
  void restoredReadySubjectRetainsOldVersionCrlVerificationPath() throws Exception {
    var f = issuedFixture();
    f.worker().process(f.id());
    assertEquals("CRL_PENDING", service.get(caller, f.id()).get("status"));
    var pending = CrlVerification.pendingVersions(db, f.id());
    assertEquals(1, pending.size());
    service.provision(
        caller, "restore-crl-" + UUID.randomUUID(), command(3, "ORG1", "ACTIVE", "SYNC"));
    f.worker().process(f.id());
    assertEquals("READY", service.get(caller, f.id()).get("status"));
    assertEquals(1, CrlVerification.pendingVersions(db, f.id()).size());
    var newer =
        db.queryForMap(
            "SELECT * FROM fabric_certificate_read WHERE identity_id=? AND version=2", f.id());
    // Only persistence of a synthetic successful proof is tested here, not real peer
    // enforcement.
    CrlVerification.recordVerifiedVersions(
        db,
        tx,
        f.id(),
        pending,
        "synthetic-operator",
        Map.of("synthetic", true, "versions", List.of(1)));
    assertEquals("READY", service.get(caller, f.id()).get("status"));
    assertEquals(
        newer,
        db.queryForMap(
            "SELECT * FROM fabric_certificate_read WHERE identity_id=? AND version=2", f.id()));
    assertEquals(
        "VERIFIED",
        db.queryForObject(
            "SELECT crl_state FROM fabric_certificate_read WHERE identity_id=? AND version=1",
            String.class,
            f.id()));
    assertTrue(CrlVerification.pendingVersions(db, f.id()).isEmpty());
  }

  @Test
  void staleCrlVersionSnapshotRollsBackWithoutChangingCurrentSubject() throws Exception {
    var f = issuedFixture();
    f.worker().process(f.id());
    var pending = CrlVerification.pendingVersions(db, f.id());
    pending.get(0).put("fingerprint", "c".repeat(64));
    assertThrows(
        IllegalStateException.class,
        () ->
            CrlVerification.recordVerifiedVersions(
                db, tx, f.id(), pending, "synthetic-operator", Map.of("synthetic", true)));
    assertEquals("CRL_PENDING", service.get(caller, f.id()).get("status"));
    assertEquals(
        "PENDING",
        db.queryForObject(
            "SELECT crl_state FROM fabric_certificate_read WHERE identity_id=?",
            String.class,
            f.id()));
  }
}
