package com.bdemo.iam.navigation.dto;

/**
 * 导航响应中的当前用户上下文 DTO。
 */
public class UserContext {

  private String displayName;
  private String accessMode;
  private boolean readOnly;

  public UserContext() {}

  public UserContext(String displayName, String accessMode, boolean readOnly) {
    this.displayName = displayName;
    this.accessMode = accessMode;
    this.readOnly = readOnly;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getAccessMode() {
    return accessMode;
  }

  public void setAccessMode(String accessMode) {
    this.accessMode = accessMode;
  }

  public boolean isReadOnly() {
    return readOnly;
  }

  public void setReadOnly(boolean readOnly) {
    this.readOnly = readOnly;
  }
}
