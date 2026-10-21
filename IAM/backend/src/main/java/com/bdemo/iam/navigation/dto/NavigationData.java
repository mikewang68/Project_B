package com.bdemo.iam.navigation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 2-B 统一导航响应 data 主结构 (NavigationData)。
 * 与 iam_navigation_response_schema_v3.json 严格对齐。
 */
public class NavigationData {

  private String schemaVersion = "3.0";
  private String generatedAt;
  private String activeBatchId;
  private UserContext userContext;
  private List<SystemNode> systems = new ArrayList<>();
  private Map<String, List<String>> operationPermissions = new LinkedHashMap<>();

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private DiagnosticsInfo diagnostics;

  public NavigationData() {}

  public String getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(String schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public String getGeneratedAt() {
    return generatedAt;
  }

  public void setGeneratedAt(String generatedAt) {
    this.generatedAt = generatedAt;
  }

  public String getActiveBatchId() {
    return activeBatchId;
  }

  public void setActiveBatchId(String activeBatchId) {
    this.activeBatchId = activeBatchId;
  }

  public UserContext getUserContext() {
    return userContext;
  }

  public void setUserContext(UserContext userContext) {
    this.userContext = userContext;
  }

  public List<SystemNode> getSystems() {
    return systems;
  }

  public void setSystems(List<SystemNode> systems) {
    this.systems = systems == null ? new ArrayList<>() : systems;
  }

  public Map<String, List<String>> getOperationPermissions() {
    return operationPermissions;
  }

  public void setOperationPermissions(Map<String, List<String>> operationPermissions) {
    this.operationPermissions = operationPermissions == null ? new LinkedHashMap<>() : operationPermissions;
  }

  public DiagnosticsInfo getDiagnostics() {
    return diagnostics;
  }

  public void setDiagnostics(DiagnosticsInfo diagnostics) {
    this.diagnostics = diagnostics;
  }
}
