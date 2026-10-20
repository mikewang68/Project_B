package com.bdemo.iam.navigation.model;

public class NavigationAppEntity {
  private String appCode;
  private String iamSystemCode;
  private String portalModuleKey;
  private String displayName;
  private int sortOrder;
  private String entryBasePath;
  private int inNavScope;
  private int enabled;
  private int valid;
  private String mappingStatus;
  private String approvalStatus;

  public NavigationAppEntity() {}

  public String getAppCode() { return appCode; }
  public void setAppCode(String appCode) { this.appCode = appCode; }

  public String getIamSystemCode() { return iamSystemCode; }
  public void setIamSystemCode(String iamSystemCode) { this.iamSystemCode = iamSystemCode; }

  public String getPortalModuleKey() { return portalModuleKey; }
  public void setPortalModuleKey(String portalModuleKey) { this.portalModuleKey = portalModuleKey; }

  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }

  public int getSortOrder() { return sortOrder; }
  public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

  public String getEntryBasePath() { return entryBasePath; }
  public void setEntryBasePath(String entryBasePath) { this.entryBasePath = entryBasePath; }

  public int getInNavScope() { return inNavScope; }
  public void setInNavScope(int inNavScope) { this.inNavScope = inNavScope; }

  public int getEnabled() { return enabled; }
  public void setEnabled(int enabled) { this.enabled = enabled; }

  public int getValid() { return valid; }
  public void setValid(int valid) { this.valid = valid; }

  public String getMappingStatus() { return mappingStatus; }
  public void setMappingStatus(String mappingStatus) { this.mappingStatus = mappingStatus; }

  public String getApprovalStatus() { return approvalStatus; }
  public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
}
