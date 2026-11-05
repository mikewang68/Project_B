import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** Explicit, offline migration runner. Never invoked by application startup. Java 17 + JDBC. */
public final class ScopeMigration {
  private static final String VERSION = "v21-region-001";
  private static final Pattern DOLLAR = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$");

  static List<String> statements(String text) {
    List<String> result = new ArrayList<>();
    StringBuilder part = new StringBuilder();
    char quote = 0;
    String dollar = null;
    boolean line = false, escape = false;
    int block = 0;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
      if (line) {
        part.append(ch);
        if (ch == '\n') line = false;
        continue;
      }
      if (block > 0) {
        part.append(ch);
        if (ch == '/' && next == '*') { block++; part.append(next); i++; }
        else if (ch == '*' && next == '/') { block--; part.append(next); i++; }
        continue;
      }
      if (dollar != null) {
        if (text.startsWith(dollar, i)) {
          part.append(dollar); i += dollar.length() - 1; dollar = null;
        } else part.append(ch);
        continue;
      }
      if (quote != 0) {
        part.append(ch);
        if (escape && ch == '\\' && next != 0) { part.append(next); i++; }
        else if (ch == quote) {
          if (next == quote) { part.append(next); i++; } else quote = 0;
        }
        continue;
      }
      if (ch == '-' && next == '-') { line = true; part.append("--"); i++; continue; }
      if (ch == '/' && next == '*') { block = 1; part.append("/*"); i++; continue; }
      if (ch == '\'' || ch == '"') {
        quote = ch;
        escape = ch == '\'' && i > 0 && (text.charAt(i - 1) == 'e' || text.charAt(i - 1) == 'E')
            && (i < 2 || !Character.isJavaIdentifierPart(text.charAt(i - 2)));
        part.append(ch); continue;
      }
      if (ch == '$') {
        var match = DOLLAR.matcher(text.substring(i));
        if (match.lookingAt()) {
          dollar = match.group(); part.append(dollar); i += dollar.length() - 1; continue;
        }
      }
      if (ch == ';') { if (!part.toString().isBlank()) result.add(part.toString()); part.setLength(0); }
      else part.append(ch);
    }
    if (quote != 0 || dollar != null || block > 0) throw new IllegalArgumentException("UNCLOSED_SQL");
    if (!part.toString().isBlank()) result.add(part.toString());
    return result;
  }

  static String sha256(String text) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  static String value(Connection db, String sql) throws SQLException {
    try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : null;
    }
  }

  static void execute(Connection db, String sql) throws SQLException {
    try (var statement = db.createStatement()) {
      statement.setQueryTimeout(60);
      statement.execute(sql);
    }
  }

  static String fingerprint(Connection db, String schema) throws Exception {
    // A length prefix distinguishes NULL, empty strings and delimiter-containing definitions.
    List<String> queries = List.of(
        "SELECT c.relname,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,pg_get_expr(d.adbin,d.adrelid) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum WHERE n.nspname='"+schema+"' AND c.relkind='r' AND c.relname<>'iam_v21_schema_ledger' AND a.attnum>0 AND NOT a.attisdropped ORDER BY 1,2",
        "SELECT c.relname,k.conname,pg_get_constraintdef(k.oid) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+schema+"' AND c.relname<>'iam_v21_schema_ledger' ORDER BY 1,2",
        "SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='"+schema+"' AND tablename<>'iam_v21_schema_ledger' ORDER BY 1,2",
        "SELECT c.relname,t.tgname,pg_get_triggerdef(t.oid) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+schema+"' AND NOT t.tgisinternal ORDER BY 1,2",
        "SELECT p.proname,pg_get_functiondef(p.oid)::text FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='"+schema+"' AND p.oid IN (SELECT tgfoid FROM pg_trigger WHERE NOT tgisinternal) ORDER BY 1,2");
    StringBuilder canonical = new StringBuilder();
    for (int q = 0; q < queries.size(); q++) {
      canonical.append("Q").append(q).append(':');
      try (var statement = db.createStatement(); var result = statement.executeQuery(queries.get(q))) {
        while (result.next()) {
          canonical.append('[');
          for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
            String value = result.getString(i);
            canonical.append(value == null ? -1 : value.length()).append(':');
            if (value != null) canonical.append(value);
          }
          canonical.append(']');
        }
      }
    }
    return sha256(canonical.toString());
  }

  private static boolean ledgerExists(Connection db, String schema) throws SQLException {
    return "1".equals(value(db,"SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+schema+"' AND c.relname='iam_v21_schema_ledger' AND c.relkind='r'"));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  /** mode: inspect | rehearse | apply. All connection values come from the process environment. */
  static String run(Connection db, String module, String mode, String expectedDb,
      String expectedFingerprint, String script) throws Exception {
    require(db.getAutoCommit(), "DEDICATED_CONNECTION_REQUIRED");
    try {
      return perform(db,module,mode,expectedDb,expectedFingerprint,script);
    } finally {
      if(!db.isClosed()) {
        if(!db.getAutoCommit()) db.rollback();
        db.setReadOnly(false);
        db.setAutoCommit(true);
      }
    }
  }

  private static String perform(Connection db, String module, String mode, String expectedDb,
      String expectedFingerprint, String script) throws Exception {
    require(List.of("iam", "sys").contains(module), "INVALID_MODULE");
    require(List.of("inspect", "rehearse", "apply").contains(mode), "INVALID_MODE");
    require(expectedDb != null && expectedDb.equals(value(db,"SELECT current_database()")), "DATABASE_MISMATCH");
    require(value(db,"SELECT version()").contains("openGauss 6.0."), "UNREHEARSED_DATABASE_VERSION");
    require("PG".equals(value(db,"SELECT current_setting('sql_compatibility')")), "PG_COMPATIBILITY_REQUIRED");
    require("on".equals(value(db,"SHOW standard_conforming_strings")), "STANDARD_STRINGS_REQUIRED");
    db.setAutoCommit(false);
    if (mode.equals("inspect")) {
      db.setReadOnly(true);
      String result=fingerprint(db,module); db.rollback(); return "INSPECTED:"+result;
    }
    require(expectedFingerprint != null && expectedFingerprint.matches("[a-f0-9]{64}"), "EXPECTED_FINGERPRINT_REQUIRED");
    try {
      execute(db,"SET LOCAL statement_timeout='60s'");
      try (var lock=db.createStatement()) {
        lock.setQueryTimeout(5);
        lock.execute("LOCK TABLE "+module+"."+(module.equals("iam")?"iam_user":"sys_config")+" IN SHARE ROW EXCLUSIVE MODE");
      }
      String before=fingerprint(db,module), checksum=sha256(script);
      if (ledgerExists(db,module)) {
        try (var query=db.prepareStatement("SELECT checksum,after_fingerprint FROM "+module+".iam_v21_schema_ledger WHERE version=?")) {
          query.setString(1,VERSION);
          try(var row=query.executeQuery()) {
            if(row.next()) {
              require(checksum.equals(row.getString(1)),"APPLIED_SCRIPT_CHECKSUM_MISMATCH");
              require(before.equals(row.getString(2)),"APPLIED_SCHEMA_DRIFT");
              db.rollback(); return "ALREADY_APPLIED:"+before;
            }
          }
        }
      }
      require(before.equals(expectedFingerprint),"SOURCE_SCHEMA_DRIFT");
      require("0".equals(value(db,"SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='"+module+"' AND c.relname='"+(module.equals("iam")?"iam_user":"sys_config")+"' AND a.attname='"+(module.equals("iam")?"scope_state":"scope_kind")+"' AND NOT a.attisdropped")),"UNTRACKED_OR_PARTIAL_MIGRATION");
      if(module.equals("iam"))
        require("0".equals(value(db,"SELECT count(*) FROM (SELECT lower(trim(username)) FROM iam.iam_user WHERE deleted=0 GROUP BY lower(trim(username)) HAVING count(*)>1) duplicates")),"NORMALIZED_USERNAME_CONFLICT");
      for(String statement:statements(script)) execute(db,statement);
      String after=fingerprint(db,module);
      require(!after.equals(before),"NO_SCHEMA_CHANGE");
      if(mode.equals("rehearse")) {
        db.rollback();
        require(before.equals(fingerprint(db,module)),"ROLLBACK_FINGERPRINT_MISMATCH");
        db.rollback(); return "REHEARSED_ROLLED_BACK:"+before;
      }
      if(!ledgerExists(db,module)) execute(db,"CREATE TABLE "+module+".iam_v21_schema_ledger(version VARCHAR(64) PRIMARY KEY,checksum VARCHAR(64) NOT NULL,before_fingerprint VARCHAR(64) NOT NULL,after_fingerprint VARCHAR(64) NOT NULL,applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
      try(var insert=db.prepareStatement("INSERT INTO "+module+".iam_v21_schema_ledger(version,checksum,before_fingerprint,after_fingerprint) VALUES(?,?,?,?)")) {
        insert.setString(1,VERSION); insert.setString(2,checksum); insert.setString(3,before); insert.setString(4,after); insert.executeUpdate();
      }
      db.commit(); return "APPLIED:"+after;
    } catch(Exception error) {
      db.rollback(); throw error;
    }
  }

  public static void main(String[] args) {
    if(args.length!=3) { System.err.println("Usage: ScopeMigration <iam|sys> <inspect|rehearse|apply> <sql-file>"); System.exit(2); }
    try(var db=DriverManager.getConnection(System.getenv("SCOPE_JDBC_URL"),System.getenv("SCOPE_DB_USER"),System.getenv("SCOPE_DB_PASSWORD"))) {
      String script=Files.readString(Path.of(args[2]),StandardCharsets.UTF_8);
      System.out.println(run(db,args[0],args[1],System.getenv("SCOPE_EXPECT_DATABASE"),System.getenv("SCOPE_EXPECT_FINGERPRINT"),script));
    } catch(Exception error) {
      // JDBC errors can contain connection details and row values. The runner emits only a code.
      System.err.println(error instanceof SQLException sql ? "SQL_FAILURE:"+sql.getSQLState() : "MIGRATION_REJECTED:"+error.getMessage());
      System.exit(1);
    }
  }
}
