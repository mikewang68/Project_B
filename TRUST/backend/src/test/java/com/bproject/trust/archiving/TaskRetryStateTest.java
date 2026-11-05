package com.bproject.trust.archiving;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.trust.events.EventService;
import com.bproject.trust.audit.AuditService;
import com.bproject.trust.shared.web.ApiError;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
class TaskRetryStateTest {
  @Test void retryIsAtomicAndCannotResetDoneOrRunning(){
    var db=mock(JdbcTemplate.class);var events=mock(EventService.class);var audit=mock(AuditService.class);var service=new TaskService(db,events,audit);
    assertThrows(ApiError.class,()->service.retry("event","org","actor"));
    var sql=ArgumentCaptor.forClass(String.class);verify(db).update(sql.capture(),eq("actor"),eq("event"));
    assertTrue(sql.getValue().contains("state IN ('READY','FAILED')"));verify(events).legacyGet("event","org");verifyNoInteractions(audit);
  }
  @Test void allowedRetryRecordsAuditOnlyAfterOneScopedTaskWasUpdated(){
    var db=mock(JdbcTemplate.class);var events=mock(EventService.class);var audit=mock(AuditService.class);var service=new TaskService(db,events,audit);
    when(db.update(anyString(),eq("actor"),eq("event"))).thenReturn(1);
    assertEquals(true,service.retry("event","org","actor").get("queued"));verify(events).legacyGet("event","org");verify(audit).record("org","actor","RETRY","event",null);
  }
}
