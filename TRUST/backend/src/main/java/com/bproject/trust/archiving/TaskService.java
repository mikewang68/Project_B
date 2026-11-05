package com.bproject.trust.archiving;

import com.bproject.trust.audit.AuditService;
import com.bproject.trust.events.EventService;
import com.bproject.trust.shared.web.ApiError;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
  private final JdbcTemplate db;
  private final EventService events;
  private final AuditService audit;

  public TaskService(JdbcTemplate db, EventService events, AuditService audit) {
    this.db = db;
    this.events = events;
    this.audit = audit;
  }

  public List<Map<String, Object>> list(String org) {
    return db.queryForList(
        "SELECT t.*,e.source_event_id,e.batch_id,e.file_state,e.chain_state FROM tasks_read t JOIN"
            + " events_read e ON e.id=t.event_id WHERE e.record_kind='LEGACY_V1' AND e.org_id=?"
            + com.bproject.trust.identity.TrustCentralScope.predicate(com.bproject.trust.identity.TrustCentralScope.Root.EVENT,"e")+" ORDER BY t.updated_at DESC LIMIT 200",
        org);
  }

  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> retry(String id, String org, String actor) {
    events.legacyGet(id, org);
    int n =
        db.update(
            "UPDATE tasks_read SET"
                + " state='READY',attempts=0,next_at=CURRENT_TIMESTAMP,last_error=NULL,updated_at=CURRENT_TIMESTAMP,change_actor=?,change_operation='RETRY',change_reason='Manual"
                + " retry' WHERE event_id=? AND state IN ('READY','FAILED')",
            actor,
            id);
    if (n == 0) throw new ApiError(409, "仅待处理或失败任务可重试；处理中或已完成任务不会重复执行");
    audit.record(org, actor, "RETRY", id, null);
    return Map.of("queued", true);
  }
}
