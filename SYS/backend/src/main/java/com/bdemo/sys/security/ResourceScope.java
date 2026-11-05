package com.bdemo.sys.security;

/** Called by MyBatis dynamic SQL so list, count, export and updates share one boundary. */
public final class ResourceScope {
  private ResourceScope() {}
  public static boolean globalRead() {
    LoginUser actor=JwtAuthFilter.currentUser();
    return actor!=null && (actor.isSuperAdmin() || actor.isGlobalRead());
  }
  public static boolean version2(){var user=JwtAuthFilter.currentUser();return user!=null&&user.getUserContext()!=null;}
  /** Native GLOBAL catalogs become visible only after their V2 classification. */
  public static String catalogOwnerCondition() {
    if(globalRead())return "";
    return version2()?" AND (owner_app_code='SYS' OR owner_app_code='GLOBAL') "
        :" AND owner_app_code='SYS' ";
  }
  public static String logCondition() {
    var user=JwtAuthFilter.currentUser();
    if(user==null||user.getUserContext()==null)return "";
    var context=user.getUserContext();
    if(context.global())return " AND scope_kind <> 'LEGACY' ";
    var generated=com.bproject.iam.context.ScopeSql.build(context.scopeMode(),context.activeScope());
    return " AND scope_kind = 'REGION_SYSTEM' "+generated.my_info();
  }
}
