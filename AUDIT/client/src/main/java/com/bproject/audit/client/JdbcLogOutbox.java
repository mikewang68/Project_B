package com.bproject.audit.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Call enqueue inside the business transaction using the SAME DataSource/transaction manager. */
public class JdbcLogOutbox {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final String table;
    public JdbcLogOutbox(JdbcTemplate db, ObjectMapper mapper, String table) {
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Unsafe outbox table");
        this.db = db; this.mapper = mapper; this.table = table;
    }
    public void initialize() {
        db.execute("CREATE TABLE IF NOT EXISTS " + table + " (id VARCHAR(64) PRIMARY KEY, event_id VARCHAR(128) NOT NULL, payload TEXT NOT NULL, payload_hash VARCHAR(64) NOT NULL, state VARCHAR(32) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt TIMESTAMP WITH TIME ZONE NOT NULL, lease_id VARCHAR(64), last_error VARCHAR(96), receipt TEXT, created_at TIMESTAMP WITH TIME ZONE NOT NULL)");
    }
    public void enqueue(LogEvent event) {
        String json = CanonicalJson.encode(mapper, event), hash = CanonicalJson.hash(json);
        var old = db.queryForList("SELECT payload_hash FROM " + table + " WHERE id=?", event.storageKey());
        if (!old.isEmpty()) {
            if (!hash.equals(old.get(0).get("payload_hash"))) throw new IllegalStateException("Log event ID conflict");
            return;
        }
        db.update("INSERT INTO " + table + " (id,event_id,payload,payload_hash,state,next_attempt,created_at) VALUES(?,?,?,?,?,?,?)",
                event.storageKey(), event.eventId(), json, hash, "PENDING", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }
    public List<Map<String,Object>> due() {
        return db.queryForList("SELECT id,event_id,payload,payload_hash,attempts FROM " + table + " WHERE state IN ('PENDING','SENDING') AND next_attempt<=? ORDER BY created_at LIMIT 20", Timestamp.from(Instant.now()));
    }
    public boolean claim(String id, String lease) {
        return db.update("UPDATE " + table + " SET state='SENDING',lease_id=?,next_attempt=? WHERE id=? AND state IN ('PENDING','SENDING') AND next_attempt<=?",
                lease, Timestamp.from(Instant.now().plusSeconds(60)), id, Timestamp.from(Instant.now())) == 1;
    }
    public void acknowledge(String id, String lease, String receipt) {
        db.update("UPDATE " + table + " SET state='ACCEPTED',receipt=?,last_error=NULL,lease_id=NULL WHERE id=? AND lease_id=?", receipt, id, lease);
    }
    public void failed(String id, String lease, int attempts, String code, boolean permanent) {
        db.update("UPDATE " + table + " SET state=?,attempts=?,next_attempt=?,last_error=?,lease_id=NULL WHERE id=? AND lease_id=?",
                permanent || attempts >= 30 ? "FAILED" : "PENDING", attempts, Timestamp.from(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(attempts, 8)))), code, id, lease);
    }
    public List<Map<String,Object>> statistics() { return db.queryForList("SELECT state,COUNT(*) AS total FROM " + table + " GROUP BY state"); }
}
