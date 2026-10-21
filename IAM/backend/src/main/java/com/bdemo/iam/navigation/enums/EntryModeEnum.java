package com.bdemo.iam.navigation.enums;

/**
 * 导航入口跳转模式枚举：严格限于 SPA_ROUTE, PAGE_KEY, TAB_TARGET。
 */
public enum EntryModeEnum {
  SPA_ROUTE,
  PAGE_KEY,
  TAB_TARGET;

  public static EntryModeEnum fromDatabase(String raw) {
    if (raw == null) return SPA_ROUTE;
    String s = raw.trim().toUpperCase();
    if ("TAB".equals(s) || "TAB_TARGET".equals(s)) {
      return TAB_TARGET;
    }
    if ("PAGE_KEY".equals(s)) {
      return PAGE_KEY;
    }
    return SPA_ROUTE;
  }
}
