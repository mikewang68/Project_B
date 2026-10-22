package com.bproject.trust.operations;

import com.bproject.trust.ports.EvidenceStorage;
import com.bproject.trust.ports.LedgerGateway;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationsService {
  private final JdbcTemplate db;
  private final EvidenceStorage ipfs;
  private final LedgerGateway fabric;

  public OperationsService(JdbcTemplate db, EvidenceStorage ipfs, LedgerGateway fabric) {
    this.db = db;
    this.ipfs = ipfs;
    this.fabric = fabric;
  }

  public Map<String, Object> status(String org) {
    var result = new LinkedHashMap<String, Object>();
    result.put("database", "UP");
    try {
      var stat = ipfs.usedBytes();
      result.put("ipfs", Map.of("state", "UP", "repoBytes", stat, "node", "IPFS 独立节点"));
    } catch (Exception e) {
      result.put("ipfs", Map.of("state", "DOWN", "node", "IPFS 独立节点"));
    }
    result.put("fabric", fabric.healthy() ? "UP" : "DOWN");
    result.put("checkedAt", Instant.now());
    result.put("workerFailures", com.bproject.trust.shared.WorkerSignals.counts());
    result.put(
        "oldestPendingSeconds",
        db.queryForObject(
            "SELECT CAST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-MIN(e.received_at))) AS BIGINT) FROM"
                + " tasks_read t JOIN events e ON e.id=t.event_id WHERE e.org_id=? AND"
                + " t.state<>'DONE'",
            Long.class,
            org));
    result.put(
        "tasks",
        db.queryForList(
            "SELECT t.state,COUNT(*) AS count FROM tasks_read t JOIN events_read e ON"
                + " e.id=t.event_id WHERE e.org_id=? GROUP BY t.state",
            org));
    result.put(
        "events",
        db.queryForObject("SELECT COUNT(*) FROM events_read WHERE org_id=?", Long.class, org));
    return result;
  }
}
