package com.bproject.trust.config;

import java.sql.DriverManager;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/** Dedicated migration entry point; the running application has no DDL permission. */
public final class IsolatedMigration {
  public static void main(String[] args) throws Exception {
    String url = System.getenv("TRUST_DB_URL");
    var instance =
        IdentityInstance.read(
            System.getenv("TRUST_INSTANCE_ALLOWLIST"), System.getenv("TRUST_INSTANCE"));
    if (!instance.databaseUrl().equals(url))
      throw new IllegalArgumentException("Refusing non-dedicated database");
    try (var connection =
        DriverManager.getConnection(
            url, instance.migrationRole(), System.getenv("TRUST_MIGRATE_PASSWORD"))) {
      migrate(connection, instance.appRole());
    }
  }

  static void migrate(java.sql.Connection connection, String app) throws Exception {
    if (app == null || !app.matches("trust_iam_[a-z0-9_]{1,24}_app"))
      throw new IllegalArgumentException("Invalid application role");
    try (var schema = connection.createStatement()) {
      schema.execute("SET search_path TO trust_data");
    }
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    database.setDefaultSchemaName("trust_data");
    database.setLiquibaseSchemaName("trust_data");
    try (var migrations =
        new Liquibase("db/changelog.xml", new ClassLoaderResourceAccessor(), database)) {
      migrations.update(new liquibase.Contexts(), new liquibase.LabelExpression());
      grantRetentionPermissions(connection, app);
    }
  }

