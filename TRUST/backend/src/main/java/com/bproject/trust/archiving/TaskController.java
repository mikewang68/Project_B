package com.bproject.trust.archiving;

import com.bproject.trust.identity.CurrentIdentity;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class TaskController {
  private final CurrentIdentity identity;
  private final TaskService tasks;

  public TaskController(CurrentIdentity identity, TaskService tasks) {
    this.identity = identity;
    this.tasks = tasks;
  }

  @GetMapping("/tasks")
  public List<Map<String, Object>> tasks(Authentication a) {
    return tasks.list(identity.org(a));
  }

  @PostMapping("/events/{id}/retry")
  @PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
  public Map<String, Object> retry(Authentication a, @PathVariable String id) {
    return tasks.retry(id, identity.org(a), a.getName());
  }
}
