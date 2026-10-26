package com.bdemo.iam.navigation.model;

public class NavigationAppEntity {
  private boolean ssoEnabled;
  public boolean isSsoEnabled() { return ssoEnabled; }
  public void setSsoEnabled(boolean value) { ssoEnabled = value; }
  private String appCode;
  private String iamSystemCode;
  private String portalModuleKey;
  private String displayName;
  private int sortOrder;
  private String entryBasePath;
  private String domainName;
  private String publicScheme;
  private Integer publicPort;
  private String originScheme;
  private String originIp;
  private Integer originPort;
  private String modulePath;
  private long addressVersion = 1;
  public long getAddressVersion() { return addressVersion; }
  public void setAddressVersion(long value) { addressVersion = value; }
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

  public String getDomainName() { return domainName; }
  public void setDomainName(String domainName) { this.domainName = domainName; }
  public String getPublicScheme() { return publicScheme; }
  public void setPublicScheme(String publicScheme) { this.publicScheme = publicScheme; }
  public Integer getPublicPort() { return publicPort; }
  public void setPublicPort(Integer publicPort) { this.publicPort = publicPort; }
  public String getOriginScheme() { return originScheme; }
  public void setOriginScheme(String originScheme) { this.originScheme = originScheme; }
  public String getOriginIp() { return originIp; }
  public void setOriginIp(String originIp) { this.originIp = originIp; }
  public Integer getOriginPort() { return originPort; }
  public void setOriginPort(Integer originPort) { this.originPort = originPort; }
  public String getModulePath() { return modulePath; }
  public void setModulePath(String modulePath) { this.modulePath = modulePath; }

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
