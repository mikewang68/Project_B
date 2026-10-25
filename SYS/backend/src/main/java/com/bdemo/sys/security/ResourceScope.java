package com.bdemo.sys.security;

/** Called by MyBatis dynamic SQL so list, count, export and updates share one boundary. */
public final class ResourceScope {
  private ResourceScope() {}
  public static boolean globalRead() {
    LoginUser actor=JwtAuthFilter.currentUser();
    return actor!=null && (actor.isSuperAdmin() || actor.isGlobalRead());
  }
}
