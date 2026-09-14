package com.bproject.trust.events;

import com.bproject.trust.audit.AuditService;
import com.bproject.trust.identity.CurrentIdentity;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class EventController {
  private final CurrentIdentity identity;
  private final EventService events;
  private final AuditService audit;

  public EventController(CurrentIdentity identity, EventService events, AuditService audit) {
    this.identity = identity;
    this.events = events;
    this.audit = audit;
  }

  @GetMapping("/events")
  public Map<String, Object> list(
      Authentication a,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "30") int size) {
    var scope = identity.org(a);
    var result = events.list(scope, q, page, size);
    audit.record(
        scope, a.getName(), "EVENT_QUERY", null, q.substring(0, Math.min(200, q.length())));
    return result;
  }

  @PostMapping("/events")
  @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
  public ResponseEntity<?> submit(Authentication a, @Valid @RequestBody EventInput input) {
    return ResponseEntity.accepted().body(events.submit(input, identity.org(a), a.getName(), null));
  }

  @GetMapping("/events/{id}")
  public Map<String, Object> detail(Authentication a, @PathVariable String id) {
    var scope = identity.org(a);
    var result = events.detail(id, scope);
    audit.record(scope, a.getName(), "EVENT_READ", id, null);
    return result;
  }

  @PostMapping("/events/{id}/corrections")
  @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
  public ResponseEntity<?> correct(
      Authentication a, @PathVariable String id, @Valid @RequestBody EventInput input) {
    return ResponseEntity.accepted().body(events.submit(input, identity.org(a), a.getName(), id));
  }
}
