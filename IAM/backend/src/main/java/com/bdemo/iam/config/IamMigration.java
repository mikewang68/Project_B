package com.bdemo.iam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.HexFormat;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Explicit operator command, never called by the application. Only IAM-owned migrations. */
public final class IamMigration {
  public static void main(String[] args) throws Exception {
    var tree =
        new ObjectMapper()
            .readTree(Files.readString(Path.of(System.getenv("IAM_INSTANCE_ALLOWLIST"))));
    var i = tree.path("instances").path(System.getenv("IAM_INSTANCE"));
    String url = i.path("databaseUrl").asText(),
        role = i.path("migrationRole").asText(),
        app = i.path("appRole").asText();
    if (tree.path("schemaVersion").asInt() != 1
        || !url.matches(
            "jdbc:postgresql://127\\.0\\.0\\.1:25432/iam_identity_[a-z0-9_]{1,32}\\?currentSchema=iam")
        || !url.equals(System.getenv("IAM_DB_URL"))
        || !role.matches("iam_identity_[a-z0-9_]{1,24}_migrate")
        || !app.matches("iam_identity_[a-z0-9_]{1,24}_app"))
      throw new IllegalArgumentException("Refusing non-IAM database/role");
    Path root = Path.of(System.getenv("IAM_ROOT")).toRealPath();
    if (!root.equals(Path.of(i.path("root").asText()).toRealPath()))
      throw new IllegalArgumentException("Wrong instance root");
    try (var c = DriverManager.getConnection(url, role, System.getenv("IAM_MIGRATE_PASSWORD"))) {
      c.setAutoCommit(false);
      try (var stmt = c.createStatement()) {
        stmt.execute("CREATE SCHEMA IF NOT EXISTS iam AUTHORIZATION " + role);
        stmt.execute(
            "CREATE TABLE IF NOT EXISTS iam.schema_migration(name VARCHAR(80) PRIMARY KEY,sha256"
                + " VARCHAR(64) NOT NULL,applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        stmt.execute("LOCK TABLE iam.schema_migration IN EXCLUSIVE MODE");
        for (String name :
            new String[] {"002-schema.sql", "004-identity.sql", "005-permissions.sql"}) {
          Path file = root.resolve("backend/db/isolated").resolve(name);
          String hash =
              HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(Files.readAllBytes(file)));
          try (var query =
              c.prepareStatement("SELECT sha256 FROM iam.schema_migration WHERE name=?")) {
            query.setString(1, name);
            try (var rows = query.executeQuery()) {
              if (rows.next()) {
                if (!hash.equals(rows.getString(1)))
                  throw new IllegalStateException("Applied migration changed: " + name);
                continue;
              }
            }
          }
          ScriptUtils.executeSqlScript(c, new FileSystemResource(file));
          try (var insert =
              c.prepareStatement("INSERT INTO iam.schema_migration(name,sha256) VALUES(?,?)")) {
            insert.setString(1, name);
            insert.setString(2, hash);
            insert.executeUpdate();
          }
        }
        stmt.execute("GRANT USAGE ON SCHEMA iam TO " + app);
        stmt.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA iam TO " + app);
        stmt.execute("REVOKE ALL ON iam.schema_migration FROM " + app);
        stmt.execute("REVOKE UPDATE,DELETE ON iam.iam_audit FROM " + app);
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
    System.out.println("IAM-only migrations committed; SYS untouched");
  }
}
