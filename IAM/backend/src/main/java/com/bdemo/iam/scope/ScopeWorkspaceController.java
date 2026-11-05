package com.bdemo.iam.scope;

import com.bdemo.iam.common.R;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/workspace")
@ConditionalOnProperty(name="iam.scope-v2.enabled", havingValue="true")
public class ScopeWorkspaceController {
  private final ScopeWorkspaceService workspaces;
  public ScopeWorkspaceController(ScopeWorkspaceService workspaces) { this.workspaces = workspaces; }
  @GetMapping public R<ScopeWorkspaceService.Workspace> current() { return R.ok(workspaces.current()); }
}
