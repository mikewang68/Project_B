package com.bdemo.iam.scope;

import com.bdemo.iam.common.*;
import com.bdemo.iam.sso.*;
import com.bproject.iam.context.UserContext;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** Backend service credentials and current central session are both mandatory. */
@RestController
@RequestMapping("/auth/sso")
@ConditionalOnProperty(name="iam.scope-v2.enabled",havingValue="true")
public class ScopeResourceController {
  private final SsoServiceIdentity services;private final SsoAuthorizationService authorization;
  private final ScopeResourceGuard resources;
  public ScopeResourceController(SsoServiceIdentity services,SsoAuthorizationService authorization,ScopeResourceGuard resources) {
    this.services=services;this.authorization=authorization;this.resources=resources;
  }
  public record Request(String subject,String sessionId,String audience,String resourceKey,String menuKey,String action) {}
  @PostMapping("/resource-filter")
  public R<Map<String,Object>> filter(@RequestBody Request request,@RequestHeader(value="X-SSO-Service-Token",required=false)String credential) {
    if(!services.authorize(request.audience(),credential))throw BizException.forbidden("服务身份校验失败");
    final Map<String,Object> current;
    try { current=authorization.current(request.subject(),request.sessionId(),request.audience()); }
    catch(SsoTicketService.SsoTicketException inactive) {
      throw BizException.unauthorized("共享资源会话或授权已失效");
    }
    if(!(current.get("userContext") instanceof UserContext context))throw BizException.forbidden("资源过滤要求新版上下文");
    var filter=resources.authorize(context,request.resourceKey(),request.menuKey(),request.action());
    return R.ok(Map.of("resourceKey",request.resourceKey(),"action",request.action(),"filter",filter,
        "authorizationVersion",context.authorizationVersion(),"issuedAt",java.time.Instant.now().toString()));
  }
}
