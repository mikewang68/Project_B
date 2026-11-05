import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.Message;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Isolated simulated-device ingress. Java 17; no legacy application changes. */
public final class DeviceIngress {
    static final String BASE = "/api/device-data/v1";
    static final String INBOX = "bpoc.device_sim_v1_inbox";
    static final String POINTS = "bpoc.device_sim_v1_points";
    static final String ASSETS = "bpoc.device_sim_v1_assets";
    static final String OWNERS = "bpoc.device_sim_v1_scope_events";
    static final String REGISTRY = "bpoc.device_sim_v1_scope_registry";
    static final String ASSET_OWNERS = "bpoc.device_sim_v1_scope_assets";
    static final int EVENT_LIMIT = 256 * 1024, ASSET_LIMIT = 1024 * 1024;
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100)
                    .maxStringLength(EVENT_LIMIT).maxNumberLength(1000).build()).build())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    final Object dbLock = new Object(); // Every connection is opened only under this lock: <= 1 active.
    final String dbUrl = required("DEVICE_DB_URL"), dbUser = required("DEVICE_DB_USER"), dbPassword = required("DEVICE_DB_PASSWORD");
    final String topic = env("DEVICE_MQ_TOPIC", "b-device-sim-v1");
    final IngressTiming timing;
    final JdbcSession database;
    DeviceIngress() { this(IngressTiming.fromEnvironment(), JdbcSession.configuredReuse()); }
    DeviceIngress(IngressTiming timing, boolean reuse) {
        this.timing = timing;
        database = new JdbcSession(dbLock, this::physicalConnect, reuse, timing);
    }
    byte[] token;
    AccessPolicy policy; // null means explicitly legacy/shared and NOT isolated.
    boolean scopedAssets; // Explicit opt-in; legacy routes and default scoped denial remain unchanged.
    RejectionAudit audit;
    HttpServer server;
    DefaultMQProducer producer;
    DefaultMQPushConsumer consumer;
    final ThreadPoolExecutor http = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    final ScheduledExecutorService outbox = Executors.newSingleThreadScheduledExecutor();
    volatile boolean running;
    final boolean publishingPaused = "true".equals(env("DEVICE_PAUSE_PUBLISHING", "false"));

    static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("CONFIG_MISSING");
        return value;
    }
    static final class Problem extends RuntimeException {
        final int status;
        Problem(int status, String code) { super(code); this.status = status; }
    }
    static void require(boolean value, String code) { if (!value) throw new Problem(400, code); }
    static long now() { return System.currentTimeMillis(); }
    Connection connect() throws SQLException { return database.borrow(); }
    Connection physicalConnect() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", dbUser); props.setProperty("password", dbPassword);
        props.setProperty("connectTimeout", "5"); props.setProperty("socketTimeout", "10");
        return DriverManager.getConnection(dbUrl, props);
    }
    static PreparedStatement sql(Connection c, String text, Object... args) throws SQLException {
        PreparedStatement s = c.prepareStatement(text);
        s.setQueryTimeout(5);
        for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
        return s;
    }
    static void execute(Connection c, String text, Object... args) throws SQLException {
        try (PreparedStatement s = sql(c, text, args)) { s.executeUpdate(); }
        catch (SQLException e) {
            System.err.println("DB_OPERATION_FAILED operation=" + text.split(" ", 2)[0] + " sqlstate=" + e.getSQLState());
            throw e;
        }
    }
    void checkDb() throws Exception {
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect(); PreparedStatement s = sql(c,
                    "SELECT version(),current_user,current_database(),has_database_privilege(current_user,current_database(),'CREATE'),has_schema_privilege(current_user,'bpoc','CREATE')"); ResultSet r = s.executeQuery()) {
                r.next();
                System.out.println(JSON.writeValueAsString(Map.of("version", r.getString(1), "db_user", r.getString(2),
                        "database", r.getString(3), "database_create", r.getBoolean(4), "schema_create", r.getBoolean(5))));
            }
        }
    }
    void initSchema() throws SQLException {
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    execute(c, "CREATE TABLE IF NOT EXISTS " + INBOX + " (event_id VARCHAR(212) PRIMARY KEY,device_id VARCHAR(63) NOT NULL,seq BIGINT NOT NULL,wire_sha256 CHAR(64) NOT NULL,body TEXT NOT NULL,state VARCHAR(16) NOT NULL,accepted_ms BIGINT NOT NULL,broker_message_id VARCHAR(256),last_error VARCHAR(64),committed_ms BIGINT,next_attempt_ms BIGINT NOT NULL DEFAULT 0)");
                    execute(c, "CREATE TABLE IF NOT EXISTS " + POINTS + " (event_id VARCHAR(212) NOT NULL,device_id VARCHAR(63) NOT NULL,point_code VARCHAR(128) NOT NULL,observed_ms BIGINT NOT NULL,quality_code VARCHAR(16) NOT NULL,body TEXT NOT NULL,PRIMARY KEY(event_id,point_code))");
                    execute(c, "CREATE TABLE IF NOT EXISTS " + ASSETS + " (sha256 CHAR(64) PRIMARY KEY,content BYTEA NOT NULL,created_ms BIGINT NOT NULL)");
                    execute(c, "CREATE INDEX IF NOT EXISTS device_sim_v1_points_latest ON " + POINTS + " (device_id,point_code,observed_ms,event_id)");
                    execute(c, "CREATE INDEX IF NOT EXISTS device_sim_v1_events_latest ON " + POINTS + " (device_id,observed_ms,event_id)");
                    execute(c, "CREATE INDEX IF NOT EXISTS device_sim_v1_outbox_pending ON " + INBOX + " (state,next_attempt_ms,accepted_ms)");
                    c.commit();
                } catch (SQLException e) { c.rollback(); throw e; }
            }
        }
        System.out.println("SCHEMA_INITIALIZED");
    }
    void migrateHistory() throws SQLException {
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                execute(c, "CREATE INDEX IF NOT EXISTS device_sim_v1_history ON " + INBOX + " (committed_ms,event_id) WHERE state='COMMITTED'");
            }
        }
        System.out.println("HISTORY_INDEX_READY");
    }

    // One ingress process owns commits; dbLock covers reading this watermark and
    // committing the transaction. Reading persisted max also survives clock rollback/restart.
    long nextCommittedMs(Connection c) throws SQLException {
        try (PreparedStatement s = sql(c, "SELECT committed_ms FROM " + INBOX + " WHERE state='COMMITTED' ORDER BY committed_ms DESC,event_id DESC LIMIT 1"); ResultSet r = s.executeQuery()) {
            return r.next() ? Math.max(now(), Math.addExact(r.getLong(1), 1)) : now();
        }
    }
    static String sha(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
    static String utf8(byte[] body) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
    }
    static JsonNode parse(byte[] body) {
        try {
            JsonNode n = JSON.readTree(utf8(body));
            require(n != null, "INVALID_JSON");
            checkTree(n);
            return n;
        } catch (Problem e) { throw e; }
        catch (Exception e) { throw new Problem(400, "INVALID_JSON"); }
    }
    static void checkTree(JsonNode n) {
        if (n.isTextual()) require(n.textValue().indexOf('\0') < 0, "NUL_NOT_SUPPORTED");
        if (n.isDouble() || n.isFloat()) require(Double.isFinite(n.doubleValue()), "NONFINITE_NUMBER");
        if (n.isObject()) n.fieldNames().forEachRemaining(k -> require(k.indexOf('\0') < 0, "NUL_NOT_SUPPORTED"));
        if (n.isContainerNode()) n.forEach(DeviceIngress::checkTree);
    }
    static String text(JsonNode n, String key) {
        require(n.has(key) && n.get(key).isTextual(), "INVALID_" + key.toUpperCase(Locale.ROOT));
        return n.get(key).textValue();
    }
    static long positive(JsonNode n, String key) {
        JsonNode value = n.get(key);
        require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0,
                "INVALID_" + key.toUpperCase(Locale.ROOT));
        return value.longValue();
    }
    static void validate(JsonNode n) {
        require(n.isObject(), "INVALID_EVENT");
        require("b.device-event.v1".equals(text(n, "schema_version")), "INVALID_SCHEMA_VERSION");
        require(n.path("simulated").isBoolean() && n.path("simulated").booleanValue(), "SIMULATED_REQUIRED");
        for (String key : List.of("run_id", "boot_id", "device_id"))
            require(text(n, key).matches("[A-Za-z0-9_.-]{1,63}"), "INVALID_IDENTITY");
        require(text(n, "device_id").startsWith("SIM-"), "SIM_DEVICE_REQUIRED");
        long seq = positive(n, "seq");
        String id = text(n, "run_id") + ":" + text(n, "boot_id") + ":" + text(n, "device_id") + ":" + seq;
        require(id.equals(text(n, "event_id")), "EVENT_ID_MISMATCH");
        positive(n, "observed_ts_ms");
        require(n.has("source_ts_ms"), "SOURCE_TIMESTAMP_REQUIRED");
        if (n.get("source_ts_ms").isNull()) require("gateway_observation".equals(text(n, "timestamp_origin")), "INVALID_TIMESTAMP_ORIGIN");
        else { positive(n, "source_ts_ms"); require(!text(n, "timestamp_origin").isBlank(), "INVALID_TIMESTAMP_ORIGIN"); }
        JsonNode points = n.path("payload").path("points");
        require(points.isArray() && points.size() >= 1 && points.size() <= 256, "INVALID_POINTS");
        Set<String> codes = new HashSet<>();
        for (JsonNode p : points) {
            require(p.isObject(), "INVALID_POINT");
            String code = text(p, "point_code");
            require(code.matches("[A-Za-z0-9_.:-]{1,128}") && codes.add(code), "INVALID_OR_DUPLICATE_POINT_CODE");
            require(Set.of("GOOD", "UNCERTAIN", "BAD", "STALE").contains(text(p, "quality_code")), "INVALID_QUALITY");
            require(p.path("provenance").isObject() && !p.path("provenance").isEmpty(), "INVALID_PROVENANCE");
            require(p.has("value"), "VALUE_REQUIRED");
            require(p.has("unit") && (p.get("unit").isNull() || p.get("unit").isTextual()), "INVALID_UNIT");
        }
    }
    static boolean same(JsonNode a, JsonNode b) {
        return a.equals((x, y) -> x.isNumber() && y.isNumber() ? x.decimalValue().compareTo(y.decimalValue()) : (x.equals(y) ? 0 : 1), b);
    }
    static ObjectNode receipt(ResultSet r, boolean duplicate, boolean includeEvent) throws Exception {
        ObjectNode result = JSON.createObjectNode();
        result.put("event_id", r.getString("event_id")); result.put("state", r.getString("state"));
        result.put("wire_sha256", r.getString("wire_sha256")); result.put("duplicate", duplicate);
        result.put("accepted_ms", r.getLong("accepted_ms"));
        result.put("broker_message_id", r.getString("broker_message_id"));
        long committed = r.getLong("committed_ms");
        if (r.wasNull()) result.putNull("committed_ms"); else result.put("committed_ms", committed);
        result.put("last_error", r.getString("last_error"));
        if (includeEvent) result.set("event", parse(r.getString("body").getBytes(StandardCharsets.UTF_8)));
        return result;
    }
    void checkAssets(Connection c, JsonNode event) throws SQLException {
        for (JsonNode point : event.path("payload").path("points")) {
            JsonNode value = point.path("value");
            String path = value.path("path").asText("");
            if (policy != null && scopedAssets && value.has("sha256"))
                require(path.startsWith(BASE + "/assets"), "INVALID_ASSET_REFERENCE");
            if (!path.startsWith(BASE + "/assets")) continue;
            String digest = value.path("sha256").asText("");
            require(digest.matches("[a-f0-9]{64}") && path.equals(BASE + "/assets?sha256=" + digest), "INVALID_ASSET_REFERENCE");
            // Unbound references must not reveal whether another device or legacy owns these bytes.
            if (policy != null) requireAssetOwner(c, digest, text(event, "device_id"));
            try (PreparedStatement s = sql(c, "SELECT 1 FROM " + ASSETS + " WHERE sha256=?", digest); ResultSet r = s.executeQuery()) {
                require(r.next(), "ASSET_NOT_FOUND");
            }
        }
    }
    ObjectNode accept(byte[] body) throws Exception { return accept(body, null); }
    ObjectNode accept(byte[] body, AccessPolicy.Principal principal) throws Exception {
        JsonNode n = parse(body); validate(n);
        if (policy != null) policy.checkEvent(principal, n, scopedAssets);
        String id = text(n, "event_id");
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement s = sql(c, "SELECT * FROM " + INBOX + " WHERE event_id=? FOR UPDATE", id); ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            if (policy != null) requireOwned(c, id, text(n, "device_id"));
                            if (!same(parse(r.getString("body").getBytes(StandardCharsets.UTF_8)), n)) throw new Problem(409, "EVENT_ID_CONFLICT");
                            ObjectNode reply = receipt(r, true, false); c.commit(); return reply;
                        }
                    }
                    checkAssets(c, n);
                    execute(c, "INSERT INTO " + INBOX + " (event_id,device_id,seq,wire_sha256,body,state,accepted_ms) VALUES(?,?,?,?,?,'PENDING',?)",
                            id, text(n, "device_id"), positive(n, "seq"), sha(body), utf8(body), now());
                    if (policy != null) {
                        AccessPolicy.Binding binding = policy.devices.get(text(n, "device_id"));
                        execute(c, "INSERT INTO " + OWNERS + " (event_id,device_id,project_id,area_id,principal_id) VALUES(?,?,?,?,?)",
                                id, text(n, "device_id"), binding.project(), binding.area(), principal.id());
                    }
                    ObjectNode reply;
                    try (PreparedStatement s = sql(c, "SELECT * FROM " + INBOX + " WHERE event_id=?", id); ResultSet r = s.executeQuery()) {
                        r.next(); reply = receipt(r, false, false);
                    }
                    c.commit(); return reply;
                } catch (Exception e) { c.rollback(); throw e; }
            }
        }
    }
    record Pending(String id, byte[] body) {}
    List<Pending> pending() throws Exception {
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    List<Pending> result = new ArrayList<>();
                    try (PreparedStatement s = sql(c, "SELECT event_id,body FROM " + INBOX + " WHERE state<>'COMMITTED' AND next_attempt_ms<=? ORDER BY accepted_ms,event_id LIMIT 10 FOR UPDATE", now()); ResultSet r = s.executeQuery()) {
                        while (r.next()) result.add(new Pending(r.getString(1), r.getString(2).getBytes(StandardCharsets.UTF_8)));
                    }
                    for (Pending p : result) execute(c, "UPDATE " + INBOX + " SET next_attempt_ms=? WHERE event_id=? AND state<>'COMMITTED'", now() + 10000, p.id());
                    c.commit(); return result;
                } catch (Exception e) { c.rollback(); throw e; }
            }
        }
    }
    void recordSend(String id, String brokerId, String error) throws SQLException {
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                // Consumer may already have committed while producer.send was returning.
                if (brokerId != null) execute(c, "UPDATE " + INBOX + " SET broker_message_id=COALESCE(broker_message_id,?),state=CASE WHEN state='COMMITTED' THEN state ELSE 'SENT' END,last_error=CASE WHEN state='COMMITTED' THEN last_error ELSE NULL END,next_attempt_ms=? WHERE event_id=?", brokerId, now() + 10000, id);
                else execute(c, "UPDATE " + INBOX + " SET last_error=?,next_attempt_ms=? WHERE event_id=? AND state<>'COMMITTED'", error, now() + 10000, id);
            }
        }
    }
    void publishPending() {
        if (!running || publishingPaused) return;
        try {
            for (Pending p : pending()) {
                if (!running) break;
                try {
                    Message m = new Message(topic, p.body()); m.setKeys(p.id());
                    var sent = producer.send(m, 3000);
                    if (sent.getSendStatus() == SendStatus.SEND_OK) recordSend(p.id(), sent.getMsgId(), null);
                    else recordSend(p.id(), null, "BROKER_NOT_SEND_OK");
                } catch (Exception e) { try { recordSend(p.id(), null, "BROKER_SEND_FAILED"); } catch (SQLException ignored) { System.err.println("OUTBOX_STATUS_FAILED"); } }
            }
        } catch (Exception e) { System.err.println("OUTBOX_RETRY"); }
    }
    boolean consume(byte[] body, String brokerId) {
        String id = null;
        try {
            require(body.length <= EVENT_LIMIT, "EVENT_TOO_LARGE");
            JsonNode n = parse(body); validate(n); id = text(n, "event_id");
            long lockStarted = timing.begin();
            synchronized (dbLock) {
                timing.end("db.lock_wait", lockStarted);
                try (Connection c = connect()) {
                    c.setAutoCommit(false);
                    try {
                        boolean committed;
                        try (PreparedStatement s = sql(c, "SELECT body,state,wire_sha256 FROM " + INBOX + " WHERE event_id=? FOR UPDATE", id); ResultSet r = s.executeQuery()) {
                            if (!r.next()) throw new Problem(400, "INBOX_NOT_FOUND");
                            require(same(parse(r.getString(1).getBytes(StandardCharsets.UTF_8)), n) && sha(body).equals(r.getString(3)), "BROKER_BODY_MISMATCH");
                            committed = "COMMITTED".equals(r.getString(2));
                        }
                        if (!committed) {
                            for (JsonNode p : n.path("payload").path("points")) execute(c,
                                    "INSERT INTO " + POINTS + " (event_id,device_id,point_code,observed_ms,quality_code,body) VALUES(?,?,?,?,?,?)",
                                    id, text(n, "device_id"), text(p, "point_code"), positive(n, "observed_ts_ms"), text(p, "quality_code"), JSON.writeValueAsString(p));
                            execute(c, "UPDATE " + INBOX + " SET state='COMMITTED',committed_ms=?,broker_message_id=COALESCE(broker_message_id,?),last_error=NULL WHERE event_id=?", nextCommittedMs(c), brokerId, id);
                        }
                        c.commit(); return true;
                    } catch (Exception e) { c.rollback(); throw e; }
                }
            }
        } catch (Exception e) {
            if (id != null) try { recordSend(id, null, e instanceof Problem ? "CONSUMER_VALIDATION_FAILED" : "CONSUMER_DB_FAILED"); } catch (SQLException ignored) { }
            System.err.println("CONSUMER_RETRY"); return false;
        }
    }
    ObjectNode queryEvent(String id, String device, String point) throws Exception { return queryEvent(id, device, point, null); }
    ObjectNode queryEvent(String id, String device, String point, AccessPolicy.Principal principal) throws Exception {
        if (policy != null && id == null) policy.allow(principal, device, false);
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                if (policy != null && id != null) {
                    try (PreparedStatement scope = sql(c, "SELECT device_id FROM " + OWNERS + " WHERE event_id=?", id); ResultSet row = scope.executeQuery()) {
                        if (!row.next()) throw new Problem(404, "NOT_FOUND");
                        policy.allow(principal, row.getString(1), false);
                    }
                }
                if (point != null) {
                    try (PreparedStatement s = sql(c, "SELECT p.body AS point_body,i.* FROM " + POINTS + " p JOIN " + INBOX + " i ON i.event_id=p.event_id WHERE i.state='COMMITTED'" + scopedFilter("i") + " AND p.device_id=? AND p.point_code=? ORDER BY p.observed_ms DESC,i.seq DESC,i.event_id DESC LIMIT 1", device, point); ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new Problem(404, "NOT_FOUND");
                        ObjectNode result = receipt(r, false, false); result.set("point", parse(r.getString("point_body").getBytes(StandardCharsets.UTF_8))); return result;
                    }
                }
                String q = id != null ? "SELECT * FROM " + INBOX + " WHERE event_id=?" : "SELECT i.* FROM " + INBOX + " i JOIN " + POINTS + " p ON p.event_id=i.event_id WHERE i.state='COMMITTED'" + scopedFilter("i") + " AND i.device_id=? ORDER BY p.observed_ms DESC,i.seq DESC,i.event_id DESC LIMIT 1";
                try (PreparedStatement s = sql(c, q, id != null ? id : device); ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new Problem(404, "NOT_FOUND"); return receipt(r, false, true);
                }
            }
        }
    }
    ObjectNode upload(byte[] body) throws Exception {
        return upload(body, null, null);
    }
    ObjectNode upload(byte[] body, String device, AccessPolicy.Principal principal) throws Exception {
        if (policy != null) {
            if (!scopedAssets) throw new Problem(403, "ASSETS_DISABLED_IN_SCOPED_MODE");
            policy.allow(principal, device, true);
        }
        String digest = sha(body);
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    boolean exists;
                    try (PreparedStatement s = sql(c, "SELECT 1 FROM " + ASSETS + " WHERE sha256=?", digest); ResultSet r = s.executeQuery()) { exists = r.next(); }
                    if (!exists) execute(c, "INSERT INTO " + ASSETS + " (sha256,content,created_ms) VALUES(?,?,?)", digest, body, now());
                    if (policy != null) {
                        boolean owned;
                        try (PreparedStatement s = sql(c, "SELECT 1 FROM " + ASSET_OWNERS + " WHERE sha256=? AND device_id=?", digest, device); ResultSet r = s.executeQuery()) { owned = r.next(); }
                        if (!owned) {
                            AccessPolicy.Binding binding = policy.devices.get(device);
                            execute(c, "INSERT INTO " + ASSET_OWNERS + " (sha256,device_id,project_id,area_id,principal_id,created_ms) VALUES(?,?,?,?,?,?)",
                                    digest, device, binding.project(), binding.area(), principal.id(), now());
                        }
                    }
                    c.commit();
                } catch (Exception e) { c.rollback(); throw e; }
            }
        }
        ObjectNode result = JSON.createObjectNode(); result.put("sha256", digest); result.put("bytes", body.length); result.put("path", BASE + "/assets?sha256=" + digest); return result;
    }
    static String historyCursor(String device, long ms, String id) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(List.of(1, device, ms, id)));
    }
    ObjectNode history(Map<String, String> q) throws Exception { return history(q, null); }
    ObjectNode history(Map<String, String> q, AccessPolicy.Principal principal) throws Exception {
        require(Set.of("cursor", "limit", "device_id").containsAll(q.keySet()), "INVALID_HISTORY_PARAMETER");
        String device = q.getOrDefault("device_id", "*");
        require(device.equals("*") || device.matches("SIM-[A-Za-z0-9_.-]{1,59}"), "INVALID_DEVICE_ID");
        if (policy != null) {
            if (device.equals("*")) throw new Problem(400, "SCOPED_DEVICE_REQUIRED");
            policy.allow(principal, device, false);
        }
        String cursorScope = policy == null ? device : policy.cursorScope(principal, device);
        int limit;
        try { limit = Integer.parseInt(q.getOrDefault("limit", "100")); }
        catch (NumberFormatException e) { throw new Problem(400, "INVALID_LIMIT"); }
        require(limit >= 1 && limit <= 100, "INVALID_LIMIT");
        long after = 0; String afterId = "";
        if (q.containsKey("cursor")) {
            try {
                String encoded = q.get("cursor");
                require(encoded.length() <= 1024 && encoded.matches("[A-Za-z0-9_-]+"), "INVALID_CURSOR");
                JsonNode value = parse(Base64.getUrlDecoder().decode(encoded));
                require(value.isArray() && value.size() == 4 && value.get(0).asInt() == 1
                        && value.get(1).isTextual() && value.get(1).textValue().equals(cursorScope)
                        && value.get(2).isIntegralNumber() && value.get(2).canConvertToLong()
                        && value.get(2).longValue() >= 0 && value.get(3).isTextual()
                        && value.get(3).textValue().length() <= 212, "INVALID_CURSOR");
                after = value.get(2).longValue(); afterId = value.get(3).textValue();
            } catch (IllegalArgumentException e) { throw new Problem(400, "INVALID_CURSOR"); }
        }
        ObjectNode result = JSON.createObjectNode();
        var items = result.putArray("items");
        String cursor = historyCursor(cursorScope, after, afterId);
        boolean more = false;
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                String columns = "event_id,state,wire_sha256,accepted_ms,broker_message_id,committed_ms,last_error";
                String filter = device.equals("*") ? "" : " AND device_id=?";
                List<Object> args = new ArrayList<>(List.of(after, after, afterId));
                if (!device.equals("*")) args.add(device);
                args.add(limit + 1);
                try (PreparedStatement s = sql(c, "SELECT " + columns + " FROM " + INBOX
                        + " WHERE state='COMMITTED'" + scopedFilter(INBOX) + " AND (committed_ms>? OR (committed_ms=? AND event_id>?))"
                        + filter + " ORDER BY committed_ms,event_id LIMIT ?", args.toArray()); ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        if (items.size() == limit) { more = true; break; }
                        ObjectNode item = receipt(r, false, false);
                        cursor = historyCursor(cursorScope, r.getLong("committed_ms"), r.getString("event_id"));
                        item.put("cursor", cursor); items.add(item);
                    }
                }
            }
        }
        result.put("next_cursor", cursor); result.put("has_more", more);
        result.put("order", "committed_ms,event_id"); result.put("retention", "no_automatic_deletion");
        return result;
    }
    byte[] download(String digest) throws Exception {
        return download(digest, null, null);
    }
    void requireAssetOwner(Connection c, String digest, String device) throws SQLException {
        try (PreparedStatement s = sql(c, "SELECT 1 FROM " + ASSET_OWNERS + " WHERE sha256=? AND device_id=?", digest, device); ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new Problem(404, "NOT_FOUND");
        }
    }
    byte[] download(String digest, String device, AccessPolicy.Principal principal) throws Exception {
        require(digest.matches("[a-f0-9]{64}"), "INVALID_SHA256");
        if (policy != null) {
            if (!scopedAssets) throw new Problem(403, "ASSETS_DISABLED_IN_SCOPED_MODE");
            policy.allow(principal, device, false);
        }
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                if (policy != null) requireAssetOwner(c, digest, device);
                try (PreparedStatement s = sql(c, "SELECT content FROM " + ASSETS + " WHERE sha256=?", digest); ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new Problem(404, "NOT_FOUND"); return r.getBytes(1);
                }
            }
        }
    }
    static Map<String, String> query(HttpExchange e) {
        String raw = e.getRequestURI().getRawQuery();
        Map<String, String> result = new HashMap<>();
        if (raw == null) return result;
        require(raw.length() <= 2048, "QUERY_TOO_LONG");
        try {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2); require(kv.length == 2, "INVALID_QUERY");
                String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8), v = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                require(!v.isEmpty() && result.putIfAbsent(k, v) == null, "INVALID_QUERY");
            }
        } catch (IllegalArgumentException ex) { throw new Problem(400, "INVALID_QUERY"); }
        return result;
    }
    static String parameter(Map<String, String> q, String name) { String v = q.get(name); require(v != null && !v.isBlank(), "MISSING_PARAMETER"); return v; }
    static byte[] body(HttpExchange e, int limit, String type) throws IOException {
        String ct = e.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.split(";", 2)[0].trim().equalsIgnoreCase(type)) throw new Problem(415, "UNSUPPORTED_CONTENT_TYPE");
        String encoding = e.getRequestHeaders().getFirst("Content-Encoding");
        require(encoding == null || encoding.equalsIgnoreCase("identity"), "UNSUPPORTED_CONTENT_ENCODING");
        String length = e.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try { if (Long.parseLong(length) > limit) throw new Problem(413, "BODY_TOO_LARGE"); }
            catch (NumberFormatException ex) { throw new Problem(400, "INVALID_LENGTH"); }
        }
        byte[] result = e.getRequestBody().readNBytes(limit + 1);
        if (result.length > limit) throw new Problem(413, "BODY_TOO_LARGE");
        require(result.length > 0, "EMPTY_BODY"); return result;
    }
    static void reply(HttpExchange e, int code, Object value) throws IOException {
        bytes(e, code, JSON.writeValueAsBytes(value), "application/json; charset=utf-8");
    }
    static void bytes(HttpExchange e, int code, byte[] value, String type) throws IOException {
        e.getResponseHeaders().set("Content-Type", type); e.getResponseHeaders().set("Cache-Control", "no-store");
        e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        e.sendResponseHeaders(code, value.length); e.getResponseBody().write(value);
    }
    String bearer(HttpExchange e) {
        List<String> values = e.getRequestHeaders().get("Authorization");
        if (values == null || values.size() != 1) return null;
        String value = values.get(0);
        return value.startsWith("Bearer ") ? value.substring(7) : null;
    }
    boolean authorized(HttpExchange e) {
        String candidate = bearer(e);
        return token != null && token.length > 0 && candidate != null && !candidate.isEmpty()
                && MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.UTF_8));
    }
    static String route(String method, String path) {
        if (!Set.of("GET", "POST").contains(method)) return "other";
        for (String suffix : List.of("events", "history", "latest", "points/latest", "assets", "health"))
            if (path.equals(BASE + "/" + suffix)) return method.toLowerCase(Locale.ROOT) + ":" + suffix;
        return "other";
    }
    void rejected(HttpExchange e, AccessPolicy.Principal principal, String route, byte[] body, int status, String reason) throws IOException {
        String who = principal != null ? principal.id() : (policy == null && authorized(e) ? "legacy-shared" : "anonymous");
        if (audit != null && !audit.append(who, status, reason, route, body)) {
            e.getResponseHeaders().set("X-Audit-Status", "unavailable");
            System.err.println("REJECTION_AUDIT_UNAVAILABLE");
        }
        reply(e, status, Map.of("error", reason));
    }
    void handle(HttpExchange e) throws IOException {
        long requestStarted = timing.begin();
        AccessPolicy.Principal principal = null; byte[] requestBody = null;
        String requestRoute = route(e.getRequestMethod(), e.getRequestURI().getPath());
        try {
            String path = e.getRequestURI().getPath(), method = e.getRequestMethod();
            if (path.equals(BASE + "/health") && method.equals("GET")) {
                reply(e, running ? 200 : 503, Map.of("ok", running, "publisher_paused", publishingPaused,
                        "health_kind", "liveness", "access_mode", policy == null ? "legacy_shared_unisolated" : "scoped_v1")); return;
            }
            if (policy != null) principal = policy.authenticate(bearer(e));
            if (policy != null ? principal == null : !authorized(e)) {
                e.getResponseHeaders().set("WWW-Authenticate", "Bearer"); throw new Problem(401, "UNAUTHORIZED");
            }
            if (policy != null && !scopedAssets && path.equals(BASE + "/assets")) throw new Problem(403, "ASSETS_DISABLED_IN_SCOPED_MODE");
            Map<String, String> q = query(e);
            if (method.equals("POST") && path.equals(BASE + "/events")) {
                requestBody = body(e, EVENT_LIMIT, "application/json");
                JsonNode n = parse(requestBody); validate(n);
                if (policy != null) policy.checkEvent(principal, n, scopedAssets);
                if (audit != null) {
                    synchronized (audit) {
                        audit.ensureWritable();
                        if (!audit.append(principal == null ? "legacy-shared" : principal.id(), 0, "WRITE_ADMISSION_CHECK", requestRoute, null))
                            throw new Problem(503, "AUDIT_UNAVAILABLE");
                        reply(e, 202, accept(requestBody, principal));
                    }
                } else reply(e, 202, accept(requestBody, principal));
            }
            else if (method.equals("POST") && path.equals(BASE + "/assets")) {
                String device = policy == null ? null : parameter(q, "device_id");
                if (policy != null) policy.allow(principal, device, true);
                requestBody = body(e, ASSET_LIMIT, "application/octet-stream");
                if (audit != null) {
                    synchronized (audit) {
                        audit.ensureWritable();
                        if (!audit.append(principal == null ? "legacy-shared" : principal.id(), 0, "WRITE_ADMISSION_CHECK", requestRoute, null))
                            throw new Problem(503, "AUDIT_UNAVAILABLE");
                        reply(e, 200, upload(requestBody, device, principal));
                    }
                } else reply(e, 200, upload(requestBody, device, principal));
            }
            else if (method.equals("GET") && path.equals(BASE + "/events")) reply(e, 200, queryEvent(parameter(q, "event_id"), null, null, principal));
            else if (method.equals("GET") && path.equals(BASE + "/history")) reply(e, 200, history(q, principal));
            else if (method.equals("GET") && path.equals(BASE + "/latest")) reply(e, 200, queryEvent(null, parameter(q, "device_id"), null, principal));
            else if (method.equals("GET") && path.equals(BASE + "/points/latest")) reply(e, 200, queryEvent(null, parameter(q, "device_id"), parameter(q, "point_code"), principal));
            else if (method.equals("GET") && path.equals(BASE + "/assets")) bytes(e, 200, download(parameter(q, "sha256"), policy == null ? null : parameter(q, "device_id"), principal), "application/octet-stream");
            else throw new Problem(404, "NOT_FOUND");
        } catch (Problem ex) { rejected(e, principal, requestRoute, requestBody, ex.status, ex.getMessage()); }
        catch (SQLException ex) { rejected(e, principal, requestRoute, requestBody, 503, "DATABASE_UNAVAILABLE"); }
        catch (Exception ex) { rejected(e, principal, requestRoute, requestBody, 500, "INTERNAL_ERROR"); }
        finally { e.close(); timing.end("request." + requestRoute, requestStarted); }
    }
    String scopedFilter(String alias) {
        return policy == null ? "" : " AND EXISTS (SELECT 1 FROM " + OWNERS + " scope_event WHERE scope_event.event_id=" + alias + ".event_id AND scope_event.device_id=" + alias + ".device_id)";
    }
    void requireOwned(Connection c, String id, String device) throws SQLException {
        try (PreparedStatement s = sql(c, "SELECT device_id FROM " + OWNERS + " WHERE event_id=?", id); ResultSet r = s.executeQuery()) {
            if (!r.next() || !device.equals(r.getString(1))) throw new Problem(403, "LEGACY_EVENT_NOT_SCOPED");
        }
    }
    // Called only for explicitly scoped startup. It never relabels legacy inbox records.
    void initScopeSchema() throws Exception {
        long lockStarted = timing.begin();
        synchronized (dbLock) {
            timing.end("db.lock_wait", lockStarted);
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    execute(c, "CREATE TABLE IF NOT EXISTS " + REGISTRY + " (id INTEGER PRIMARY KEY,registry_sha256 CHAR(64) NOT NULL)");
                    execute(c, "CREATE TABLE IF NOT EXISTS " + OWNERS + " (event_id VARCHAR(212) PRIMARY KEY,device_id VARCHAR(63) NOT NULL,project_id VARCHAR(63) NOT NULL,area_id VARCHAR(63) NOT NULL,principal_id VARCHAR(63) NOT NULL)");
                    if (scopedAssets) execute(c, "CREATE TABLE IF NOT EXISTS " + ASSET_OWNERS + " (sha256 CHAR(64) NOT NULL,device_id VARCHAR(63) NOT NULL,project_id VARCHAR(63) NOT NULL,area_id VARCHAR(63) NOT NULL,principal_id VARCHAR(63) NOT NULL,created_ms BIGINT NOT NULL,PRIMARY KEY(sha256,device_id))");
                    try (PreparedStatement s = sql(c, "SELECT registry_sha256 FROM " + REGISTRY + " WHERE id=1 FOR UPDATE"); ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            if (!policy.registryHash.equals(r.getString(1))) throw new IllegalStateException("SCOPE_REGISTRY_IMMUTABLE");
                        } else execute(c, "INSERT INTO " + REGISTRY + " (id,registry_sha256) VALUES(1,?)", policy.registryHash);
                    }
                    c.commit();
                } catch (Exception ex) { c.rollback(); throw ex; }
            }
        }
    }
    void configureSecurity() throws Exception {
        String mode = env("DEVICE_ACCESS_MODE", "legacy");
        if (!Set.of("legacy", "scoped").contains(mode)) throw new IllegalArgumentException("INVALID_ACCESS_MODE");
        String assetsMode = env("DEVICE_SCOPED_ASSETS", "disabled");
        if (!Set.of("disabled", "enabled").contains(assetsMode)) throw new IllegalArgumentException("INVALID_SCOPED_ASSETS_MODE");
        scopedAssets = assetsMode.equals("enabled");
        if (scopedAssets && !mode.equals("scoped")) throw new IllegalArgumentException("SCOPED_MODE_REQUIRED");
        if (mode.equals("scoped")) {
            policy = AccessPolicy.load(Path.of(required("DEVICE_ACCESS_POLICY_FILE")));
            audit = new RejectionAudit(Path.of(required("DEVICE_REJECTION_AUDIT_FILE")), Long.parseLong(required("DEVICE_REJECTION_AUDIT_MAX_BYTES")));
        } else {
            if (System.getenv("DEVICE_ACCESS_POLICY_FILE") != null) throw new IllegalArgumentException("SCOPED_MODE_REQUIRED");
            byte[] secret = Files.readAllBytes(Path.of(required("DEVICE_TOKEN_FILE")));
            require(secret.length <= 4096, "INVALID_TOKEN_FILE");
            String tokenText = utf8(secret).strip();
            require(tokenText.matches("[A-Za-z0-9._~-]{32,256}"), "INVALID_TOKEN_FILE"); token = tokenText.getBytes(StandardCharsets.UTF_8);
            if (System.getenv("DEVICE_REJECTION_AUDIT_FILE") != null)
                audit = new RejectionAudit(Path.of(required("DEVICE_REJECTION_AUDIT_FILE")), Long.parseLong(required("DEVICE_REJECTION_AUDIT_MAX_BYTES")));
        }
    }
    void start() throws Exception {
        require(topic.equals("b-device-sim-v1"), "ISOLATED_TOPIC_REQUIRED");
        configureSecurity();
        System.setProperty("sun.net.httpserver.maxReqTime", "10");
        System.setProperty("sun.net.httpserver.maxRspTime", "10");
        System.setProperty("sun.net.httpserver.maxConnections", "64");
        System.setProperty("sun.net.httpserver.maxReqHeaders", "32");
        server = HttpServer.create(new InetSocketAddress(env("DEVICE_BIND", "127.0.0.1"), Integer.parseInt(env("DEVICE_PORT", "28110"))), 32);
        server.setExecutor(http); server.createContext(BASE, this::handle);
        if (policy != null) initScopeSchema();
        String endpoint = required("DEVICE_MQ_ENDPOINT");
        producer = new DefaultMQProducer("b-device-sim-v1-producer");
        producer.setNamesrvAddr(endpoint); producer.setVipChannelEnabled(false); producer.setRetryTimesWhenSendFailed(0); producer.setMaxMessageSize(EVENT_LIMIT + 4096); producer.start();
        consumer = new DefaultMQPushConsumer("b-device-sim-v1-consumer");
        consumer.setNamesrvAddr(endpoint); consumer.setVipChannelEnabled(false);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.setConsumeThreadMin(2); consumer.setConsumeThreadMax(2); consumer.setConsumeMessageBatchMaxSize(1);
        consumer.setPullBatchSize(1); consumer.setPullThresholdForQueue(16); consumer.setPullThresholdForTopic(64);
        consumer.setPullThresholdSizeForQueue(4); consumer.setPullThresholdSizeForTopic(16);
        consumer.subscribe(topic, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (var m : messages) if (!consume(m.getBody(), m.getMsgId())) return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start(); running = true; server.start();
        outbox.scheduleWithFixedDelay(this::publishPending, 0, 1, TimeUnit.SECONDS);
        System.out.println("DEVICE_INGRESS_STARTED");
    }
    void close() {
        running = false;
        if (server != null) server.stop(1);
        outbox.shutdownNow(); http.shutdownNow();
        if (consumer != null) consumer.shutdown();
        if (producer != null) producer.shutdown();
        database.close(); timing.close();
        if (audit != null) try { audit.close(); } catch (IOException ex) { System.err.println("AUDIT_CLOSE_FAILED"); }
    }
    public static void main(String[] args) {
        DeviceIngress app = null;
        try {
            DriverManager.setLoginTimeout(5);
            app = new DeviceIngress(); final DeviceIngress active = app;
            Runtime.getRuntime().addShutdownHook(new Thread(active::close, "device-ingress-shutdown"));
            if (args.length == 1 && args[0].equals("--check-db")) { app.checkDb(); app.close(); }
            else if (args.length == 1 && args[0].equals("--init-schema")) { app.initSchema(); app.close(); }
            else if (args.length == 1 && args[0].equals("--migrate-history")) { app.migrateHistory(); app.close(); }
            else if (args.length == 0) app.start();
            else throw new IllegalArgumentException("INVALID_ARGUMENTS");
        } catch (Exception e) {
            System.err.println("DEVICE_INGRESS_START_FAILED"); if (app != null) app.close(); System.exit(1);
        }
    }
}
