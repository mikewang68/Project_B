package com.bproject.trust.config;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real JDBC/Liquibase tests; the test operator records PostgreSQL vs openGauss separately. */
@EnabledIfEnvironmentVariable(named = "TRUST_GENERIC_DB_TEST", matches = "1")
class GenericMigrationDatabaseTest {
  static JdbcTemplate db;

  static DriverManagerDataSource dataSource(boolean migration) {
    return com.bproject.trust.support.TestDatabase.dataSource(migration);
  }

  @BeforeAll
  static void migrate() throws Exception {
    // Exercise production migrations and grants; the compatibility DB is not a deployed
    // instance.
    for (int i = 0; i < 2; i++)
      try (var c = dataSource(true).getConnection()) {
        IsolatedMigration.migrate(c, System.getenv("TRUST_DB_USER"));
      }
    db = new JdbcTemplate(dataSource(false));
  }

  @Test
  void applicationIsRestrictedAndMigrationIsRepeatable() {
    assertFalse(
        db.queryForObject(
            "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE"
                + " rolname=current_user",
            Boolean.class));
    assertFalse(
        db.queryForObject(
            "SELECT has_schema_privilege(current_user,'trust_data','CREATE')", Boolean.class));
    for (String sql :
        new String[] {
          "UPDATE generic_event_data SET original_utf8='tamper'",
          "UPDATE generic_event_data SET original_sha256=repeat('a',64)",
          "UPDATE generic_event_data SET original_size_bytes=1",
          "DELETE FROM generic_event_data",
          "UPDATE event_source_routes SET source_id='takeover'",
          "DELETE FROM event_source_routes",
          "UPDATE audit_page_action SET identity_mode='real'",
          "UPDATE audit_delivery SET actor='takeover'",
          "SELECT * FROM databasechangelog"
        })
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(sql), sql);
    // Column grants are sufficient for archival/retry without changing the original.
    assertDoesNotThrow(
        () ->
            db.execute(
                "UPDATE generic_event_data_read SET"
                    + " original_cid=NULL,last_error_code=NULL WHERE 1=0"));
    assertDoesNotThrow(
        () ->
            db.execute(
                "UPDATE audit_delivery_read SET lease_token=NULL,lease_until=NULL WHERE 1=0"));
    assertEquals(
        9,
        new JdbcTemplate(dataSource(true))
            .queryForObject("SELECT COUNT(*) FROM databasechangelog", Integer.class));
  }

  @Test
  void legacySnapshotSurvivesUpgradeAndConditionalBatchConstraintRemains() throws Exception {
    String schema = "generic_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection c = dataSource(true).getConnection()) {
      try (var s = c.createStatement()) {
        s.execute("CREATE SCHEMA " + schema);
      }
      c.setSchema(schema);
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      database.setDefaultSchemaName(schema);
      database.setLiquibaseSchemaName(schema);
      try (var migration =
          new Liquibase("db/changelog.xml", new ClassLoaderResourceAccessor(), database)) {
        migration.update(4, new liquibase.Contexts(), new liquibase.LabelExpression());
        String id = UUID.randomUUID().toString();
        try (var s =
            c.prepareStatement(
                "INSERT INTO"
                    + " events(id,org_id,source_system,source_event_id,event_type,batch_id,object_id,occurred_at,submitted_by,request_json,canonical_json,event_sha256,root_id,version)"
                    + " VALUES(?,'OLD','OLD','one','WAREHOUSE_IN','batch','object',CURRENT_TIMESTAMP,'old','"
                    + " {  } ',' {  } ',repeat('a',64),?,1)")) {
          s.setString(1, id);
          s.setString(2, id);
          s.executeUpdate();
        }
        migration.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        migration.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        c.commit();
        c.setAutoCommit(true);
        try (var s = c.createStatement();
            var r = s.executeQuery("SELECT canonical_json,record_kind FROM events")) {
          assertTrue(r.next());
          assertEquals(" {  } ", r.getString(1));
          assertEquals("LEGACY_V1", r.getString(2));
        }
        try (var s = c.createStatement()) {
          assertThrows(
              SQLException.class, () -> s.executeUpdate("UPDATE events SET batch_id=NULL"));
          assertThrows(
              SQLException.class,
              () -> s.executeUpdate("UPDATE events SET record_kind='GENERIC_V1'"));
          assertEquals(
              1,
              s.executeUpdate(
                  "UPDATE events SET"
                      + " record_kind='GENERIC_V1',batch_id=NULL,signing_required=TRUE"));
          assertThrows(
              SQLException.class,
              () -> s.executeUpdate("UPDATE events SET signing_required=FALSE"));
          try (var r = s.executeQuery("SELECT record_kind,source_id FROM event_source_routes")) {
            assertTrue(r.next());
            assertEquals("LEGACY_V1", r.getString(1));
            assertNull(r.getString(2));
          }
          s.execute("SET search_path TO trust_data");
          s.execute("DROP SCHEMA " + schema + " CASCADE");
        }
      }
    }
  }

  @Test
  void eightChangeSetSnapshotRetainsEveryExistingDomainColumn() throws Exception {
    String schema = "retention_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var migrationDs = dataSource(true);
    var admin = new JdbcTemplate(migrationDs);
    admin.execute("CREATE SCHEMA " + schema);
    migrationDs.setSchema(schema);
    var fixture = new JdbcTemplate(migrationDs);
    var before = new java.util.LinkedHashMap<String, java.util.List<String>>();
    var fields = new java.util.LinkedHashMap<String, String>();
    try (var c = migrationDs.getConnection()) {
      var database =
          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
      database.setDefaultSchemaName(schema);
      database.setLiquibaseSchemaName(schema);
      try (var migrations =
          new Liquibase("db/changelog.xml", new ClassLoaderResourceAccessor(), database)) {
        migrations.update(8, new liquibase.Contexts(), new liquibase.LabelExpression());
        fixture.execute(
            "INSERT INTO"
                + " wallets(id,org_id,label,msp_id,key_ref,certificate_pem,fingerprint,subject,not_after,state)"
                + " VALUES('wallet','OLD','old wallet','Org1MSP','synthetic-key','synthetic"
                + " certificate',repeat('a',64),'synthetic',CURRENT_TIMESTAMP,'DISABLED')");
        fixture.execute(
            "INSERT INTO"
                + " wallet_requests(id,org_id,action,payload,reason,proposed_by,reviewed_by,state,reviewed_at)"
                + " VALUES('request','OLD','BIND','{}','old"
                + " approval','proposer','reviewer','APPROVED',CURRENT_TIMESTAMP)");
        fixture.execute(
            "INSERT INTO wallet_bindings(org_id,source_system,wallet_id,revision,change_request_id)"
                + " VALUES('OLD','SOURCE','wallet',3,'request')");
        fixture.execute(
            "INSERT INTO"
                + " evidence(id,org_id,uploaded_by,filename,mime,size_bytes,sha256,cid,storage_state)"
                + " VALUES('evidence','OLD','receiver','old.pdf','application/pdf',5,repeat('b',64),'synthetic-cid','STORED')");
        fixture.execute(
            "INSERT INTO"
                + " events(id,org_id,source_system,source_event_id,event_type,batch_id,object_id,occurred_at,submitted_by,request_json,canonical_json,event_sha256,root_id,version,wallet_id,chain_state)"
                + " VALUES('event','OLD','SOURCE','one','ARRIVAL','batch','object',CURRENT_TIMESTAMP,'actor','"
                + " { } ',' { } ',repeat('c',64),'event',1,'wallet','COMMITTED')");
        fixture.execute(
            "INSERT INTO event_evidence(event_id,evidence_id) VALUES('event','evidence')");
        fixture.execute(
            "INSERT INTO tasks(event_id,state,attempts,last_error) VALUES('event','DONE',4,'old"
                + " result')");
        fixture.execute(
            "INSERT INTO signing_attempts(tx_id,event_id,wallet_id,fingerprint,state)"
                + " VALUES('transaction','event','wallet',repeat('a',64),'COMMITTED')");
        fixture.execute(
            "INSERT INTO"
                + " fabric_identity(id,issuer,tenant,user_id,org_id,revision,command_hash,desired_state,action,status)"
                + " VALUES('identity','issuer','tenant','user','OLD',7,repeat('d',64),'DELETED','REVOKE','REVOKED')");
        fixture.execute(
            "INSERT INTO"
                + " fabric_certificate(identity_id,version,requested_revision,org_id,msp_id,ca_id,enrollment_id,key_ref,state,fingerprint,certificate_pem)"
                + " VALUES('identity',2,6,'OLD','Org1MSP','ca','synthetic-enrollment','synthetic-identity-key','CA_REVOKED',repeat('e',64),'synthetic"
                + " certificate')");
        fixture.execute(
            "INSERT INTO audit_page_action(id,org_id,actor,request_json,result_json)"
                + " VALUES('page','OLD','actor','{ }','{ }')");
        fixture.execute(
            "INSERT INTO"
                + " audit_delivery(id,org_id,actor,action,object_id,outcome,source_kind,menu_id,occurred_at,event_json,state)"
                + " VALUES('delivery','OLD','actor','READ','object','SUCCESS','BUSINESS','events','2026-01-01T00:00:00Z','{"
                + " }','DELIVERED')");
        for (String table :
            java.util.List.of(
                "events",
                "evidence",
                "generic_event_data",
                "wallets",
                "wallet_bindings",
                "wallet_requests",
                "fabric_identity",
                "fabric_certificate",
                "audit_log_settings",
                "audit_evidence",
                "audit_delivery",
                "audit_page_action",
                "signing_attempts",
                "tasks")) {
          var cols =
              fixture.queryForList(
                  "SELECT column_name FROM information_schema.columns WHERE table_schema=? AND"
                      + " table_name=? ORDER BY ordinal_position",
                  String.class,
                  schema,
                  table);
          String selected = String.join(",", cols);
          fields.put(table, selected);
          before.put(
              table,
              fixture.queryForList(
                  "SELECT row_to_json(x)::text FROM (SELECT " + selected + " FROM " + table + ") x",
                  String.class));
        }
        migrations.update(new liquibase.Contexts(), new liquibase.LabelExpression());
        for (var entry : fields.entrySet()) {
          String table = entry.getKey(), cols = entry.getValue();
          assertEquals(
              before.get(table),
              fixture.queryForList(
                  "SELECT row_to_json(x)::text FROM (SELECT " + cols + " FROM " + table + ") x",
                  String.class),
              table + " original changed");
          assertEquals(
              before.get(table),
              fixture.queryForList(
                  "SELECT row_to_json(x)::text FROM (SELECT "
                      + cols
                      + " FROM "
                      + table
                      + "_read) x",
                  String.class),
              table + " read baseline differs");
          assertEquals(
              before.get(table).size(),
              fixture.queryForObject(
                  "SELECT count(*) FROM " + table + "_versions WHERE change_operation='BASELINE'",
                  Integer.class));
        }
      }
    } finally {
      admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }
}