  private static void grantRetentionPermissions(java.sql.Connection connection, String app)
      throws Exception {
    try (var grants = connection.createStatement()) {
      try (var member =
          grants.executeQuery(
              "SELECT count(*) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE"
                  + " rolname='"
                  + app
                  + "')")) {
        member.next();
        if (member.getLong(1) != 0)
          throw new IllegalStateException("Application role must not inherit other roles");
      }
      grants.execute("REVOKE ALL ON ALL TABLES IN SCHEMA trust_data FROM " + app + ", PUBLIC");
      grants.execute("REVOKE CREATE ON SCHEMA trust_data FROM " + app + ", PUBLIC");
      // Definer triggers use a fixed migration search_path. Prevent temporary-table shadowing too.
      String database;
      try (var result = grants.executeQuery("SELECT current_database()")) {
        result.next();
        database = result.getString(1);
      }
      if (!database.matches("[a-z0-9_]+"))
        throw new IllegalStateException("Invalid dedicated database name");
      grants.execute("REVOKE TEMPORARY ON DATABASE " + database + " FROM " + app + ", PUBLIC");
      grants.execute("GRANT USAGE ON SCHEMA trust_data TO " + app);
      for (String table :
          new String[] {
            "trust_users",
            "event_evidence",
            "event_links",
            "audit_log",
            "event_signing_context",
            "identity_request",
            "identity_audit",
            "event_source_routes",
            "audit_evidence_access"
          }) grants.execute("GRANT SELECT,INSERT ON trust_data." + table + " TO " + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.events TO " + app);
      grants.execute(
          "GRANT SELECT ON trust_data.events_versions,trust_data.events_head,trust_data.events_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.events_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(expected_lease_token,manifest_json,manifest_cid,manifest_sha256,file_state,chain_state,tx_id,block_number,last_error,wallet_id,signer_fingerprint,ledger_identity,change_actor,change_operation,change_reason)"
              + " ON trust_data.events_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.evidence TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.evidence_versions,trust_data.evidence_head,trust_data.evidence_read TO"
              + " "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.evidence_head TO " + app);
      grants.execute(
          "GRANT UPDATE(cid,storage_state,change_actor,change_operation,change_reason) ON"
              + " trust_data.evidence_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.generic_event_data TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.generic_event_data_versions,trust_data.generic_event_data_head,trust_data.generic_event_data_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.generic_event_data_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(expected_lease_token,original_cid,last_error_code,change_actor,change_operation,change_reason)"
              + " ON trust_data.generic_event_data_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.wallets TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.wallets_versions,trust_data.wallets_head,trust_data.wallets_read TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.wallets_head TO " + app);
      grants.execute(
          "GRANT UPDATE(state,change_actor,change_operation,change_reason) ON"
              + " trust_data.wallets_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.wallet_bindings TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.wallet_bindings_versions,trust_data.wallet_bindings_head,trust_data.wallet_bindings_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.wallet_bindings_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(wallet_id,revision,change_request_id,change_actor,change_operation,change_reason)"
              + " ON trust_data.wallet_bindings_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.wallet_requests TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.wallet_requests_versions,trust_data.wallet_requests_head,trust_data.wallet_requests_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.wallet_requests_head TO " + app);
      grants.execute(
          "GRANT UPDATE(reviewed_by,state,reviewed_at,change_actor,change_operation,change_reason)"
              + " ON trust_data.wallet_requests_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.fabric_identity TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.fabric_identity_versions,trust_data.fabric_identity_head,trust_data.fabric_identity_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.fabric_identity_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(org_id,revision,command_hash,desired_state,action,status,certificate_version,last_error,next_attempt,attempts,lease_token,lease_until,updated_at,change_actor,change_operation,change_reason)"
              + " ON trust_data.fabric_identity_read TO "
              + app);
      grants.execute("GRANT SELECT ON trust_data.fabric_identity_runtime TO " + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.fabric_certificate TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.fabric_certificate_versions,trust_data.fabric_certificate_head,trust_data.fabric_certificate_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.fabric_certificate_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(state,fingerprint,certificate_pem,not_before,not_after,network_state,network_receipt,crl_state,change_actor,change_operation,change_reason)"
              + " ON trust_data.fabric_certificate_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.audit_log_settings TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.audit_log_settings_versions,trust_data.audit_log_settings_head,trust_data.audit_log_settings_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.audit_log_settings_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(archive_paused,chain_paused,updated_at,change_actor,change_operation,change_reason)"
              + " ON trust_data.audit_log_settings_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.audit_evidence TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.audit_evidence_versions,trust_data.audit_evidence_head,trust_data.audit_evidence_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.audit_evidence_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(archive_cid,archive_state,chain_event_id,last_error,attempts,next_at,lease_until,lease_token,change_actor,change_operation,change_reason)"
              + " ON trust_data.audit_evidence_read TO "
              + app);
      grants.execute("GRANT SELECT ON trust_data.audit_evidence_runtime TO " + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.audit_delivery TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.audit_delivery_versions,trust_data.audit_delivery_head,trust_data.audit_delivery_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.audit_delivery_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(event_json,state,last_error,attempts,next_at,lease_token,lease_until,change_actor,change_operation,change_reason)"
              + " ON trust_data.audit_delivery_read TO "
              + app);
      grants.execute("GRANT SELECT ON trust_data.audit_delivery_runtime TO " + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.audit_page_action TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.audit_page_action_versions,trust_data.audit_page_action_head,trust_data.audit_page_action_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.audit_page_action_head TO " + app);
      grants.execute(
          "GRANT UPDATE(result_json,change_actor,change_operation,change_reason) ON"
              + " trust_data.audit_page_action_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.signing_attempts TO " + app);
      grants.execute(
          "GRANT SELECT ON"
              + " trust_data.signing_attempts_versions,trust_data.signing_attempts_head,trust_data.signing_attempts_read"
              + " TO "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.signing_attempts_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(state,ledger_identity,block_number,last_error,resolved_at,change_actor,change_operation,change_reason)"
              + " ON trust_data.signing_attempts_read TO "
              + app);
      grants.execute("GRANT SELECT,INSERT ON trust_data.tasks TO " + app);
      grants.execute(
          "GRANT SELECT ON trust_data.tasks_versions,trust_data.tasks_head,trust_data.tasks_read TO"
              + " "
              + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.tasks_head TO " + app);
      grants.execute(
          "GRANT"
              + " UPDATE(state,last_error,attempts,next_at,lease_token,lease_until,updated_at,change_actor,change_operation,change_reason)"
              + " ON trust_data.tasks_read TO "
              + app);
      grants.execute("GRANT SELECT ON trust_data.tasks_runtime TO " + app);
      grants.execute("GRANT SELECT ON trust_data.event_heads TO " + app);
      grants.execute("GRANT UPDATE(lock_epoch) ON trust_data.event_heads TO " + app);
      grants.execute(
          "GRANT SELECT,INSERT ON trust_data.source_mutex,trust_data.storage_mutex TO " + app);
      grants.execute(
          "GRANT UPDATE(lock_epoch) ON trust_data.source_mutex,trust_data.storage_mutex TO " + app);
      try (var rows =
          grants.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='trust_data'")) {
        var tables = new java.util.ArrayList<String>();
        while (rows.next()) tables.add(rows.getString(1));
        for (String table : tables)
          try (var check =
              connection.prepareStatement(
                  "SELECT has_table_privilege(?,?,'DELETE') OR"
                      + " has_table_privilege(?,?,'TRUNCATE')")) {
            check.setString(1, app);
            check.setString(2, "trust_data." + table);
            check.setString(3, app);
            check.setString(4, "trust_data." + table);
            try (var result = check.executeQuery()) {
              result.next();
              if (result.getBoolean(1))
                throw new IllegalStateException("Unexpected destructive privilege on " + table);
            }
          }
      }
    }
  }
}
