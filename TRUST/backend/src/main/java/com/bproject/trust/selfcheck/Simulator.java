package com.bproject.trust.selfcheck;

import com.bproject.trust.ingress.*;
import com.bproject.trust.shared.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** A bounded, loopback-only contract simulator. It never starts the business application. */
public final class Simulator {
  public static final String BASE = "/api/v1/integrations/evidence/events";
  public static final Set<String> SCENARIOS = Set.of("normal", "idempotency", "correction",
      "permissions", "disconnect", "unavailable", "timeout", "action-required",
      "retry-ready", "retry-done", "retry-running", "retry-failed", "retry-invalid");
  private final EvidenceIngressSettings.Source source;
  private final String scene;
  private final Map<String, Record> records = new LinkedHashMap<>();
  private final Map<String, String> numbers = new HashMap<>();
  private final Set<String> seen = new LinkedHashSet<>();
  private final List<String> failures = new ArrayList<>();
  private final String token = "selfcheck-" + UUID.randomUUID();
  private final String readOnly = "selfcheck-readonly-" + UUID.randomUUID();
  private final String other = "selfcheck-other-" + UUID.randomUUID();
  private final String otherOrg = "selfcheck-otherorg-" + UUID.randomUUID();
  private int calls;
  private boolean executionError;

  private Simulator(EvidenceIngressSettings.Source source, String scene) {
    this.source = source; this.scene = scene;
  }

