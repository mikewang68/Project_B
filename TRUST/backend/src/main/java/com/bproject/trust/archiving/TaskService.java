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
        "SELECT t.*,e.source_event_id,e.batch_id,e.file_state,e.chain_state FROM tasks t JOIN"
            + " events e ON e.id=t.event_id WHERE e.org_id=? ORDER BY t.updated_at DESC LIMIT 200",
        org);
  }

  public Map<String, Object> retry(String id, String org, String actor) {
    events.get(id, org);
    int n =
        db.update(
            "UPDATE tasks SET"
                + " state='READY',attempts=0,next_at=CURRENT_TIMESTAMP,last_error=NULL,updated_at=CURRENT_TIMESTAMP"
                + " WHERE event_id=? AND state<>'RUNNING'",
            id);
    if (n == 0) throw new ApiError(409, "任务正在处理，请稍后查看");
    audit.record(org, actor, "RETRY", id, null);
    return Map.of("queued", true);
  }
}
