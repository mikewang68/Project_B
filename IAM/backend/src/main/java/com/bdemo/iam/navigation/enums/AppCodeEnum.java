package com.bdemo.iam.navigation.enums;

/**
 * 八系统权威枚举与固定排序定义 (WMS:10 -> SYS:80)。
 * 严格禁止包含 DTS 或历史非导航编码。
 */
public enum AppCodeEnum {
  WMS(10, "/wms/", "wms", "仓储管理系统"),
  PSMS(20, "/psms/", "pps", "生产调度系统"),
  SCS(30, "/scs/", "safety", "安全卡控系统"),
  EHMS(40, "/ehm/", "ehealth", "设备健康管理系统"),
  EMCS(50, "/emcs/", "ems", "能源管理控制系统"),
  TRUST(60, "/trust/", "trust", "可信存证与身份系统"),
  IAM(70, "/iam/", "iam", "统一身份与权限管理系统"),
  SYS(80, "/sys/", "sys", "系统管理系统");

  private final int sortOrder;
  private final String entryBasePath;
  private final String iamSystemCode;
  private final String defaultDisplayName;

  AppCodeEnum(int sortOrder, String entryBasePath, String iamSystemCode, String defaultDisplayName) {
    this.sortOrder = sortOrder;
    this.entryBasePath = entryBasePath;
    this.iamSystemCode = iamSystemCode;
    this.defaultDisplayName = defaultDisplayName;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public String getEntryBasePath() {
    return entryBasePath;
  }

  public String getIamSystemCode() {
    return iamSystemCode;
  }

  public String getDefaultDisplayName() {
    return defaultDisplayName;
  }

  public static AppCodeEnum fromString(String code) {
    if (code == null) return null;
    for (AppCodeEnum e : values()) {
      if (e.name().equalsIgnoreCase(code.trim())) {
        return e;
      }
    }
    return null;
  }
}
