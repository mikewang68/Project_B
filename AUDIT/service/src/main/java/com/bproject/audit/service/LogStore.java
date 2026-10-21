package com.bproject.audit.service;

import com.bproject.audit.client.CanonicalJson;
import com.bproject.audit.client.LogEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class LogStore {
    final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final boolean autoDdl, chainEnabled;
    public LogStore(JdbcTemplate db, ObjectMapper mapper, TransactionTemplate tx,
                    @Value("${audit.auto-ddl:false}") boolean autoDdl, @Value("${audit.chain.enabled:false}") boolean chainEnabled) {
        this.db = db; this.mapper = mapper; this.tx = tx; this.autoDdl = autoDdl; this.chainEnabled = chainEnabled;
    }
    @PostConstruct void initialize() throws Exception {
        if (autoDdl) {
            String sql = new ClassPathResource("schema.sql").getContentAsString(StandardCharsets.UTF_8);
            for (String statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
        }
        // Fail startup if the service upgrade omitted its required migration.
        db.queryForObject("SELECT COUNT(*) FROM log_trust_delivery WHERE 1=0",Long.class);
        db.queryForObject("SELECT COUNT(*) FROM log_trust_receipt WHERE 1=0",Long.class);
    }
    public Map<String,Object> append(LogEvent event, ServiceIdentity client) {
        if (!client.can("WRITE") || !client.appCode().equals(event.appCode()) || !client.domainId().equals(event.domainId()) || !client.zoneId().equals(event.zoneId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SCOPE_DENIED");
        return appendInternal(event);
    }
    Map<String,Object> appendInternal(LogEvent event) {
        String payload = CanonicalJson.encode(mapper, event), hash = CanonicalJson.hash(payload);
        try {
            tx.executeWithoutResult(status -> {
                var existing = db.queryForList("SELECT payload_hash FROM log_event WHERE id=?", event.storageKey());
                if (!existing.isEmpty()) { checkHash(existing.get(0), hash); return; }
                db.update("INSERT INTO log_event(id,event_id,app_code,domain_id,zone_id,user_id,menu_id,action,outcome,occurred_at,received_at,payload,payload_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        event.storageKey(), event.eventId(), event.appCode(), event.domainId(), event.zoneId(), event.userId(), event.menuId(), event.action(), event.outcome(), Timestamp.from(event.occurredAt()), Timestamp.from(Instant.now()), payload, hash);
                db.update("INSERT INTO log_chain(id,state,next_attempt) VALUES(?,?,?)", event.storageKey(), chainEnabled ? "PENDING" : "NOT_CONFIGURED", Timestamp.from(Instant.now()));
            });
        } catch (DuplicateKeyException race) {
            var existing = db.queryForList("SELECT payload_hash FROM log_event WHERE id=?", event.storageKey());
            if (existing.isEmpty()) throw race;
            checkHash(existing.get(0), hash);
        }
        return receiptByKey(event.storageKey());
    }
    private void checkHash(Map<String,Object> old, String hash) {
        if (!hash.equals(old.get("payload_hash"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "EVENT_ID_CONFLICT");
    }
    private String app(ServiceIdentity client, String requested) {
        if (!client.appCode().equals("*")) {
            if (requested != null && !requested.equals(client.appCode())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "APP_DENIED");
            return client.appCode();
        }
        if (requested == null || requested.isBlank()) return null;
        return requested;
    }
    Map<String,Object> receiptByKey(String id) {
        var rows = db.queryForList("SELECT e.event_id,e.app_code,e.payload_hash,c.state,c.attempts,c.transaction_id,c.last_error FROM log_event e JOIN log_chain c ON c.id=e.id WHERE e.id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
        var row = rows.get(0);
        var result = new java.util.LinkedHashMap<String,Object>();
        result.put("eventId", row.get("event_id")); result.put("appCode", row.get("app_code")); result.put("stored", true);
        result.put("payloadHash", row.get("payload_hash")); result.put("chainState", row.get("state")); result.put("attempts", row.get("attempts"));
        result.put("transactionId", row.get("transaction_id")); result.put("lastError", row.get("last_error"));
        var deliveries=db.queryForList("SELECT remote_id,remote_status,outcome_unknown,original_sha256,remote_event_sha256,receipt_raw FROM log_trust_delivery WHERE id=?",id);
        if(!deliveries.isEmpty()){
            var delivery=new java.util.LinkedHashMap<>(deliveries.get(0));
            Object raw=delivery.remove("receipt_raw");
            try{delivery.put("receipt",raw==null?null:mapper.readTree((String)raw));}catch(Exception invalid){throw new IllegalStateException("Invalid stored TRUST receipt",invalid);}
            result.put("trust",delivery);
        }
        return result;
    }
    String key(ServiceIdentity client, String eventId, String requestedApp) {
        String selected = app(client, requestedApp);
        if (selected == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "APP_REQUIRED");
        return CanonicalJson.hash(client.domainId() + "\n" + client.zoneId() + "\n" + selected + "\n" + eventId);
    }
    public Map<String,Object> receipt(ServiceIdentity client, String eventId, String selectedApp) { return receiptByKey(key(client, eventId, selectedApp)); }
    public Map<String,Object> query(ServiceIdentity client, String selectedApp, String action, String userId, String from, String to, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        var args = new ArrayList<Object>();
        args.add(client.domainId()); args.add(client.zoneId());
        String where = " WHERE domain_id=? AND zone_id=?";
        String selected = app(client, selectedApp);
        if (selected != null) { where += " AND app_code=?"; args.add(selected); }
        if (action != null && !action.isBlank()) { where += " AND action=?"; args.add(action); }
        if (userId != null && !userId.isBlank()) { where += " AND user_id=?"; args.add(userId); }
        if (from != null && !from.isBlank()) { where += " AND occurred_at>=?"; args.add(Timestamp.from(Instant.parse(from))); }
        if (to != null && !to.isBlank()) { where += " AND occurred_at<=?"; args.add(Timestamp.from(Instant.parse(to))); }
        Long total = db.queryForObject("SELECT COUNT(*) FROM log_event" + where, Long.class, args.toArray());
        var pageArgs = new ArrayList<>(args); pageArgs.add(size); pageArgs.add(page * size);
        List<Map<String,Object>> items = db.query("SELECT id,payload,received_at FROM log_event" + where + " ORDER BY occurred_at DESC,id LIMIT ? OFFSET ?", (rs, row) -> {
            try {
                Map<String,Object> item = mapper.readValue(rs.getString("payload"), Map.class);
                item.put("receivedAt", rs.getTimestamp("received_at").toInstant()); item.put("receipt", receiptByKey(rs.getString("id")));
                return item;
            } catch (Exception e) { throw new IllegalStateException("Invalid persisted audit event", e); }
        }, pageArgs.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }
    public void retry(ServiceIdentity client, String eventId, String selectedApp) {
        String id = key(client, eventId, selectedApp); receiptByKey(id);
        if (!chainEnabled) throw new ResponseStatusException(HttpStatus.CONFLICT, "CHAIN_NOT_CONFIGURED");
        if (db.update("UPDATE log_chain SET state='PENDING',attempts=0,next_attempt=?,last_error=NULL WHERE id=? AND state IN ('FAILED','NOT_CONFIGURED')", Timestamp.from(Instant.now()), id) == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EVENT_NOT_RETRYABLE");
    }
    public void access(ServiceIdentity client, String action, String outcome, String path) {
        appendInternal(new LogEvent(1, "AUDIT-" + java.util.UUID.randomUUID(), Instant.now(), "audit", client.domainId(), client.zoneId(), client.id(), "SYSTEM", "audit-logs", action, "", outcome, "", path, "", 0, "REQUEST", "日志服务访问，使用已认证服务身份"));
    }
}
