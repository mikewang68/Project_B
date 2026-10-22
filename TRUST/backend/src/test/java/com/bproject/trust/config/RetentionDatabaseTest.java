package com.bproject.trust.config;

import static org.junit.jupiter.api.Assertions.*;

import com.bproject.trust.ingress.EventSourceRoutes;
import com.bproject.trust.support.TestDatabase;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "TRUST_RETENTION_DB_TEST", matches = "1")
class RetentionDatabaseTest {
  static JdbcTemplate db, admin;
  static TransactionTemplate tx;

  @BeforeAll
  static void setup() throws Exception {
    for (int i = 0; i < 2; i++)
      try (var c = TestDatabase.dataSource(true).getConnection()) {
        IsolatedMigration.migrate(c, System.getenv("TRUST_DB_USER"));
      }
    db = new JdbcTemplate(TestDatabase.dataSource(false));
    admin = new JdbcTemplate(TestDatabase.dataSource(true));
    tx = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
  }

  static String event(String root, int version, String previous, String org, String source) {
    String id = version == 1 ? root : UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " events(id,org_id,source_system,source_event_id,event_type,batch_id,object_id,occurred_at,submitted_by,request_json,canonical_json,event_sha256,root_id,version,supersedes_id)"
            + " VALUES(?,?,?,?,'WAREHOUSE_IN','batch','object',CURRENT_TIMESTAMP,'test:actor','{}','{}',?,?,?,?)",
        id,
        org,
        source,
        UUID.randomUUID().toString(),
        "a".repeat(64),
        root,
        version,
        previous);
    return id;
  }

  @Test
  void eventAndProcessingSnapshotsStayImmutableWhileHeadsAdvance() {
    String root = UUID.randomUUID().toString();
    event(root, 1, null, "RETENTION", "SOURCE");
    String original =
        db.queryForObject(
            "SELECT row_to_json(e)::text FROM events e WHERE id=?", String.class, root);
    String first =
        db.queryForObject(
            "SELECT row_to_json(v)::text FROM events_versions v WHERE id=?", String.class, root);
    db.update(
        "UPDATE events_read SET"
            + " file_state='STORED',manifest_cid=?,change_actor='worker:test',change_operation='ARCHIVE'"
            + " WHERE id=?",
        "b".repeat(30),
        root);
    assertEquals(
        original,
        db.queryForObject(
            "SELECT row_to_json(e)::text FROM events e WHERE id=?", String.class, root));
    assertEquals(
        first,
        db.queryForObject(
            "SELECT row_to_json(v)::text FROM events_versions v WHERE id=? AND"
                + " retention_revision=1",
            String.class,
            root));
    String next = event(root, 2, root, "RETENTION", "SOURCE");
    assertEquals(
        next,
        db.queryForObject(
            "SELECT current_id FROM event_heads WHERE root_id=?", String.class, root));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> event(root, 3, root, "RETENTION", "SOURCE"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.update("UPDATE events_read SET manifest_cid=? WHERE id=?", "c".repeat(30), root));
    assertEquals(
        2,
        db.queryForObject("SELECT COUNT(*) FROM events_versions WHERE id=?", Integer.class, root));
  }

  @Test
  void restrictedRoleCannotRewriteOrRemoveHistoryOrSpoofOriginal() {
    for (String table :
        List.of(
            "audit_log_settings",
            "audit_evidence",
            "events",
            "evidence",
            "wallets",
            "wallet_requests",
            "wallet_bindings",
            "fabric_identity",
            "fabric_certificate",
            "audit_page_action",
            "audit_delivery",
            "generic_event_data",
            "signing_attempts",
            "tasks")) {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.execute("DELETE FROM " + table));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.execute("UPDATE " + table + " SET accepted_actor='forged'"));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.execute("UPDATE " + table + "_versions SET change_actor='forged'"));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.execute("DELETE FROM " + table + "_versions"));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> db.execute("TRUNCATE " + table + " CASCADE"));
    }
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("CREATE TEMP TABLE events(id integer)"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("UPDATE events_read SET canonical_json='tamper'"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("UPDATE events_head SET retention_revision=1"));
    assertFalse(
        db.queryForObject(
            "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE"
                + " rolname=current_user",
            Boolean.class));
  }

  @Test
  void leaseRenewalsDoNotCreateBusinessVersionsAndFinalResultDoes() {
    String id = UUID.randomUUID().toString();
    event(id, 1, null, "RETENTION", "LEASE");
    db.update("INSERT INTO tasks(event_id) VALUES(?)", id);
    db.update(
        "UPDATE tasks_read SET"
            + " state='RUNNING',lease_token='first',lease_until=CURRENT_TIMESTAMP+INTERVAL '60"
            + " seconds' WHERE event_id=?",
        id);
    int n =
        db.queryForObject(
            "SELECT COUNT(*) FROM tasks_versions WHERE event_id=?", Integer.class, id);
    db.update(
        "UPDATE tasks_read SET lease_until=CURRENT_TIMESTAMP+INTERVAL '120 seconds' WHERE"
            + " event_id=? AND lease_token='first'",
        id);
    assertEquals(
        n,
        db.queryForObject(
            "SELECT COUNT(*) FROM tasks_versions WHERE event_id=?", Integer.class, id));
    db.update("UPDATE tasks_read SET lease_token='second' WHERE event_id=?", id);
    assertEquals(
        0,
        db.update(
            "UPDATE tasks_read SET state='DONE' WHERE event_id=? AND lease_token='first'", id));
    db.update("UPDATE tasks_read SET state='DONE' WHERE event_id=? AND lease_token='second'", id);
    assertEquals(
        n + 1,
        db.queryForObject(
            "SELECT COUNT(*) FROM tasks_versions WHERE event_id=?", Integer.class, id));
    assertEquals(
        "READY", db.queryForObject("SELECT state FROM tasks WHERE event_id=?", String.class, id));
  }

  @Test
  void pageResultIsAppendOnlyAndCannotBeReplaced() {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO audit_page_action(id,org_id,actor,request_json)"
            + " VALUES(?,'RETENTION','user','{}')",
        id);
    db.update(
        "UPDATE audit_page_action_read SET"
            + " result_json='{}',change_actor='user',change_operation='RESULT' WHERE id=?",
        id);
    assertNull(
        db.queryForObject(
            "SELECT result_json FROM audit_page_action WHERE id=?", String.class, id));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.update("UPDATE audit_page_action_read SET result_json='different' WHERE id=?", id));
    assertEquals(
        2,
        db.queryForObject(
            "SELECT COUNT(*) FROM audit_page_action_versions WHERE id=?", Integer.class, id));
  }

  @Test
  void concurrentSettingsUpdatesHaveOneWinner() throws Exception {
    long revision =
        db.queryForObject(
            "SELECT retention_revision FROM audit_log_settings_read WHERE id=1", Long.class);
    var pool = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try {
      var jobs = new ArrayList<Future<Integer>>();
      for (int i = 0; i < 2; i++)
        jobs.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  try {
                    return db.update(
                        "UPDATE audit_log_settings_read SET archive_paused=NOT"
                            + " archive_paused,change_actor='test',change_operation='UPDATE',change_reason='concurrent'"
                            + " WHERE id=1 AND retention_revision=?",
                        revision);
                  } catch (org.springframework.dao.DataAccessException conflict) {
                    return 0;
                  }
                }));
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      assertEquals(
          1, jobs.get(0).get(15, TimeUnit.SECONDS) + jobs.get(1).get(15, TimeUnit.SECONDS));
      assertEquals(
          revision + 1,
          db.queryForObject(
              "SELECT retention_revision FROM audit_log_settings_read WHERE id=1", Long.class));
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void differentSourcesAreNotSerializedByAnEventTableLock() throws Exception {
    String org = "LOCK" + UUID.randomUUID().toString();
    EventSourceRoutes.prepare(db, org, "A");
    EventSourceRoutes.prepare(db, org, "B");
    var pool = Executors.newSingleThreadExecutor();
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      var first =
          pool.submit(
              () ->
                  tx.execute(
                      s -> {
                        EventSourceRoutes.reserve(db, org, "A", "LEGACY_V1", null);
                        held.countDown();
                        try {
                          release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                        }
                        return null;
                      }));
      assertTrue(held.await(5, TimeUnit.SECONDS));
      assertTimeoutPreemptively(
          java.time.Duration.ofSeconds(3),
          () ->
              tx.execute(
                  s -> {
                    EventSourceRoutes.reserve(db, org, "B", "LEGACY_V1", null);
                    return null;
                  }));
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void expiredWorkerCannotPublishAfterWaitingForEventLock() throws Exception {
    String id = UUID.randomUUID().toString();
    event(id, 1, null, "RETENTION", "FENCE");
    db.update("INSERT INTO tasks(event_id) VALUES(?)", id);
    db.update(
        "UPDATE tasks_read SET"
            + " state='RUNNING',lease_token='old',lease_until=clock_timestamp()+INTERVAL '1 second'"
            + " WHERE event_id=?",
        id);
    var pool = Executors.newSingleThreadExecutor();
    try (var blocker = TestDatabase.dataSource(false).getConnection()) {
      blocker.setAutoCommit(false);
      try (var lock =
          blocker.prepareStatement("SELECT event_id FROM tasks_head WHERE event_id=?")) {
        lock.setString(1, id);
        lock.executeQuery().close();
      }
      try (var lock =
          blocker.prepareStatement("SELECT id FROM events_head WHERE id=? FOR UPDATE")) {
        lock.setString(1, id);
        lock.executeQuery().close();
      }
      var started = new CountDownLatch(1);
      var job =
          pool.submit(
              () -> {
                started.countDown();
                return db.update(
                    "UPDATE events_read SET manifest_cid=?,expected_lease_token='old' WHERE id=?",
                    "f".repeat(30),
                    id);
              });
      assertTrue(started.await(2, TimeUnit.SECONDS));
      Thread.sleep(1400);
      assertFalse(job.isDone(), "Worker must still be waiting on the event head");
      blocker.commit();
      assertEquals(0, job.get(5, TimeUnit.SECONDS));
      assertNull(
          db.queryForObject("SELECT manifest_cid FROM events_read WHERE id=?", String.class, id));
      assertEquals(
          1,
          db.queryForObject("SELECT count(*) FROM events_versions WHERE id=?", Integer.class, id));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void currentSearchAndCountDoNotResurrectMatchedOldVersions() {
    String org = "SEARCH-" + UUID.randomUUID(), root = UUID.randomUUID().toString();
    event(root, 1, null, org, "SOURCE");
    String current = event(root, 2, root, org, "SOURCE");
    String oldNumber =
        db.queryForObject("SELECT source_event_id FROM events WHERE id=?", String.class, root);
    var service =
        new com.bproject.trust.events.EventService(
            db,
            tx,
            new com.bproject.trust.audit.AuditService(db),
            new com.bproject.trust.events.EventRelations(db));
    assertEquals(2L, service.list(org, "", 0, 20).get("total"));
    assertEquals(1L, service.list(org, "", 0, 20, "current").get("total"));
    var currentRows =
        (List<Map<String, Object>>) service.list(org, "", 0, 20, "current").get("items");
    assertEquals(current, currentRows.get(0).get("id"));
    assertEquals(0L, service.list(org, oldNumber, 0, 20, "current").get("total"));
    assertTrue(((List<?>) service.list(org, oldNumber, 0, 20, "current").get("items")).isEmpty());
    assertEquals(1L, service.list(org, oldNumber, 0, 20, "history").get("total"));
    assertEquals(0L, service.list("OTHER", oldNumber, 0, 20, "history").get("total"));
  }
}
