package com.bdemo.iam.scope;

import com.bproject.iam.context.*;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.JwtAuthFilter;
import jakarta.servlet.http.*;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Scoped administrators cannot fall through to the legacy global-management controllers. */
@Component
public class ScopeRouteInterceptor implements HandlerInterceptor {
  public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
    var user=JwtAuthFilter.currentUser();if(user==null||user.getUserContext()==null)return true;
    var context=user.getUserContext();
    String path=request.getRequestURI().substring(request.getContextPath().length());
    if(Set.of("/auth/me","/auth/logout","/auth/context","/auth/context/selection","/auth/sso/csrf","/health").contains(path))return true;
    if(path.equals("/auth/workspace")&&request.getMethod().equals("GET"))return true;
    if((path.startsWith("/management/")||path.equals("/management"))&&context.mayManage())return true;
    // A newly appointed L2 must reach the bounded management service before
    // any business grant exists. Ticket audience checks still run downstream.
    if(context.mayManage()&&(path.equals("/navigation/tree")&&request.getMethod().equals("GET")
        ||path.equals("/auth/sso/ticket")&&request.getMethod().equals("POST")))return true;
    if(context.scopeMode()==ScopeSql.Mode.DENY)throw BizException.forbidden("当前项目、区域或系统授权已失效");
    if(path.equals("/navigation/tree")&&request.getMethod().equals("GET"))return true;
    if(path.equals("/auth/sso/ticket"))return true;
    if(Set.of("L1_SUPER","L1_READONLY").contains(context.adminLevel()))return true;
    throw BizException.forbidden("请使用当前范围的人员与岗位管理入口");
  }
}
