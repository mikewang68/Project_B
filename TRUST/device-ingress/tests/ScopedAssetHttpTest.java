import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;

/** Additional real HTTP/H2 tests; never starts a broker consumer. */
final class ScopedAssetHttpTest extends SecurityHttpTest {
    static void run(Path temp, Connection db) throws Exception {
        DeviceIngress app = new DeviceIngress();
        byte[] asset = "synthetic-scoped-waveform".getBytes(StandardCharsets.UTF_8);
        String digest = DeviceIngress.sha(asset);
        String a = "/assets?device_id=SIM-TEST-A", b = "/assets?device_id=SIM-TEST-B";
        try {
            app.policy = policy(); app.scopedAssets = true;
            app.audit = audit(temp.resolve("scoped-assets.jsonl"), 1024 * 1024);
            app.initScopeSchema(); start(app);
            response(request("POST", "/assets", A, asset, "application/octet-stream"), 400, "MISSING_PARAMETER", "scoped upload requires device");
            response(request("POST", b, A, asset, "application/octet-stream"), 403, "DEVICE_SCOPE_DENIED", "cross-area upload denied");
            response(request("POST", a, R, asset, "application/octet-stream"), 403, "DEVICE_SCOPE_DENIED", "reader cannot upload");
            response(request("POST", a, A, asset, "application/octet-stream"), 200, null, "authorized scoped upload");
            response(request("POST", a, A, asset, "application/octet-stream"), 200, null, "scoped upload idempotent");
            response(request("GET", "/assets?sha256=" + digest, A, null), 400, "MISSING_PARAMETER", "hash alone not a read capability");
            response(request("GET", b + "&sha256=" + digest, A, null), 403, "DEVICE_SCOPE_DENIED", "cross-area asset read denied");
            response(request("GET", b + "&sha256=" + digest, B, null), 404, "NOT_FOUND", "own-device query cannot borrow other device content");
            var downloaded = request("GET", a + "&sha256=" + digest, R, null);
            check(downloaded.statusCode() == 200 && downloaded.body().equals(new String(asset, StandardCharsets.UTF_8)), "reader downloads exact authorized bytes");
            ObjectNode revoked = policyJson();
            ((ObjectNode)revoked.path("principals").get(2)).putArray("read_devices");
            app.policy = AccessPolicy.parse(wire(revoked));
            response(request("GET", a + "&sha256=" + digest, R, null), 403, "DEVICE_SCOPE_DENIED", "current grant revocation blocks existing asset");
            app.policy = policy();
            String oldDigest = DeviceIngress.sha("synthetic-legacy-asset-content".getBytes(StandardCharsets.UTF_8));
            response(request("GET", a + "&sha256=" + oldDigest, A, null), 404, "NOT_FOUND", "legacy asset not silently granted");
            ObjectNode eventA = event("SIM-TEST-A", 201), eventB = event("SIM-TEST-B", 201);
            for (ObjectNode n : new ObjectNode[]{eventA, eventB})
                ((ObjectNode)n.path("payload").path("points").get(0)).putObject("value").put("sha256", digest).put("path", DeviceIngress.BASE + "/assets?sha256=" + digest);
            long before = inboxCount(db);
            response(request("POST", "/events", B, wire(eventB)), 404, "NOT_FOUND", "event cannot reference unowned asset");
            check(inboxCount(db) == before, "unowned reference writes no inbox row");
            String[] unboundDigests = {
                    DeviceIngress.sha("synthetic-never-uploaded-asset".getBytes(StandardCharsets.UTF_8)), digest, oldDigest};
            String[] unboundKinds = {"absent", "other-device", "legacy"};
            for (int i = 0; i < unboundDigests.length; i++) {
                ObjectNode unbound = event("SIM-TEST-B", 210 + i);
                ((ObjectNode)unbound.path("payload").path("points").get(0)).putObject("value")
                        .put("sha256", unboundDigests[i]).put("path", DeviceIngress.BASE + "/assets?sha256=" + unboundDigests[i]);
                response(request("POST", "/events", B, wire(unbound)), 404, "NOT_FOUND",
                        "unbound " + unboundKinds[i] + " asset has uniform event rejection");
                check(inboxCount(db) == before, "unbound " + unboundKinds[i] + " reference writes no inbox row");
            }
            ObjectNode malformed = eventA.deepCopy();
            ((ObjectNode)malformed.path("payload").path("points").get(0).path("value")).remove("path");
            response(request("POST", "/events", A, wire(malformed)), 400, "INVALID_ASSET_REFERENCE", "digest-only event reference rejected");
            byte[] original = wire(eventA);
            response(request("POST", "/events", A, original), 202, null, "owned waveform event accepted");
            check(app.consume(original, "local-scoped-asset"), "owned waveform event committed");
            check(response(request("POST", "/events", A, original), 202, null, "owned event retry").path("duplicate").asBoolean(), "asset event idempotency preserved");
            check(response(request("GET", "/events?event_id=" + esc(eventA.path("event_id").asText()), R, null), 200, null, "reader scoped event").path("wire_sha256").asText().equals(DeviceIngress.sha(original)), "asset event original wire preserved");
            response(request("POST", b, B, asset, "application/octet-stream"), 200, null, "same bytes require explicit second-device upload");
            response(request("POST", "/events", B, wire(eventB)), 202, null, "second device may reference after explicit upload");
            check(app.consume(wire(eventB), "local-scoped-asset"), "second device asset event committed");
            try (var s = db.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM " + DeviceIngress.ASSET_OWNERS + " WHERE sha256='" + digest + "'")) {
                rows.next(); check(rows.getInt(1) == 2, "digest deduplicated with two explicit device grants");
            }
            byte[] rollback = "synthetic-transaction-rollback".getBytes(StandardCharsets.UTF_8);
            String rollbackDigest = DeviceIngress.sha(rollback);
            try (var s = db.createStatement()) {
                s.execute("ALTER TABLE " + DeviceIngress.ASSET_OWNERS + " ADD CONSTRAINT synthetic_asset_failure CHECK (sha256 <> '" + rollbackDigest + "')");
            }
            try {
                response(request("POST", a, A, rollback, "application/octet-stream"), 503, "DATABASE_UNAVAILABLE", "binding insertion SQL failure reported");
                try (var s = db.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM " + DeviceIngress.ASSETS + " WHERE sha256='" + rollbackDigest + "'")) {
                    rows.next(); check(rows.getInt(1) == 0, "asset content rolls back with failed binding");
                }
            } finally {
                try (var s = db.createStatement()) { s.execute("ALTER TABLE " + DeviceIngress.ASSET_OWNERS + " DROP CONSTRAINT synthetic_asset_failure"); }
            }
            app.audit.close(); app.audit = audit(temp.resolve("scoped-asset-full.jsonl"), 4096);
            for (int i = 0; i < 100; i++) if (!app.audit.append("synthetic-A", 403, "TEST", "post:assets", null)) break;
            byte[] blocked = "must-not-persist".getBytes(StandardCharsets.UTF_8);
            response(request("POST", a, A, blocked, "application/octet-stream"), 503, "AUDIT_UNAVAILABLE", "full audit blocks asset upload");
            try (var s = db.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM " + DeviceIngress.ASSETS + " WHERE sha256='" + DeviceIngress.sha(blocked) + "'")) {
                rows.next(); check(rows.getInt(1) == 0, "audit failure leaves no asset bytes");
            }
        } finally { app.close(); }
        DeviceIngress restarted = new DeviceIngress();
        try {
            restarted.policy = policy(); restarted.scopedAssets = true;
            restarted.audit = audit(temp.resolve("scoped-asset-restarted.jsonl"), 8192);
            restarted.initScopeSchema(); start(restarted);
            var downloaded = request("GET", a + "&sha256=" + digest, R, null);
            check(downloaded.statusCode() == 200 && downloaded.body().equals(new String(asset, StandardCharsets.UTF_8)), "asset binding survives restart");
        } finally { restarted.close(); }
    }
}
