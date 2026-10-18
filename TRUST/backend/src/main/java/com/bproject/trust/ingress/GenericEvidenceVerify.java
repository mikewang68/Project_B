package com.bproject.trust.ingress;

import com.bproject.trust.archiving.AttestationRecord;
import com.bproject.trust.config.IdentityInstance;
import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.shared.json.Json;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Operator-only entry point. No Spring context, scheduler, HTTP endpoint or write transaction. */
public final class GenericEvidenceVerify {
  private GenericEvidenceVerify() {}

  private static final ObjectMapper READER =
      Json.MAPPER
          .copy()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<Integer> PROTECTED_PORTS =
      Set.of(18080, 18091, 28182, 28183, 28184, 28185, 28186, 28187);

  public record Target(
      boolean enabled,
      String purpose,
      IdentityInstance instance,
      String ipfsUrl,
      String fabricEndpoint,
      String serverName,
      String contract,
      String queryMsp,
      String queryFingerprint) {}

  public record Allowlist(int schemaVersion, Map<String, Target> targets) {}

  public record Plan(
      int schemaVersion,
      String instanceId,
      Target target,
      String orgId,
      String sourceSystem,
      String sourceId,
      String eventId) {}

  public record Credentials(String databasePassword) {}

  interface Resources extends AutoCloseable {
    JdbcTemplate db();

    EvidenceStorage storage();

    Map<String, Object> find(String org, String id) throws Exception;

    @Override
    void close() throws Exception;
  }

  interface Factory {
    Resources open(Target target, Credentials credentials) throws Exception;
  }

  static final class Failure extends RuntimeException {
    final String code;

    Failure(String code) {
      super(code);
      this.code = code;
    }
  }

  private static void require(boolean ok, String code) {
    if (!ok) throw new Failure(code);
  }

  public static void main(String[] args) {
    // Third-party logs must not leak endpoints, query parameters or exception messages to stdout.
    java.util.logging.LogManager.getLogManager().reset();
    if (org.slf4j.LoggerFactory.getILoggerFactory()
        instanceof ch.qos.logback.classic.LoggerContext c)
      c.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
    int code = run(args, ReadOnlyVerificationResources::new, System.out);
    if (code != 0) System.exit(code);
  }

