package com.bdemo.sys.security;

import com.bdemo.sys.common.BizException;
import com.bproject.iam.context.ScopeSql;
import jakarta.servlet.http.*;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Version-2 actions are checked separately from legacy permission codes, including export. */
@Component
public class ContextActionInterceptor implements HandlerInterceptor {
  private static final Set<String> SAFE=Set.of("VIEW","DOWNLOAD","EXPORT","AUDIT_VERIFY");
  public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
    var user=JwtAuthFilter.currentUser();if(user==null||user.getUserContext()==null)return true;
    String path=request.getRequestURI().substring(request.getContextPath().length());
    if(Set.of("/auth/me","/auth/sso/csrf","/auth/sso/logout").contains(path))return true;
    var c=user.getUserContext();
    if(c.scopeMode()==ScopeSql.Mode.DENY)throw BizException.forbidden("当前范围已失效");
    String menu=path.equals("/dict")||path.startsWith("/dict/")?"SYS:dict":
        path.equals("/configs")||path.startsWith("/configs/")?"SYS:config":
        path.equals("/logs")||path.startsWith("/logs/")?"SYS:logs":null;
    if(menu==null)throw BizException.forbidden("接口尚未登记新版范围策略");
    String method=request.getMethod();
    String action;
    if(method.equals("GET")||method.equals("HEAD"))action=path.endsWith("/export")?"EXPORT":"VIEW";
    else if(method.equals("DELETE")||path.endsWith("/batch-delete"))action="DELETE";
    else if(method.equals("PUT")||method.equals("PATCH"))action="EDIT";
    else if(method.equals("POST"))action="ADD";
    else throw BizException.forbidden("操作方法未登记");
    if(!c.hasAction(menu,action)||c.readOnly()&&!SAFE.contains(action))throw BizException.forbidden("当前动作未授权");
    if(!menu.equals("SYS:logs")&&!SAFE.contains(action)&&!c.adminLevel().equals("L1_SUPER"))
      throw BizException.forbidden("全局字典和配置仅允许全局管理员维护");
    return true;
  }
}
