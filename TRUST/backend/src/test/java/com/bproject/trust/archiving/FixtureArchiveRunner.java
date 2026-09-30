package com.bproject.trust.archiving;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Executes only the explicitly supplied fixture event, never polls the shared task queue. */
public final class FixtureArchiveRunner {
  public static void run(ArchiveWorker worker, JdbcTemplate db, String id, String org) {
    if (!org.equals(db.queryForObject("SELECT org_id FROM events WHERE id=?", String.class, id)))
      throw new IllegalArgumentException("Fixture organization mismatch");
    String token = UUID.randomUUID().toString();
    if (db.update("UPDATE tasks SET state='RUNNING',lease_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '60 seconds',attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE event_id=? AND ((state='READY' AND next_at<=CURRENT_TIMESTAMP) OR (state='RUNNING' AND lease_until<CURRENT_TIMESTAMP))", token, id) != 1) return;
    try { worker.process(id, token); }
    catch (Exception error) { worker.failed(id, token, error); }
  }
}
