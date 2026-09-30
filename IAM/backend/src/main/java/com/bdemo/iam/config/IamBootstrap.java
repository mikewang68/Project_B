package com.bdemo.iam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Creates only the isolated administrator; business acceptance users must use the real create API.
 */
public final class IamBootstrap {
  public static void main(String[] args) throws Exception {
    String url = System.getenv("IAM_DB_URL"), role = System.getenv("IAM_DB_USER");
    if (url == null
        || !url.matches(
            "jdbc:postgresql://127\\.0\\.0\\.1:25432/iam_identity_[a-z0-9_]{1,32}\\?currentSchema=iam")
        || role == null
        || !role.matches("iam_identity_[a-z0-9_]{1,24}_app"))
      throw new IllegalArgumentException("Invalid isolated target");
    var allowlist =
        new ObjectMapper()
            .readTree(Files.readString(Path.of(System.getenv("IAM_INSTANCE_ALLOWLIST"))));
    var instance = allowlist.path("instances").path(System.getenv("IAM_INSTANCE"));
    if (allowlist.path("schemaVersion").asInt() != 1
        || !url.equals(instance.path("databaseUrl").asText())
        || !role.equals(instance.path("appRole").asText())
        || !Path.of(System.getenv("IAM_ROOT"))
            .toRealPath()
            .equals(Path.of(instance.path("root").asText()).toRealPath()))
      throw new IllegalArgumentException("Bootstrap target not in instance allowlist");
    var account =
        new ObjectMapper().readTree(Files.readString(Path.of(System.getenv("IAM_BOOTSTRAP_FILE"))));
    String username = account.path("username").asText(),
        password = account.path("password").asText(),
        name = account.path("name").asText();
    if (!username.matches("[A-Za-z0-9_.-]{3,64}") || password.length() < 12 || name.isBlank())
      throw new IllegalArgumentException("Invalid private bootstrap account");
    try (var c = DriverManager.getConnection(url, role, System.getenv("IAM_DB_PASSWORD"))) {
      c.setAutoCommit(false);
      try {
        try (var lock =
            c.prepareStatement(
                "SELECT id FROM iam.iam_role WHERE id='role-super-admin' FOR UPDATE")) {
          try (var rows = lock.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("Run isolated migrations first");
          }
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        try (var q =
            c.prepareStatement(
                "SELECT COUNT(*) FROM iam.iam_user_role WHERE role_id='role-super-admin'")) {
          try (var r = q.executeQuery()) {
            r.next();
            if (r.getLong(1) != 0)
              throw new IllegalStateException("Administrator already exists; refusing to reset");
          }
        }
        try (var q =
            c.prepareStatement(
                "INSERT INTO"
                    + " iam.iam_user(id,username,password_hash,display_name,status,zone_code,company_code,org_codes)"
                    + " VALUES(?,?,?,?,'active','z-ne','c-dl-port','z-ne,c-dl-port')")) {
          q.setString(1, id);
          q.setString(2, username);
          q.setString(3, new BCryptPasswordEncoder().encode(password));
          q.setString(4, name);
          q.executeUpdate();
        }
        try (var q =
            c.prepareStatement(
                "INSERT INTO iam.iam_user_role(user_id,role_id) VALUES(?,'role-super-admin')")) {
          q.setString(1, id);
          q.executeUpdate();
        }
        try (var q =
            c.prepareStatement(
                "INSERT INTO iam.iam_audit(id,kind,user_id,module,action,target,result)"
                    + " VALUES(?,'operation',?,'bootstrap','CREATE_ADMIN',?,'success')")) {
          q.setString(1, UUID.randomUUID().toString());
          q.setString(2, id);
          q.setString(3, id);
          q.executeUpdate();
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }
    System.out.println("Isolated administrator created; no password printed");
  }
}
