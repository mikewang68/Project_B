import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Runs actual HTTP handlers, auth, SQL, event transactions and consume(), without any broker. */
public class SecurityHttpTest {
    static final String A = "TEST_ONLY_SYNTHETIC_A_00000000000000000000";
    static final String B = "TEST_ONLY_SYNTHETIC_B_00000000000000000000";
    static final String R = "TEST_ONLY_SYNTHETIC_R_00000000000000000000";
    static final String LEGACY = "TEST_ONLY_LEGACY_000000000000000000000000";
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    static int checks;
    static String base;
    static RejectionAudit audit(Path path, long capacity) throws Exception {
        // Windows JDK 17 returns null fileKey. This explicit test seam is never used by startup.
        return new RejectionAudit(path, capacity, (p, attributes) -> "synthetic-test-file-key:" + p);
    }
    static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++; System.out.println("PASS " + name);
    }
    static ObjectNode policyJson() throws Exception {
        ObjectNode root = DeviceIngress.JSON.createObjectNode(); root.put("version", 1);
        var devices = root.putArray("devices");
        for (String id : List.of("A", "B")) devices.addObject().put("device_id", "SIM-TEST-" + id).put("project_id", "SIM-PROJECT-" + id).put("area_id", "SIM-AREA-" + id);
        var principals = root.putArray("principals");
        for (String id : List.of("A", "B", "R")) {
            var p = principals.addObject().put("principal_id", "synthetic-" + id)
                    .put("token_sha256", DeviceIngress.sha((id.equals("A") ? A : id.equals("B") ? B : R).getBytes(StandardCharsets.UTF_8)));
            p.putArray("read_devices").add("SIM-TEST-" + (id.equals("R") ? "A" : id));
            var write = p.putArray("write_devices"); if (!id.equals("R")) write.add("SIM-TEST-" + id);
        }
        return root;
    }
    static AccessPolicy policy() throws Exception { return AccessPolicy.parse(DeviceIngress.JSON.writeValueAsBytes(policyJson())); }
    static ObjectNode event(String device, int seq) {
        ObjectNode n = DeviceIngress.JSON.createObjectNode();
        n.put("schema_version", "b.device-event.v1").put("simulated", true).put("run_id", "test-run").put("boot_id", "test-boot")
                .put("device_id", device).put("seq", seq).put("event_id", "test-run:test-boot:" + device + ":" + seq)
                .put("observed_ts_ms", 1000 + seq).putNull("source_ts_ms").put("timestamp_origin", "gateway_observation");
        var point = n.putObject("payload").putArray("points").addObject();
        point.put("point_code", "TEST-P1").put("quality_code", "GOOD").put("value", seq).putNull("unit");
        point.putObject("provenance").put("simulated_fixture", true); return n;
    }
    static byte[] wire(JsonNode n) throws Exception { return DeviceIngress.JSON.writeValueAsBytes(n); }
    static String esc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static HttpResponse<String> request(String method, String path, String token, byte[] body) throws Exception {
        return request(method, path, token, body, "application/json");
    }
    static HttpResponse<String> request(String method, String path, String token, byte[] body, String contentType) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(10));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", contentType);
        return CLIENT.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static JsonNode response(HttpResponse<String> response, int status, String reason, String test) throws Exception {
        JsonNode n = DeviceIngress.JSON.readTree(response.body());
        check(response.statusCode() == status && (reason == null || reason.equals(n.path("error").asText())), test + " (" + response.statusCode() + ")");
        return n;
    }
    static void deniedPolicy(ObjectNode n, String name) throws Exception {
        try { AccessPolicy.parse(wire(n)); throw new AssertionError(name); }
        catch (IllegalArgumentException | DeviceIngress.Problem ex) { check(true, name); }
    }
    static void start(DeviceIngress app) throws Exception {
        app.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        app.server.setExecutor(app.http); app.server.createContext(DeviceIngress.BASE, app::handle); app.running = true; app.server.start();
        base = "http://127.0.0.1:" + app.server.getAddress().getPort() + DeviceIngress.BASE;
    }
    static long inboxCount(Connection c) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + DeviceIngress.INBOX)) { r.next(); return r.getLong(1); }
    }
    static void legacyHttp() throws Exception {
        DeviceIngress legacy = new DeviceIngress(); legacy.token = LEGACY.getBytes(StandardCharsets.UTF_8);
        try {
            start(legacy);
            response(request("POST", "/events", null, wire(event("SIM-LEGACY", 1))), 401, "UNAUTHORIZED", "legacy anonymous POST denied");
            byte[] asset = "synthetic-legacy-asset-content".getBytes(StandardCharsets.UTF_8);
            JsonNode uploaded = response(request("POST", "/assets", LEGACY, asset, "application/octet-stream"), 200, null, "legacy binary asset upload");
            String digest = DeviceIngress.sha(asset);
            check(uploaded.path("sha256").asText().equals(digest), "legacy asset digest unchanged");
            HttpResponse<String> downloaded = request("GET", "/assets?sha256=" + digest, LEGACY, null);
            check(downloaded.statusCode() == 200 && downloaded.body().equals(new String(asset, StandardCharsets.UTF_8)), "legacy binary asset download unchanged");
            response(request("GET", "/assets?sha256=" + digest, null, null), 401, "UNAUTHORIZED", "legacy asset authentication required");
            ObjectNode missingAsset = event("SIM-LEGACY", 2);
            String missingDigest = DeviceIngress.sha("synthetic-never-uploaded-asset".getBytes(StandardCharsets.UTF_8));
            ((ObjectNode)missingAsset.path("payload").path("points").get(0)).putObject("value")
                    .put("sha256", missingDigest).put("path", DeviceIngress.BASE + "/assets?sha256=" + missingDigest);
            response(request("POST", "/events", LEGACY, wire(missingAsset)), 400, "ASSET_NOT_FOUND", "legacy missing asset response unchanged");
            ObjectNode event = event("SIM-LEGACY", 1);
            ((ObjectNode) event.path("payload").path("points").get(0)).putObject("value").put("sha256", digest).put("path", DeviceIngress.BASE + "/assets?sha256=" + digest);
            byte[] bytes = wire(event);
            response(request("POST", "/events", LEGACY, bytes), 202, null, "legacy event with asset accepted");
            check(legacy.consume(bytes, "local-legacy-test"), "legacy event consumer commits");
            check(response(request("POST", "/events", LEGACY, bytes), 202, null, "legacy retry accepted").path("duplicate").asBoolean(), "legacy retry remains idempotent");
            String id = event.path("event_id").asText();
            response(request("GET", "/events?event_id=" + esc(id), LEGACY, null), 200, null, "legacy event read");
            check(response(request("GET", "/latest?device_id=SIM-LEGACY", LEGACY, null), 200, null, "legacy latest read").path("event_id").asText().equals(id), "legacy latest event matches");
            response(request("GET", "/points/latest?device_id=SIM-LEGACY&point_code=TEST-P1", LEGACY, null), 200, null, "legacy point read");
            check(response(request("GET", "/history", LEGACY, null), 200, null, "legacy unfiltered history allowed").path("items").size() >= 4, "legacy remains explicitly shared");
            JsonNode page = response(request("GET", "/history?device_id=SIM-LEGACY", LEGACY, null), 200, null, "legacy filtered history");
            check(page.path("items").size() == 1 && page.path("items").get(0).path("event_id").asText().equals(id), "legacy history filter unchanged");
            check(response(request("GET", "/history?device_id=SIM-LEGACY&cursor=" + esc(page.path("next_cursor").asText()), LEGACY, null), 200, null, "legacy cursor resume").path("items").isEmpty(), "legacy cursor reaches tail");
            check(response(request("GET", "/health", null, null), 200, null, "legacy health").path("access_mode").asText().equals("legacy_shared_unisolated"), "legacy HTTP explicitly labeled unisolated");
        } finally { legacy.close(); }
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--config-probe")) {
            DeviceIngress probe = new DeviceIngress();
            try {
                probe.configureSecurity();
                System.out.println(probe.policy == null ? "LEGACY_SHARED_UNISOLATED" : "SCOPED_CONFIG_READY");
            } catch (Exception ex) {
                System.out.println("SECURITY_CONFIG_REJECTED");
                if ("AUDIT_FILE_IDENTITY_UNAVAILABLE".equals(ex.getMessage())) System.out.println("AUDIT_FILE_IDENTITY_UNAVAILABLE");
                System.exit(2);
            }
            finally { probe.close(); }
            return;
        }
        Path temp = Files.createTempDirectory(Path.of(args[0]), "security-http-");
        try (Connection testDb = DriverManager.getConnection(System.getenv("DEVICE_DB_URL"), "sa", "synthetic-test-password")) {
            try (Statement schema = testDb.createStatement()) { schema.execute("CREATE SCHEMA IF NOT EXISTS bpoc"); }
            ObjectNode invalid = policyJson(); ((ObjectNode)invalid.path("principals").get(0)).putArray("read_devices").add("SIM-UNKNOWN"); deniedPolicy(invalid, "unknown device config fails");
            invalid = policyJson(); ((ObjectNode) invalid.path("devices").get(0)).put("area_id", ""); deniedPolicy(invalid, "blank mapping fails");
            invalid = policyJson(); ((ObjectNode) invalid.path("principals").get(1)).put("token_sha256", invalid.path("principals").get(0).path("token_sha256").asText()); deniedPolicy(invalid, "shared token hashes fail");
            invalid = policyJson(); ((ObjectNode) invalid.path("principals").get(0)).put("token_sha256", DeviceIngress.sha(new byte[0])); deniedPolicy(invalid, "empty token hash fails");
            invalid = policyJson(); invalid.put("unexpected", true); deniedPolicy(invalid, "unknown configuration keys fail");
            check(policy().authenticate("") == null, "empty bearer is not authenticated");
            check(policy().authenticate(LEGACY) == null, "legacy token has no scoped principal");
            try { audit(temp, 4096); throw new AssertionError("directory audit"); } catch (java.io.IOException ex) { check(true, "unwritable audit path fails startup"); }
            Path broken = temp.resolve("partial.jsonl"); Files.writeString(broken, "partial");
            try { audit(broken, 4096); throw new AssertionError("partial audit"); } catch (java.io.IOException ex) { check(true, "partial audit record fails startup"); }
            try { new RejectionAudit(temp.resolve("no-identity.jsonl"), 4096, (p, attributes) -> null); throw new AssertionError("null file key"); }
            catch (java.io.IOException ex) { check("AUDIT_FILE_IDENTITY_UNAVAILABLE".equals(ex.getMessage()), "unsupported file identity refuses startup"); }
            DeviceIngress app = new DeviceIngress();
            try {
                app.initSchema();
                // Seed one real legacy row via accept/consume, then enable scope. It must stay invisible.
                byte[] legacy = wire(event("SIM-TEST-A", 90)); app.accept(legacy); check(app.consume(legacy, "local-test"), "legacy fixture committed");
                app.policy = policy(); app.token = LEGACY.getBytes(StandardCharsets.UTF_8);
                app.audit = audit(temp.resolve("reject.jsonl"), 1024 * 1024); app.initScopeSchema();
                AccessPolicy original = app.policy;
                ObjectNode changed = policyJson(); ((ObjectNode) changed.path("devices").get(0)).put("project_id", "SIM-CHANGED");
                app.policy = AccessPolicy.parse(wire(changed));
                try { app.initScopeSchema(); throw new AssertionError("registry changed"); } catch (IllegalStateException ex) { check(true, "registry reassignment fails across restart"); }
                app.policy = original; app.initScopeSchema(); start(app);
                response(request("GET", "/latest?device_id=SIM-TEST-A", "", null), 401, "UNAUTHORIZED", "empty token HTTP rejected");
                response(request("GET", "/latest?device_id=SIM-TEST-A", LEGACY, null), 401, "UNAUTHORIZED", "legacy bypass HTTP rejected");
                long before = inboxCount(testDb);
                response(request("POST", "/events", A, wire(event("SIM-TEST-B", 1))), 403, "DEVICE_SCOPE_DENIED", "cross-device POST rejected");
                response(request("GET", "/latest?device_id=SIM-TEST-B", A, null), 403, "DEVICE_SCOPE_DENIED", "cross-device latest rejected");
                response(request("GET", "/points/latest?device_id=SIM-TEST-B&point_code=TEST-P1", A, null), 403, "DEVICE_SCOPE_DENIED", "cross-device point rejected");
                response(request("GET", "/history?device_id=SIM-TEST-B", A, null), 403, "DEVICE_SCOPE_DENIED", "cross-device history rejected");
                response(request("GET", "/history", A, null), 400, "SCOPED_DEVICE_REQUIRED", "unfiltered history rejected");
                response(request("GET", "/assets?sha256=" + "a".repeat(64), A, null), 403, "ASSETS_DISABLED_IN_SCOPED_MODE", "asset download rejected");
                response(request("POST", "/assets", A, "secret-asset".getBytes(StandardCharsets.UTF_8)), 403, "ASSETS_DISABLED_IN_SCOPED_MODE", "asset upload rejected");
                response(request("POST", "/events", R, wire(event("SIM-TEST-A", 1))), 403, "DEVICE_SCOPE_DENIED", "reader POST rejected");
                ObjectNode spoof = event("SIM-TEST-A", 1); spoof.put("project_id", "SIM-PROJECT-B");
                response(request("POST", "/events", A, wire(spoof)), 403, "DEVICE_BINDING_MISMATCH", "payload project cannot change identity");
                ObjectNode asset = event("SIM-TEST-A", 1); ((ObjectNode)asset.path("payload").path("points").get(0)).putObject("value").put("sha256", "a".repeat(64));
                response(request("POST", "/events", A, wire(asset)), 403, "ASSETS_DISABLED_IN_SCOPED_MODE", "event asset references rejected");
                check(inboxCount(testDb) == before, "preflight denials did not write inbox");
                byte[] first = (DeviceIngress.JSON.writeValueAsString(event("SIM-TEST-A", 1)) + " \n").getBytes(StandardCharsets.UTF_8);
                JsonNode receipt = response(request("POST", "/events", A, first), 202, null, "allowed POST accepted");
                check(receipt.path("wire_sha256").asText().equals(DeviceIngress.sha(first)), "original wire whitespace hash preserved");
                check(app.consume(first, "local-test"), "allowed event consumer commits");
                check(response(request("POST", "/events", A, first), 202, null, "allowed retry accepted").path("duplicate").asBoolean(), "retry is duplicate");
                ObjectNode conflict = event("SIM-TEST-A", 1); ((ObjectNode) conflict.path("payload").path("points").get(0)).put("value", 999);
                response(request("POST", "/events", A, wire(conflict)), 409, "EVENT_ID_CONFLICT", "conflict retains contract");
                response(request("POST", "/events", A, legacy), 403, "LEGACY_EVENT_NOT_SCOPED", "legacy duplicate cannot acquire ownership");
                byte[] second = wire(event("SIM-TEST-B", 1)); response(request("POST", "/events", B, second), 202, null, "second project accepted"); check(app.consume(second, "local-test"), "second project committed");
                String idA = "test-run:test-boot:SIM-TEST-A:1", idB = "test-run:test-boot:SIM-TEST-B:1";
                response(request("GET", "/events?event_id=" + esc(idB), A, null), 403, "DEVICE_SCOPE_DENIED", "event ID cannot bypass scope");
                response(request("GET", "/events?event_id=" + esc("test-run:test-boot:SIM-TEST-A:90"), A, null), 404, "NOT_FOUND", "legacy event invisible by ID");
                response(request("GET", "/events?event_id=" + esc(idA), R, null), 200, null, "reader can read authorized event");
                JsonNode latest = response(request("GET", "/latest?device_id=SIM-TEST-A", A, null), 200, null, "authorized latest"); check(latest.path("event_id").asText().equals(idA), "latest excludes newer legacy event");
                JsonNode point = response(request("GET", "/points/latest?device_id=SIM-TEST-A&point_code=TEST-P1", A, null), 200, null, "authorized point"); check(point.path("event_id").asText().equals(idA), "point excludes legacy event");
                JsonNode page = response(request("GET", "/history?device_id=SIM-TEST-A", A, null), 200, null, "authorized history");
                check(page.path("items").size() == 1 && page.path("items").get(0).path("event_id").asText().equals(idA), "history excludes other project and legacy");
                String cursor = page.path("next_cursor").asText();
                response(request("GET", "/history?device_id=SIM-TEST-B&cursor=" + esc(cursor), B, null), 400, "INVALID_CURSOR", "cross-device cursor denied");
                response(request("GET", "/history?device_id=SIM-TEST-A&cursor=" + esc(cursor), R, null), 400, "INVALID_CURSOR", "cross-principal cursor denied");
                check(response(request("GET", "/history?device_id=SIM-TEST-A&cursor=" + esc(cursor), A, null), 200, null, "same cursor accepted").path("items").isEmpty(), "cursor reaches empty page");
                // Grant revision also invalidates stale cursors.
                changed = policyJson(); ((ObjectNode) changed.path("principals").get(0)).putArray("write_devices"); app.policy = AccessPolicy.parse(wire(changed));
                response(request("GET", "/history?device_id=SIM-TEST-A&cursor=" + esc(cursor), A, null), 400, "INVALID_CURSOR", "changed grants invalidate cursor"); app.policy = original;
                String secret = "SHOULD_NEVER_APPEAR_IN_AUDIT";
                ObjectNode invalidEvent = event("SIM-TEST-A", 2); invalidEvent.put("simulated", false).put("secret", secret);
                response(request("POST", "/events?leak=" + secret, A, wire(invalidEvent)), 400, "SIMULATED_REQUIRED", "invalid event keeps status and code");
                response(request("GET", "/" + secret + "?token=" + secret, A, null), 404, "NOT_FOUND", "unknown path rejected");
                // Concurrency exercises the real two-thread HTTP executor and synchronized fsync log.
                var pool = Executors.newFixedThreadPool(4);
                try {
                    var jobs = new ArrayList<Future<Integer>>();
                    for (int i=0; i<12; i++) jobs.add(pool.submit(() -> request("GET", "/latest?device_id=SIM-TEST-B", A, null).statusCode()));
                    for (Future<Integer> job : jobs) check(job.get() == 403, "concurrent rejection");
                } finally { pool.shutdownNow(); }
                app.audit.close(); // Windows mandatory file locks prevent a second reader while open.
                String audit = Files.readString(temp.resolve("reject.jsonl"));
                check(!audit.contains(A) && !audit.contains(B) && !audit.contains(LEGACY) && !audit.contains(secret) && !audit.contains("secret-asset"), "audit contains no token body query or path secrets");
                for (String line : audit.lines().toList()) {
                    JsonNode row = DeviceIngress.JSON.readTree(line);
                    check(row.size() == 8 && row.path("principal").isTextual() && row.path("received_ms").asLong() > 0, "audit row has fixed valid fields");
                }
                HttpResponse<String> denied = request("GET", "/latest?device_id=SIM-TEST-B", A, null);
                response(denied, 403, "DEVICE_SCOPE_DENIED", "audit failure preserves rejection");
                check(denied.headers().firstValue("X-Audit-Status").orElse("").equals("unavailable"), "audit failure is explicit");
                before = inboxCount(testDb);
                response(request("POST", "/events", A, wire(event("SIM-TEST-A", 3))), 503, "AUDIT_UNAVAILABLE", "closed audit blocks allowed write");
                check(inboxCount(testDb) == before, "audit failure prevented inbox write");
                app.audit = audit(temp.resolve("bounded.jsonl"), 4096);
                for (int i = 0; i < 30; i++) request("GET", "/latest?device_id=SIM-TEST-B", A, null);
                response(request("POST", "/events", A, wire(event("SIM-TEST-A", 4))), 503, "AUDIT_UNAVAILABLE", "full audit blocks writes");
                check(Files.size(temp.resolve("bounded.jsonl")) <= 4096, "audit size remains bounded without deletion");
                check(response(request("GET", "/health", null, null), 200, null, "health liveness accessible").path("access_mode").asText().equals("scoped_v1"), "health labels scope mode");
                app.audit.close();
                AtomicReference<String> identity = new AtomicReference<>("original-file-key");
                app.audit = new RejectionAudit(temp.resolve("identity-change.jsonl"), 8192, (p, attributes) -> identity.get());
                identity.set("replacement-file-key");
                HttpResponse<String> replaced = request("GET", "/latest?device_id=SIM-TEST-B", A, null);
                response(replaced, 403, "DEVICE_SCOPE_DENIED", "replacement during append preserves original denial");
                check(replaced.headers().firstValue("X-Audit-Status").orElse("").equals("unavailable"), "replacement identity reports audit unavailable");
                identity.set("original-file-key"); before = inboxCount(testDb);
                response(request("POST", "/events", A, wire(event("SIM-TEST-A", 5))), 503, "AUDIT_UNAVAILABLE", "identity failure stays latched after restoration");
                check(inboxCount(testDb) == before, "identity failure prevents DB writes");
                app.audit.close();
                AtomicReference<Boolean> missing = new AtomicReference<>(false);
                Path missingPath = temp.resolve("identity-missing.jsonl");
                app.audit = new RejectionAudit(missingPath, 8192, (p, attributes) -> {
                    if (missing.get()) throw new NoSuchFileException("synthetic-missing-path");
                    return "original-file-key";
                });
                missing.set(true);
                response(request("POST", "/events", A, wire(event("SIM-TEST-A", 6))), 503, "AUDIT_UNAVAILABLE", "missing audit identity blocks write admission");
                check(inboxCount(testDb) == before, "missing audit path prevents DB writes");
                missing.set(false);
                response(request("POST", "/events", A, wire(event("SIM-TEST-A", 6))), 503, "AUDIT_UNAVAILABLE", "missing path failure stays latched");
                app.audit.close();
                Path unlink = temp.resolve("unlink-native.jsonl"); app.audit = audit(unlink, 8192);
                boolean deleted = false;
                try { Files.delete(unlink); deleted = true; }
                catch (FileSystemException platformLock) { System.out.println("PLATFORM_LIMITATION Windows/open-handle locking prevented actual unlink; file identity failures were injected."); }
                if (deleted) {
                    response(request("POST", "/events", A, wire(event("SIM-TEST-A", 7))), 503, "AUDIT_UNAVAILABLE", "real unlink blocks admission");
                    check(inboxCount(testDb) == before, "real unlink prevents DB writes");
                }
            } finally { app.close(); }
            legacyHttp();
            ScopedAssetHttpTest.run(temp, testDb);
            JdbcSessionTest.run(temp);
            System.out.println("SECURITY_HTTP_TEST_PASS checks=" + checks);
            System.out.println("LIMITATION H2 PostgreSQL compatibility mode; no broker or openGauss integration was exercised.");
            System.out.println("LIMITATION scoped runtime tests use an explicit synthetic file-key reader; production requires native non-null fileKey.");
        } finally {
            try (var paths = Files.walk(temp)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
}
