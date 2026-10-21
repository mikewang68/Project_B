package com.bdemo.iam.navigation.model;

public class NavigationMenuPermEntity {
  private String mappingId;
  private String stableMenuKey;
  private String navigationAppCode;
  private String permissionScope;
  private String ruleOperator;
  private String sourcePermissionRaw;
  private String candidatePermissionCode;
  private String existingIamPermissionId;
  private String existingIamSystemCode;
  private String mappingStatus;
  private String approvalStatus;
  private int valid;

  public NavigationMenuPermEntity() {}

  public String getMappingId() { return mappingId; }
  public void setMappingId(String mappingId) { this.mappingId = mappingId; }

  public String getStableMenuKey() { return stableMenuKey; }
  public void setStableMenuKey(String stableMenuKey) { this.stableMenuKey = stableMenuKey; }

  public String getNavigationAppCode() { return navigationAppCode; }
  public void setNavigationAppCode(String navigationAppCode) { this.navigationAppCode = navigationAppCode; }

  public String getPermissionScope() { return permissionScope; }
  public void setPermissionScope(String permissionScope) { this.permissionScope = permissionScope; }

  public String getRuleOperator() { return ruleOperator; }
  public void setRuleOperator(String ruleOperator) { this.ruleOperator = ruleOperator; }

  public String getSourcePermissionRaw() { return sourcePermissionRaw; }
  public void setSourcePermissionRaw(String sourcePermissionRaw) { this.sourcePermissionRaw = sourcePermissionRaw; }

  public String getCandidatePermissionCode() { return candidatePermissionCode; }
  public void setCandidatePermissionCode(String candidatePermissionCode) { this.candidatePermissionCode = candidatePermissionCode; }

  public String getExistingIamPermissionId() { return existingIamPermissionId; }
  public void setExistingIamPermissionId(String existingIamPermissionId) { this.existingIamPermissionId = existingIamPermissionId; }

  public String getExistingIamSystemCode() { return existingIamSystemCode; }
  public void setExistingIamSystemCode(String existingIamSystemCode) { this.existingIamSystemCode = existingIamSystemCode; }

  public String getMappingStatus() { return mappingStatus; }
  public void setMappingStatus(String mappingStatus) { this.mappingStatus = mappingStatus; }

  public String getApprovalStatus() { return approvalStatus; }
  public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

  public int getValid() { return valid; }
  public void setValid(int valid) { this.valid = valid; }
}