  public static int run(Path config, String scenario, int seconds, Path connection, Path report)
      throws Exception {
    if (!SCENARIOS.contains(scenario) || seconds < 1 || seconds > 600)
      throw new IllegalArgumentException("Unknown scenario or duration outside 1..600");
    privatePath(connection); privatePath(report);
    if (connection.toAbsolutePath().normalize().equals(report.toAbsolutePath().normalize()))
      throw new IllegalArgumentException("Connection and report paths must differ");
    var sim = new Simulator(Profile.read(config), scenario);
    sim.writeReport(report, false, 2);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newSingleThreadExecutor(r -> {
      var t = new Thread(r, "trust-selfcheck-loopback"); t.setDaemon(true); return t;
    });
    server.setExecutor(executor);
    server.createContext("/", sim::handle);
    Thread hook = new Thread(() -> {
      server.stop(0);
      // The initial incomplete report remains authoritative if execution is interrupted.
    }, "trust-selfcheck-stop");
    Runtime.getRuntime().addShutdownHook(hook);
    try {
      server.start();
      var info = new LinkedHashMap<String, Object>();
      info.put("mode", "local-simulation"); info.put("scenario", scenario);
      info.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + BASE);
      info.put("token", sim.token); info.put("readOnlyToken", sim.readOnly);
      info.put("otherSourceToken", sim.other); info.put("otherOrgToken", sim.otherOrg);
      info.put("expiresAt", Instant.now().plusSeconds(seconds).toString());
      info.put("requiredChecks", sim.required());
      write(connection, info);
      TimeUnit.SECONDS.sleep(seconds);
      server.stop(0);
      synchronized (sim) {
        boolean complete = sim.seen.containsAll(sim.required());
        int code = sim.executionError ? 2 : !sim.failures.isEmpty() ? 1 : complete ? 0 : 2;
        sim.writeReport(report, !sim.executionError, code);
        return code;
      }
    } finally {
      server.stop(0); executor.shutdownNow();
      Runtime.getRuntime().removeShutdownHook(hook);
    }
  }

  static void privatePath(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    boolean privateDirectory = false;
    for (Path part : absolute) if (part.toString().equals(".local")) privateDirectory = true;
    if (!privateDirectory) throw new IllegalArgumentException("Output must be inside .local");
    Path current = absolute.getRoot();
    for (Path part : absolute) {
      current = current.resolve(part);
      if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symlink output is forbidden");
    }
    Files.createDirectories(absolute.getParent());
  }

  private static void write(Path path, Object value) throws IOException {
    Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".selfcheck-", ".tmp");
    try {
      Files.writeString(temporary, Json.write(value), StandardCharsets.UTF_8);
      try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    } finally { Files.deleteIfExists(temporary); }
  }

  private List<String> required() {
    return requiredChecks(scene);
  }

  public static List<String> requiredChecks(String scenario) {
    if (!SCENARIOS.contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
    return switch (scenario) {
      case "normal" -> List.of("accepted", "query-number", "query-id", "processing", "committed");
      case "idempotency" -> List.of("accepted", "equivalent-replay", "content-conflict", "original-preserved");
      case "correction" -> List.of("accepted", "corrected", "correction-conflict", "correction-mismatch");
      case "permissions" -> List.of("accepted", "missing-credential", "wrong-credential", "operation-denied", "type-denied", "cross-source", "cross-org", "not-found");
      case "disconnect", "unavailable" -> List.of("accepted", "unknown-injected", "query-number", "query-id", "committed");
      case "timeout" -> List.of("accepted", "waiting", "waiting-again");
      case "action-required" -> List.of("accepted", "action-required");
      case "retry-invalid" -> List.of("accepted", "invalid-retry", "retry-not-queued");
      case "retry-running" -> List.of("accepted", "retry-running-conflict");
      case "retry-failed" -> List.of("accepted", "action-required", "retry-queued", "retry-not-queued");
      default -> List.of("accepted", "retry-not-queued");
    };
  }

  private synchronized void writeReport(Path path, boolean complete, int code) throws IOException {
    var checks = new ArrayList<Map<String, Object>>();
    for (String expected : required()) checks.add(Map.of("name", expected, "passed", seen.contains(expected),
        "reason", seen.contains(expected) ? "Observed by local simulator" : "Required interaction not observed"));
    for (String failure : failures) checks.add(Map.of("name", "unexpected-interaction", "passed", false, "reason", failure));
    var report = new LinkedHashMap<String, Object>();
    report.put("formatVersion", 1); report.put("mode", "local-simulation");
    report.put("contractVersion", Reports.CONTRACT); report.put("buildVersion", Reports.BUILD);
    report.put("scenario", scene); report.put("completed", complete); report.put("exitCode", code);
    report.put("scenarioSatisfied", seen.containsAll(required()) && failures.isEmpty());
    report.put("checks", checks); report.put("requestCount", calls);
    report.put("limitations", List.of("Local simulation only; caller business-state assertions are required separately", "No database, Fabric, IPFS or transaction consistency verified"));
    write(path, report);
  }

  private synchronized void handle(HttpExchange exchange) throws IOException {
    try {
      if (++calls > 64) { fail("Request budget exceeded (64); unbounded retry/polling"); respond(exchange, 429, Map.of("code", "SELFCHECK_REQUEST_BUDGET", "message", "Local simulation request budget exceeded")); return; }
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      if (exchange.getRequestURI().getRawQuery() != null || !(path.equals(BASE) || path.startsWith(BASE + "/"))) {
        fail("Wrong API path or unsupported query string"); respond(exchange, 404, Map.of("code", "SELFCHECK_WRONG_PATH", "message", "Unsupported local simulation path")); return;
      }
      String auth = exchange.getRequestHeaders().getFirst("Authorization");
      if (auth == null) throw expected("missing-credential", new IngressError(401, "INVALID_SOURCE_CREDENTIAL", "通用来源凭据无效"), scene.equals("permissions"));
      boolean primary = auth.equals("Bearer " + token), restricted = auth.equals("Bearer " + readOnly);
      boolean foreign = auth.equals("Bearer " + other), foreignOrg = auth.equals("Bearer " + otherOrg);
      if (!(primary || restricted || foreign || foreignOrg)) throw expected("wrong-credential", new IngressError(401, "INVALID_SOURCE_CREDENTIAL", "通用来源凭据无效"), scene.equals("permissions"));
      if (restricted && method.equals("POST")) throw expected("operation-denied", new IngressError(403, "SOURCE_OPERATION_DENIED", "来源操作未获授权"), scene.equals("permissions"));
      if ((foreign || foreignOrg) && method.equals("POST")) {
        fail("Alternate scope credentials are query-only simulator fixtures");
        throw new ExpectedError(new IngressError(403, "SOURCE_OPERATION_DENIED", "来源操作未获授权"));
      }
      if (path.equals(BASE + "/capabilities") && method.equals("GET")) {
        source.require("read"); respond(exchange, 200, Map.of("instanceId", "selfcheck-local", "isolationEnabled", true, "ingressSchemaVersions", List.of(1))); return;
      }
      if (path.equals(BASE) && method.equals("POST")) { submit(exchange); return; }
      String suffix = path.startsWith(BASE + "/") ? path.substring(BASE.length() + 1) : "";
      boolean byNumber = suffix.startsWith("by-source-event/");
      boolean retry = suffix.endsWith("/retry");
      String key = byNumber ? suffix.substring("by-source-event/".length()) : retry ? suffix.substring(0, suffix.length() - 6) : suffix;
      if (key.isEmpty() || key.contains("/") || (retry ? !method.equals("POST") : !method.equals("GET"))) {
        fail("Wrong API path or HTTP method"); respond(exchange, 404, Map.of("code", "SELFCHECK_WRONG_PATH", "message", "Unsupported local simulation path")); return;
      }
      if (retry) {
        source.require("retry");
        try { GenericHttpInput.retry(exchange.getRequestBody().readNBytes(65)); }
        catch (IngressError e) { throw expected("invalid-retry", e, scene.equals("retry-invalid")); }
      } else source.require("read");
      Record record = records.get(byNumber ? numbers.get(key) : key);
      if (foreign || foreignOrg) {
        // Only an actual owner's record proves a cross-scope denial.
        throw expected(foreign ? "cross-source" : "cross-org", IngressError.missing(), scene.equals("permissions") && record != null);
      }
      if (record == null) throw expected("not-found", IngressError.missing(), scene.equals("permissions") && !records.isEmpty());
      if (retry) retry(exchange, record); else query(exchange, record, byNumber);
    } catch (ExpectedError e) { respond(exchange, e.error.status, e.error.body()); }
    catch (IngressError e) { fail("Unexpected contract rejection: " + e.status + " " + e.code); respond(exchange, e.status, e.body()); }
    catch (Exception e) { executionError = true; fail("Simulator execution failure: " + e.getClass().getSimpleName()); respond(exchange, 500, Map.of("code", "SELFCHECK_EXECUTION_ERROR", "message", "Local simulation failed")); }
    finally { exchange.close(); }
  }

  private void submit(HttpExchange ex) throws IOException {
    source.require("submit");
    GenericHttpInput.mediaType(ex.getRequestHeaders().getFirst("Content-Type"), ex.getRequestHeaders().getFirst("Content-Encoding"));
    var input = GenericEventInput.parse(ex.getRequestBody().readNBytes(GenericEventInput.LIMIT + 1));
    try { source.type(input.eventType()); }
    catch (IngressError e) { throw expected("type-denied", e, scene.equals("permissions")); }
    Record old = records.get(numbers.get(input.sourceEventId()));
    if (old != null) {
      if (!old.input.request(source.sourceSystem()).equals(input.request(source.sourceSystem())))
        throw expected("content-conflict", new IngressError(409, "IDEMPOTENCY_CONFLICT", "同一来源事件号已经登记了不同内容"), scene.equals("idempotency"));
      if (!scene.equals("idempotency")) fail("Unexpected duplicate submit; recover or poll using the original event number");
      if (!old.input.original().equals(input.original())) {
        seen.add("equivalent-replay"); seen.add("original-preserved");
      }
      var receipt = receipt(old); receipt.put("replayed", true); respond(ex, 200, receipt); return;
    }
    String id = UUID.randomUUID().toString(), root = id; int version = 1;
    if (input.supersedesId() != null) {
      Record prev = records.get(input.supersedesId());
      if (prev == null) throw IngressError.missing();
      if (!prev.input.eventType().equals(input.eventType()) || !prev.input.businessObjectId().equals(input.businessObjectId()))
        throw expected("correction-mismatch", new IngressError(400, "INVALID_REQUEST", "更正必须保持事件类型及业务对象"), scene.equals("correction"));
      if (records.values().stream().anyMatch(r -> input.supersedesId().equals(r.input.supersedesId())))
        throw expected("correction-conflict", new IngressError(409, "CORRECTION_CONFLICT", "请基于最新版本追加更正"), scene.equals("correction"));
      root = prev.root; version = prev.version + 1;
      if (!scene.equals("correction")) fail("Unexpected correction in selected scenario");
      seen.add("corrected");
    } else if (!records.isEmpty()) fail("Changed source event number instead of querying/retrying the original event");
    String canonical = GenericEventRules.canonical(source, input, id, root, version);
    Record record = new Record(input, id, root, version, Json.sha(canonical.getBytes(StandardCharsets.UTF_8)));
    records.put(id, record); numbers.put(input.sourceEventId(), id); seen.add("accepted");
    // The production insert returns its READY receipt within the same transaction. Fixture
    // transitions become visible only to subsequent queries/retries, like post-commit work.
    var acceptedReceipt = receipt(record); acceptedReceipt.put("replayed", false);
    if (scene.equals("retry-done")) { record.task = "DONE"; record.chain = "COMMITTED"; }
    if (scene.equals("retry-running")) record.task = "RUNNING";
    if (scene.equals("action-required") || scene.equals("retry-failed")) record.task = "FAILED";
    if ((scene.equals("disconnect") || scene.equals("unavailable")) && records.size() == 1) {
      seen.add("unknown-injected");
      if (scene.equals("disconnect")) { ex.close(); return; }
      respond(ex, 503, Map.of("code", "PERSISTENCE_UNAVAILABLE", "message", "持久化暂不可用，请按原来源事件号查询")); return;
    }
    ex.getResponseHeaders().set("Location", BASE + "/" + id); respond(ex, 202, acceptedReceipt);
  }

  private void query(HttpExchange ex, Record r, boolean byNumber) throws IOException {
    if ((scene.equals("disconnect") || scene.equals("unavailable")) && !seen.contains("query-number") && !byNumber)
      fail("Unknown submit result must first be recovered by original source event number");
    seen.add(byNumber ? "query-number" : "query-id"); r.polls++;
    if (scene.equals("normal") || scene.equals("disconnect") || scene.equals("unavailable")) {
      r.task = r.polls == 1 ? "RUNNING" : "DONE";
      r.chain = r.polls == 1 ? "PENDING" : "COMMITTED";
      seen.add(r.polls == 1 ? "processing" : "committed");
    }
    if (scene.equals("timeout")) seen.add(r.polls == 1 ? "waiting" : "waiting-again");
    if (r.task.equals("FAILED")) seen.add("action-required");
    respond(ex, 200, receipt(r));
  }

  private void retry(HttpExchange ex, Record r) throws IOException {
    source.type(r.input.eventType());
    if (!scene.startsWith("retry-")) fail("Polling/recovery must not issue unsolicited explicit retry");
    boolean queued;
    try { queued = GenericEventRules.retryQueued(r.task, r.chain); }
    catch (IngressError e) { throw expected("retry-running-conflict", e, scene.equals("retry-running") && e.code.equals("TASK_RUNNING")); }
    if (queued) { r.task = "READY"; seen.add("retry-queued"); } else seen.add("retry-not-queued");
    var receipt = receipt(r); receipt.put("queued", queued); respond(ex, queued ? 202 : 200, receipt);
  }

  private Map<String, Object> receipt(Record r) {
    boolean committed = r.chain.equals("COMMITTED");
    var row = new HashMap<String, Object>();
    row.put("id", r.id); row.put("source_system", source.sourceSystem()); row.put("source_event_id", r.input.sourceEventId());
    row.put("root_id", r.root); row.put("version", r.version); row.put("supersedes_id", r.input.supersedesId());
    row.put("event_sha256", r.sha); row.put("original_sha256", Json.sha(r.input.original().getBytes(StandardCharsets.UTF_8)));
    row.put("manifest_cid", committed ? "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3ptew7v2j3ydemjkpdsxvzn3a" : null);
    row.put("manifest_sha256", committed ? Json.sha("synthetic-manifest".getBytes(StandardCharsets.UTF_8)) : null);
    row.put("original_cid", row.get("manifest_cid")); row.put("tx_id", committed ? Json.sha("synthetic-transaction".getBytes(StandardCharsets.UTF_8)) : null);
    row.put("block_number", committed ? 1L : null); row.put("file_state", committed ? "STORED" : "PENDING");
    row.put("chain_state", r.chain); row.put("task_state", r.task); row.put("attempts", 0);
    row.put("uncertain", false); row.put("last_error_code", r.task.equals("FAILED") ? "SIGNING_UNAVAILABLE" : null);
    return GenericEventRules.receipt(row);
  }

  private ExpectedError expected(String check, IngressError error, boolean allowed) {
    if (allowed) seen.add(check); else fail("Unexpected rejection: " + error.status + " " + error.code);
    return new ExpectedError(error);
  }
  private void fail(String reason) { if (failures.size() < 20) failures.add(reason); }
  private static void respond(HttpExchange ex, int status, Object body) throws IOException {
    byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
    ex.getResponseHeaders().set("X-TRUST-Selfcheck", "local-simulation");
    ex.sendResponseHeaders(status, bytes.length); ex.getResponseBody().write(bytes);
  }
  private static final class ExpectedError extends RuntimeException {
    final IngressError error; ExpectedError(IngressError error) { this.error = error; }
  }
  private static final class Record {
    final GenericEventInput input; final String id, root, sha; final int version;
    String task = "READY", chain = "PENDING"; int polls;
    Record(GenericEventInput input, String id, String root, int version, String sha) {
      this.input = input; this.id = id; this.root = root; this.version = version; this.sha = sha;
    }
  }
}