  static int run(String[] args, Factory factory, PrintStream stdout) {
    Path output = null;
    var report = new LinkedHashMap<String, Object>();
    report.put("hashVerification", "NOT_COMPLETED");
    report.put("ledgerRegistration", "NOT_CHECKED");
    report.put(
        "notProven",
        List.of(
            "CA_OR_CRL_STATUS",
            "BUSINESS_FACT_AUTHENTICITY",
            "OFFLINE_CRYPTOGRAPHIC_LEDGER_PROOF"));
    try {
      var options = options(args);
      Path allowFile = absolute(options.get("--allowlist"));
      Path planFile = absolute(options.get("--plan"));
      require(!allowFile.equals(planFile), "INDEPENDENT_ALLOWLIST_REQUIRED");
      var allow = read(allowFile, Allowlist.class);
      var plan = read(planFile, Plan.class);
      validate(allow, plan);
      if (!options.containsKey("--execute")) {
        stdout.println(Json.write(Map.of("status", "DRY_RUN", "configuration", "VALIDATED")));
        return 0;
      }
      Path root = absolute(plan.target().instance().root());
      Path credentialFile = absolute(options.get("--credentials"));
      require(
          credentialFile.startsWith(root.resolve(".local"))
              || credentialFile.startsWith(root.resolve("runtime/secrets")),
          "PRIVATE_CREDENTIAL_PATH_REQUIRED");
      // Validate output before reading credentials or opening any resource.
      output = reserveOutput(root, absolute(options.get("--output")));
      var credentials = read(credentialFile, Credentials.class);
      require(
          credentials.databasePassword() != null && !credentials.databasePassword().isBlank(),
          "INVALID_CREDENTIALS");
      report.put("eventId", plan.eventId());
      report.put("orgId", plan.orgId());
      report.put("sourceSystem", plan.sourceSystem());
      report.put("sourceId", plan.sourceId());
      byte[] original;
      Map<String, Object> row;
      try (var resources = factory.open(plan.target(), credentials)) {
        row = scopedRow(resources.db(), plan);
        var archive = new GenericEvidenceArchive(resources.db(), resources.storage());
        original = archive.verify(row);
        report.put("hashVerification", "MATCH");
        report.put("originalSha256", Json.sha(original));
        report.put("eventSha256", row.get("event_sha256"));
        report.put("manifestSha256", row.get("manifest_sha256"));
        var ledger = resources.find(plan.orgId(), plan.eventId());
        require(ledger != null, "LEDGER_RECORD_MISSING");
        try {
          AttestationRecord.assertMatches(AttestationRecord.record(row), ledger);
        } catch (com.bproject.trust.shared.web.ApiError e) {
          throw new Failure("LEDGER_MISMATCH");
        }
        report.put("ledgerRegistration", "MATCH");
        report.put("ledgerRecord", AttestationRecord.record(row));
        report.put("originalCid", resources.db().queryForMap(
            "SELECT original_cid FROM generic_event_data WHERE event_id=?", plan.eventId()).get("original_cid"));
        report.put("manifestCid", row.get("manifest_cid"));
      }
      // No raw output before all checks and resource closure have succeeded.
      if (options.containsKey("--save-original")) {
        writeNew(output.resolve("source-request.json"), original);
        writeNew(
            output.resolve("evidence-manifest.json"),
            ((String) row.get("manifest_json")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      report.put("status", "VERIFIED");
      report.put("checkedAt", Instant.now().toString());
      writeNew(output.resolve("report.json"), Json.bytes(report));
      stdout.println(
          Json.write(
              Map.of(
                  "status",
                  "VERIFIED",
                  "originalSha256",
                  report.get("originalSha256"),
                  "eventSha256",
                  report.get("eventSha256"),
                  "manifestSha256",
                  report.get("manifestSha256"))));
      return 0;
    } catch (Exception error) {
      String code =
          error instanceof Failure f
              ? f.code
              : error instanceof IngressError ? "INTEGRITY_MISMATCH" : "VERIFICATION_UNAVAILABLE";
      report.put("status", "FAILED");
      report.put("code", code);
      if (output != null)
        try {
          writeNew(output.resolve("report.json"), Json.bytes(report));
        } catch (Exception ignored) {
        }
      stdout.println(Json.write(Map.of("status", "FAILED", "code", code)));
      return 1;
    }
  }

  static Map<String, Object> scopedRow(JdbcTemplate db, Plan p) {
    var rows =
        db.queryForList(
            "SELECT e.*,t.state AS task_state FROM events e JOIN tasks t ON t.event_id=e.id WHERE"
                + " e.record_kind='GENERIC_V1' AND e.org_id=? AND e.source_system=? AND"
                + " e.submitted_by=? AND e.id=?",
            p.orgId(),
            p.sourceSystem(),
            "source:" + p.sourceId(),
            p.eventId());
    require(rows.size() == 1, "TARGET_NOT_FOUND");
    var row = rows.get(0);
    require(
        "GENERIC_V1".equals(row.get("record_kind"))
            && p.orgId().equals(row.get("org_id"))
            && p.sourceSystem().equals(row.get("source_system"))
            && ("source:" + p.sourceId()).equals(row.get("submitted_by"))
            && p.eventId().equals(row.get("id")),
        "TARGET_NOT_FOUND");
    require(
        "DONE".equals(row.get("task_state"))
            && "COMMITTED".equals(row.get("chain_state"))
            && "STORED".equals(row.get("file_state"))
            && Boolean.TRUE.equals(row.get("signing_required"))
            && row.get("wallet_id") instanceof String wallet
            && !wallet.isBlank()
            && row.get("signer_fingerprint") instanceof String fingerprint
            && fingerprint.matches("[a-f0-9]{64}")
            && row.get("manifest_cid") instanceof String,
        "TARGET_NOT_COMPLETE");
    return row;
  }

  static void validate(Allowlist allow, Plan plan) throws Exception {
    require(
        allow.schemaVersion() == 1
            && allow.targets() != null
            && plan.schemaVersion() == 1
            && id(plan.instanceId(), 80),
        "INVALID_PLAN");
    Target approved = allow.targets().get(plan.instanceId());
    require(
        approved != null
            && approved.enabled()
            && approved.equals(plan.target())
            && "dedicated-generic-verification".equals(approved.purpose()),
        "TARGET_NOT_APPROVED");
    approved.instance().validate();
    safeExisting(absolute(approved.instance().root()));
    require(
        id(plan.orgId(), 80)
            && id(plan.sourceSystem(), 80)
            && id(plan.sourceId(), 70)
            && UUID.fromString(plan.eventId()).toString().equals(plan.eventId()),
        "INVALID_SCOPE");
    URI ipfs = URI.create(approved.ipfsUrl());
    require(
        "http".equals(ipfs.getScheme())
            && "127.0.0.1".equals(ipfs.getHost())
            && approved.ipfsUrl().equals("http://127.0.0.1:" + ipfs.getPort())
            && port(ipfs.getPort()),
        "INVALID_ENDPOINT");
    require(
        approved.fabricEndpoint() != null
            && approved.fabricEndpoint().matches("127\\.0\\.0\\.1:[0-9]{4,5}"),
        "INVALID_ENDPOINT");
    require(
        port(Integer.parseInt(approved.fabricEndpoint().substring("127.0.0.1:".length())))
            && id(approved.serverName(), 253)
            && id(approved.contract(), 80)
            && id(approved.queryMsp(), 80)
            && approved.queryFingerprint() != null
            && approved.queryFingerprint().matches("[a-f0-9]{64}")
            && port(approved.instance().port()),
        "INVALID_ENDPOINT");
  }

  private static boolean id(String s, int max) {
    return s != null
        && s.matches("[A-Za-z0-9._-]{1," + max + "}")
        && !Set.of(".", "..").contains(s);
  }

  private static boolean port(int n) {
    return n >= 1024 && n <= 65535 && !PROTECTED_PORTS.contains(n);
  }

  static Path absolute(String value) {
    require(value != null, "MISSING_ARGUMENT");
    Path p = Path.of(value);
    require(p.isAbsolute() && p.normalize().equals(p), "INVALID_LOCAL_PATH");
    return p;
  }

  static void safeExisting(Path path) throws Exception {
    Path current = path.getRoot();
    for (Path part : path) {
      current = current.resolve(part);
      var attributes =
          Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      require(!attributes.isSymbolicLink() && !attributes.isOther(), "LINK_PATH_REFUSED");
    }
    require(path.toRealPath().equals(path), "LINK_PATH_REFUSED");
  }

  private static <T> T read(Path p, Class<T> type) throws Exception {
    safeExisting(p);
    try (var input = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(65537);
      require(bytes.length <= 65536, "CONFIGURATION_TOO_LARGE");
      return READER.readValue(bytes, type);
    }
  }

  static Path reserveOutput(Path root, Path output) throws Exception {
    Path base = root.resolve(".local");
    require(output.startsWith(base) && !output.equals(base), "PRIVATE_OUTPUT_REQUIRED");
    safeExisting(output.getParent());
    require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS), "OUTPUT_ALREADY_EXISTS");
    if (Files.getFileStore(output.getParent()).supportsFileAttributeView("posix"))
      Files.createDirectory(
          output,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    else Files.createDirectory(output);
    safeExisting(output);
    return output;
  }

  private static void writeNew(Path path, byte[] bytes) throws Exception {
    safeExisting(path.getParent());
    Files.write(
        path,
        bytes,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS);
  }

  private static Map<String, String> options(String[] args) {
    var out = new HashMap<String, String>();
    for (int i = 0; i < args.length; i++) {
      String key = args[i];
      require(
          Set.of(
                      "--allowlist",
                      "--plan",
                      "--credentials",
                      "--output",
                      "--execute",
                      "--save-original")
                  .contains(key)
              && !out.containsKey(key),
          "INVALID_ARGUMENTS");
      String value = "true";
      if (!Set.of("--execute", "--save-original").contains(key)) {
        require(++i < args.length && !args[i].startsWith("--"), "INVALID_ARGUMENTS");
        value = args[i];
      }
      out.put(key, value);
    }
    return out;
  }
}
