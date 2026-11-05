package com.bdemo.iam.scope;

import com.bproject.iam.context.UserContext;

/** IAM is a management service audience, independent of the manager's business tuple. */
public final class ScopeManagementAccess {
  public static final String MENU = "IAM:scope-management";
  public static final String ROUTE = "/system/scope-management";
  private ScopeManagementAccess() {}
  public static boolean allowed(UserContext context, String audience) {
    return context != null && context.mayManage() && "IAM".equalsIgnoreCase(audience);
  }
  public static boolean menu(UserContext context, String app, String key, String route) {
    return allowed(context, app) && MENU.equals(key) && ROUTE.equals(route);
  }
}
