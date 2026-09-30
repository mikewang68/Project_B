package com.bproject.trust.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bproject.trust.adapters.fabric.FabricClient;
import com.bproject.trust.events.*;
import com.bproject.trust.verification.VerificationService;
import com.bproject.trust.wallet.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Real Fabric + IPFS; only the response after successful commit is deliberately lost. */
@org.springframework.context.annotation.Import(com.bproject.trust.support.NoBackgroundScheduling.class)
@SpringBootTest(properties = {"trust.isolation-enabled=false", "spring.liquibase.enabled=false"})
@EnabledIfEnvironmentVariable(named = "TRUST_REAL_COMPONENTS", matches = "1")
class ManagedRealComponentsTest {
  String fixtureEvent, fixtureOrg;
  @org.junit.jupiter.api.AfterEach
  void stopOnlyThisRunTask() {
    if (fixtureEvent != null)
      db.update("UPDATE tasks SET state='FAILED',lease_token=NULL,lease_until=NULL,last_error='TEST_RUN_COMPLETE' WHERE event_id=? AND state IN ('READY','RUNNING') AND event_id IN (SELECT id FROM events WHERE org_id=?)", fixtureEvent, fixtureOrg);
  }
  @MockitoSpyBean FabricClient fabric;
  @Autowired WalletService wallets;
  @Autowired EventService events;
  @Autowired com.bproject.trust.archiving.ArchiveWorker archive;
  @Autowired JdbcTemplate db;
  @Autowired VerificationService verification;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry p) {
    String url = System.getenv("TRUST_TEST_DB_URL");
    if (!"jdbc:opengauss://127.0.0.1:25432/trust_iam_dev_test".equals(url))
      throw new IllegalStateException("Dedicated test database required");
    p.add("spring.datasource.url", () -> url);
    p.add("spring.liquibase.enabled", () -> false);
    p.add("trust.fabric-channel", () -> "trust-iam-dev-test");
  }

  @Test
  void lostRealCommitResponseReconcilesWithoutSecondSubmission() throws Exception {
    String org = System.getenv("TRUST_REAL_TEST_ORG");
    String wallet = System.getenv("TRUST_REAL_TEST_WALLET");
    String suffix = UUID.randomUUID().toString();
    assertNotNull(org, "An approved test organization is required");
    assertNotNull(wallet, "An existing approved test wallet/binding is required");
    var active = wallets.active(wallet, org);
    assertEquals("Org1MSP", active.get("msp_id"));
    assertEquals(wallet, db.queryForObject(
        "SELECT wallet_id FROM wallet_bindings WHERE org_id=? AND source_system='WMS'", String.class, org));
    AtomicBoolean lost = new AtomicBoolean();
    java.util.concurrent.atomic.AtomicInteger submits =
        new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            call -> {
              submits.incrementAndGet();
              Object receipt = call.callRealMethod();
              if (lost.compareAndSet(false, true))
                throw new java.io.IOException("TEST: response lost after real valid commit");
              return receipt;
            })
        .when(fabric)
        .submit(anyMap(), any());
    var input =
        new EventInput(
            "WMS",
            "REAL-LOST-" + suffix,
            "WAREHOUSE_IN",
            "SIMULATED",
            "REAL-RECOVERY-" + suffix,
            Instant.parse("2026-09-24T01:00:00Z"),
            List.of(),
            null,
            List.of(),
            List.of(),
            BigDecimal.ONE,
            "吨",
            "隔离测试",
            null,
            null,
            List.of(),
            Map.of(
                "operatorId",
                "simulated-recovery-operator",
                "companyCode",
                "C01",
                "warehouseCode",
                "WH01",
                "ownerCode",
                "O01"));
    String id = (String) events.submit(input, org, "source:real-recovery-fixture", null).get("id");
    fixtureEvent = id; fixtureOrg = org;
    long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(100);
    while (System.nanoTime() < end && !"COMMITTED".equals(events.get(id, org).get("chain_state")))
    {
      com.bproject.trust.archiving.FixtureArchiveRunner.run(archive, db, id, org);
      Thread.sleep(250);
    }
    var row = events.get(id, org);
    assertEquals("COMMITTED", row.get("chain_state"));
    assertTrue(lost.get());
    assertEquals(1, submits.get());
    var attempts = db.queryForList("SELECT * FROM signing_attempts WHERE event_id=?", id);
    assertEquals(1, attempts.size());
    assertEquals("COMMITTED", attempts.get(0).get("state"));
    assertTrue(attempts.get(0).get("last_error").toString().contains("response lost"));
    assertEquals(true, verification.verify(id, org, "test-verifier").get("ok"));
    var evidence =
        Map.of(
            "event",
            row,
            "attempts",
            attempts,
            "submitCount",
            submits.get(),
            "fault",
            "lost response after real commit");
    var path =
        java.nio.file.Path.of(
            System.getenv("TRUST_ROOT"), ".local/test-results/real-commit-recovery.json");
    java.nio.file.Files.writeString(path, com.bproject.trust.shared.json.Json.write(evidence));
  }
}
