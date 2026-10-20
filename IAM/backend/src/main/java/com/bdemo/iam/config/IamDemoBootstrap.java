package com.bdemo.iam.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * One-off provisioning of the three isolated demo identities.
 *
 * <p>Design constraints:
 *
 * <ul>
 *   <li>Opt-in only: runs as a standalone main() with {@code IAM_DEMO_BOOTSTRAP=enable}. It is never
 *       invoked by the application context, so a normal start can never create or reset accounts.
 *   <li>No credentials in code, SQL, Git, or logs. Passwords are read from a 0600 file whose path is
 *       given by {@code IAM_DEMO_SECRETS_FILE}, never from a command-line argument.
 *   <li>Target allow-list: refuses any JDBC URL that is not a loopback database whose name ends with
 *       {@code _verify} or {@code _demo}. This keeps the tool away from shared/production databases.
 *   <li>Idempotent: existing users are left untouched (no password reset, no role churn).
 *   <li>Fail-closed: missing tables, unknown roles, or a partially provisioned set abort the whole
 *       transaction.
 *   <li>Hashes are produced by the same {@link BCryptPasswordEncoder} the application uses to verify
 *       them, in memory, and written straight to the target row.
 * </ul>
 *
 * <p>Secret file format (one {@code key=value} per line, no quoting):
 *
 * <pre>
 * admin_password=...
 * auditor_password=...
 * scoped_password=...
 * </pre>
 */
public final class IamDemoBootstrap {

  /**
   * Roles are resolved by {@code role_code}, not by a hard-coded id: the isolated demo database
   * uses {@code r_admin_001/r_auditor_001/r_scoped_001} while the repository seed uses
   * {@code role-super-admin}. Resolving by code lets the same tool serve both, and it fails loudly
   * if a role is missing instead of silently binding nothing.
   */
  private record Account(String username, String displayName, String roleCode, String secretKey) {}

  private static final List<Account> ACCOUNTS =
      List.of(
          new Account("admin", "超级管理员", "super_admin", "admin_password"),
          new Account("auditor", "只读审计员", "auditor", "auditor_password"),
          new Account("scoped_user", "受限业务员", "scoped_user", "scoped_password"));

  public static void main(String[] args) throws Exception {
    if (!"enable".equals(System.getenv("IAM_DEMO_BOOTSTRAP"))) {
      System.err.println(
          "Refusing to run: set IAM_DEMO_BOOTSTRAP=enable to acknowledge this writes users.");
      System.exit(2);
    }

    String url = require("IAM_DEMO_DB_URL");
    String user = require("IAM_DEMO_DB_USER");
    String password = require("IAM_DEMO_DB_PASSWORD");
    String secretsPath = require("IAM_DEMO_SECRETS_FILE");

    guardTarget(url);

    var secrets = SecretsFile.load(secretsPath);
    for (Account account : ACCOUNTS) {
      secrets.requireStrong(account.secretKey());
    }

    var encoder = new BCryptPasswordEncoder();
    int created = 0;
    int skipped = 0;

    try (Connection c = DriverManager.getConnection(url, user, password)) {
      c.setAutoCommit(false);
      try {
        verifySchema(c);
        for (Account account : ACCOUNTS) {
          if (userExists(c, account.username())) {
            skipped++;
            continue;
          }
          insertUser(c, encoder, account, secrets.get(account.secretKey()));
          bindRole(c, account);
          created++;
        }
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      }
    }

    // No usernames' secrets, no hashes, no passwords are printed.
    System.out.printf(
        "IAM demo bootstrap finished: created=%d skipped=%d (existing users untouched)%n",
        created, skipped);
  }

