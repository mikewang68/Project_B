package com.bdemo.iam.navigation.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 严格两级架构中的一级已授权系统节点 DTO (Level 1 System Node)。
 * children 必须至少包含 1 个已授权二级页面；无授权功能页时该一级节点在投影中自动裁剪。
 */
public class SystemNode {
  private boolean ssoEnabled;
  public boolean isSsoEnabled() { return ssoEnabled; }
  public void setSsoEnabled(boolean value) { ssoEnabled = value; }

  private String appCode;
  private String displayName;
  private int sortOrder;
  private String entryBasePath;
  private String entryBaseUrl;
  private long addressVersion = 1;
  public long getAddressVersion() { return addressVersion; }
  public void setAddressVersion(long value) { addressVersion = value; }
  private List<PageLeafNode> children = new ArrayList<>();

  public SystemNode() {}

  public SystemNode(String appCode, String displayName, int sortOrder, String entryBasePath, List<PageLeafNode> children) {
    this.appCode = appCode;
    this.displayName = displayName;
    this.sortOrder = sortOrder;
    this.entryBasePath = entryBasePath;
    this.children = children == null ? new ArrayList<>() : children;
  }

  public String getAppCode() {
    return appCode;
  }

  public void setAppCode(String appCode) {
    this.appCode = appCode;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public String getEntryBasePath() {
    return entryBasePath;
  }

  public void setEntryBasePath(String entryBasePath) {
    this.entryBasePath = entryBasePath;
  }

  /** Optional configured external entry; authentication remains the target application's responsibility. */
  public String getEntryBaseUrl() { return entryBaseUrl; }
  public void setEntryBaseUrl(String entryBaseUrl) { this.entryBaseUrl = entryBaseUrl; }

  public List<PageLeafNode> getChildren() {
    return children;
  }

  public void setChildren(List<PageLeafNode> children) {
    this.children = children == null ? new ArrayList<>() : children;
  }
}
