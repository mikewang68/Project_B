package com.bdemo.iam.provisioning;

import com.bdemo.iam.common.R;
import com.bdemo.iam.security.RequirePerm;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users/{id}/identity")
public class IdentityController {
  private final IdentityTasks tasks;
  @org.springframework.beans.factory.annotation.Autowired private com.bdemo.iam.security.AppScopePolicy appScopes;

  public IdentityController(IdentityTasks tasks) {
    this.tasks = tasks;
  }

  @GetMapping
  @RequirePerm("iam:user:list:view")
  public R<Map<String, Object>> get(@PathVariable String id) {
    if(!appScopes.visible("USER",id))throw com.bdemo.iam.common.BizException.notFound("用户不存在");
    return R.ok(tasks.status(id));
  }

  @PostMapping("/retry")
  @RequirePerm("iam:identity:retry:execute")
  public R<Map<String, Object>> retry(
      @PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
    appScopes.requireOwned("USER",id);
    return R.ok(tasks.operate(id, "retry", key));
  }

  @PostMapping("/rotate")
  @RequirePerm("iam:identity:rotate:execute")
  public R<Map<String, Object>> rotate(
      @PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
    appScopes.requireOwned("USER",id);
    return R.ok(tasks.operate(id, "rotate", key));
  }

  @PostMapping("/revoke")
  @RequirePerm("iam:identity:revoke:execute")
  public R<Map<String, Object>> revoke(
      @PathVariable String id, @RequestHeader("Idempotency-Key") String key) {
    appScopes.requireOwned("USER",id);
    return R.ok(tasks.operate(id, "revoke", key));
  }
}