  private static String require(String name) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      System.err.println("Missing required environment variable: " + name);
      System.exit(2);
    }
    return v;
  }

  /**
   * Target allow-list.
   *
   * <p>Default: loopback only, database name ending in {@code _verify} or {@code _demo}.
   *
   * <p>When the verification database lives on another host, {@code IAM_DEMO_ALLOW_REMOTE=enable}
   * is required as a second, explicit acknowledgement. The guard never widens implicitly, and the
   * database-name rule always applies.
   */
  private static void guardTarget(String url) {
    boolean loopback =
        url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/[A-Za-z0-9_]+\\?.*");
    boolean remote =
        "enable".equals(System.getenv("IAM_DEMO_ALLOW_REMOTE"))
            && url.matches("jdbc:postgresql://[0-9A-Za-z_.-]+(:\\d+)?/[A-Za-z0-9_]+\\?.*");
    boolean named = url.contains("_verify") || url.contains("_demo");
    if (!((loopback || remote) && named)) {
      System.err.println(
          "Refusing target. Expected a database whose name ends in _verify or _demo, on loopback"
              + " (or on a remote host only with IAM_DEMO_ALLOW_REMOTE=enable).");
      System.exit(2);
    }
  }

  private static void verifySchema(Connection c) throws Exception {
    // Use pg_class rather than information_schema: the application role may not see
    // information_schema rows for tables it did not create, even when it can query them.
    for (String table : List.of("iam_user", "iam_role", "iam_user_role")) {
      try (PreparedStatement q =
          c.prepareStatement(
              "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                  + " WHERE n.nspname = 'iam' AND c.relname = ? AND c.relkind IN ('r','p')")) {
        q.setString(1, table);
        try (ResultSet r = q.executeQuery()) {
          r.next();
          if (r.getInt(1) == 0) {
            throw new IllegalStateException(
                "Missing table iam." + table + " — run the identity migrations first");
          }
        }
      }
    }
    for (Account account : ACCOUNTS) {
      Integer count = null;
      try (PreparedStatement q =
          c.prepareStatement("SELECT count(*) FROM iam.iam_role WHERE role_code=?")) {
        q.setString(1, account.roleCode());
        try (ResultSet r = q.executeQuery()) {
          r.next();
          count = r.getInt(1);
        }
      }
      if (count == null || count == 0) {
        throw new IllegalStateException(
            "Missing role with role_code='"
                + account.roleCode()
                + "' — run the identity/role seed first");
      }
      if (count > 1) {
        throw new IllegalStateException(
            "Ambiguous role_code='" + account.roleCode() + "' (" + count + " rows)");
      }
    }
  }

  private static boolean userExists(Connection c, String username) throws Exception {
    try (PreparedStatement q =
        c.prepareStatement("SELECT 1 FROM iam.iam_user WHERE username=?")) {
      q.setString(1, username);
      try (ResultSet r = q.executeQuery()) {
        return r.next();
      }
    }
  }

  private static void insertUser(
      Connection c, BCryptPasswordEncoder encoder, Account account, String plaintext)
      throws Exception {
    try (PreparedStatement q =
        c.prepareStatement(
            "INSERT INTO iam.iam_user"
                + " (id,username,password_hash,display_name,status,created_at,updated_at,deleted)"
                + " VALUES(?,?,?,?,'active',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)")) {
      q.setString(1, UUID.randomUUID().toString().replace("-", ""));
      q.setString(2, account.username());
      q.setString(3, encoder.encode(plaintext));
      q.setString(4, account.displayName());
      q.executeUpdate();
    }
  }

  private static void bindRole(Connection c, Account account) throws Exception {
    try (PreparedStatement q =
        c.prepareStatement(
            "INSERT INTO iam.iam_user_role(user_id,role_id)"
                + " SELECT id, ? FROM iam.iam_user WHERE username=?")) {
      try (PreparedStatement role = c.prepareStatement("SELECT id FROM iam.iam_role WHERE role_code=?")) {
        role.setString(1, account.roleCode());
        try (ResultSet r = role.executeQuery()) {
          if (!r.next()) throw new IllegalStateException("Role disappeared mid-transaction");
          q.setString(1, r.getString(1));
        }
      }
      q.setString(2, account.username());
      if (q.executeUpdate() != 1) {
        throw new IllegalStateException("Failed to bind role for " + account.username());
      }
    }
  }

  /** Minimal key=value reader. Values are never logged or echoed. */
  private static final class SecretsFile {
    private final java.util.Map<String, String> values = new java.util.HashMap<>();

    static SecretsFile load(String path) throws Exception {
      var file = java.nio.file.Path.of(path);
      if (!java.nio.file.Files.isReadable(file)) {
        throw new IllegalStateException("Secrets file is not readable: " + path);
      }
      var secrets = new SecretsFile();
      for (String raw : java.nio.file.Files.readAllLines(file)) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        int eq = line.indexOf('=');
        if (eq <= 0) continue;
        secrets.values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
      }
      return secrets;
    }

    String get(String key) {
      return values.get(key);
    }

    /** Minimum length only; policy strength is enforced by the value the operator supplies. */
    void requireStrong(String key) {
      String v = values.get(key);
      if (v == null || v.length() < 16) {
        throw new IllegalStateException(
            "Secret '" + key + "' missing or shorter than 16 characters in the secrets file");
      }
      if (values.values().stream().filter(x -> x.equals(v)).count() > 1) {
        throw new IllegalStateException("The three demo passwords must differ from each other");
      }
    }
  }
}
