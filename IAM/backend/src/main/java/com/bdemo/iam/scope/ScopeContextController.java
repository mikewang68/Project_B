package com.bdemo.iam.scope;

import com.bproject.iam.context.*;

import com.bdemo.iam.common.*;
import com.bdemo.iam.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/context")
@ConditionalOnProperty(name="iam.scope-v2.enabled",havingValue="true")
public class ScopeContextController {
  private final ScopeContextService contexts;
  public ScopeContextController(ScopeContextService contexts){this.contexts=contexts;}
  @GetMapping public R<UserContext> context() {
    var user=JwtAuthFilter.currentUser();
    if(user==null||user.getUserContext()==null)throw BizException.forbidden("账号尚未迁移至新版范围");
    return R.ok(user.getUserContext());
  }
  public record Select(List<ScopeTriple> scopes,boolean global) {}
  @PostMapping("/selection") public R<UserContext> select(@RequestBody Select input,HttpServletRequest request) {
    return R.ok(contexts.select(request,input.scopes(),input.global()));
  }
}
