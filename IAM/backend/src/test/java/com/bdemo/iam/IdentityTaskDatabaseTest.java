package com.bdemo.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.provisioning.IdentityTasks;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses a migrated real dedicated openGauss database; no H2 substitution. */
@EnabledIfEnvironmentVariable(named = "IAM_IDENTITY_DB_TEST", matches = "1")
class IdentityTaskDatabaseTest {
  JdbcTemplate db;
  TransactionTemplate tx;
  IdentityTasks tasks;
  String id;

  @BeforeEach
  void setup() {
    String url = System.getenv("IAM_IDENTITY_DB_URL");
    assertNotNull(url);
    assertTrue(
        url.matches(
            "jdbc:postgresql://127\\.0\\.0\\.1:25432/iam_identity_[a-z0-9_]+_test\\?currentSchema=iam"));
    var ds =
        new DriverManagerDataSource(
            url, System.getenv("IAM_IDENTITY_DB_USER"), System.getenv("IAM_IDENTITY_DB_PASSWORD"));
    db = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    tasks = new IdentityTasks(db, tx, new ObjectMapper(), "");
    id = UUID.randomUUID().toString().replace("-", "");
  }

  @AfterEach
  void quarantineOnlyThisFixture() {
    if (db == null || id == null) return;
    tx.executeWithoutResult(t -> {
      if (db.update("UPDATE iam.identity_task SET state='QUARANTINED',last_error='TEST_RUN_COMPLETE' WHERE user_id=? AND lease_token IS NULL", id) == 1)
        tasks.audit(id, "TEST_RECORD_QUARANTINED");
    });
  }

  void user() {
    db.update(
        "INSERT INTO iam.iam_user(id,username,password_hash,display_name,company_code)"
            + " VALUES(?,?,?,'DB test','c-dl-port')",
        id,
        "db-" + id,
        "UNUSABLE_TEST_HASH");
  }

  @Test
  void userAndTaskRollbackTogetherIncludingAudit() {
    assertThrows(
        IllegalStateException.class,
        () ->
            tx.executeWithoutResult(
                s -> {
                  user();
                  tasks.sync(id, "TEST_CREATE");
                  throw new IllegalStateException("fault after task insert");
                }));
    assertEquals(
        0, db.queryForObject("SELECT COUNT(*) FROM iam.iam_user WHERE id=?", Integer.class, id));
    assertEquals("NOT_PROVISIONED", tasks.status(id).get("state"));
    assertEquals(
        0,
        db.queryForObject("SELECT COUNT(*) FROM iam.iam_audit WHERE target=?", Integer.class, id));
  }

  @Test
  void committedTaskSurvivesRestartAndDisableAdvancesDesiredRevision() {
    tx.executeWithoutResult(
        s -> {
          user();
          tasks.sync(id, "TEST_CREATE");
        });
    var restarted = new IdentityTasks(db, tx, new ObjectMapper(), "");
    assertEquals("PENDING", restarted.status(id).get("state"));
    tx.executeWithoutResult(
        s -> {
          db.update("UPDATE iam.iam_user SET status='disabled' WHERE id=?", id);
          restarted.sync(id, "TEST_DISABLE");
        });
    assertEquals("DISABLED", restarted.status(id).get("desiredState"));
    assertEquals(2L, ((Number) restarted.status(id).get("revision")).longValue());
    assertThrows(
        BizException.class, () -> restarted.operate(id, "rotate", "test-" + UUID.randomUUID()));
  }

  @Test
  void lifecycleIdempotencyPersistsAndRemoteWorkIsRefusedInsideTransaction() {
    tx.executeWithoutResult(
        s -> {
          user();
          tasks.sync(id, "TEST_CREATE");
        });
    String key = "rotate-" + UUID.randomUUID();
    tasks.operate(id, "rotate", key);
    tasks.operate(id, "rotate", key);
    assertEquals(2L, ((Number) tasks.status(id).get("revision")).longValue());
    assertThrows(BizException.class, () -> tasks.operate(id, "retry", key));
    assertThrows(
        IllegalStateException.class, () -> tx.executeWithoutResult(s -> tasks.process(id)));
  }
}
