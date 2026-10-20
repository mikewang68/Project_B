package com.bdemo.iam.navigation.dto;

/**
 * 诊断信息 DTO：仅在特批管理员请求且 includeDiagnostics=true 时返回。
 * 对普通主体严格隐蔽，不泄露未授权系统与页面的任何元数据。
 */
public class DiagnosticsInfo {

  private String evaluationMode;
  private String cacheStatus;
  private int filteredOutCount;

  public DiagnosticsInfo() {}

  public DiagnosticsInfo(String evaluationMode, String cacheStatus, int filteredOutCount) {
    this.evaluationMode = evaluationMode;
    this.cacheStatus = cacheStatus;
    this.filteredOutCount = filteredOutCount;
  }

  public String getEvaluationMode() {
    return evaluationMode;
  }

  public void setEvaluationMode(String evaluationMode) {
    this.evaluationMode = evaluationMode;
  }

  public String getCacheStatus() {
    return cacheStatus;
  }

  public void setCacheStatus(String cacheStatus) {
    this.cacheStatus = cacheStatus;
  }

  public int getFilteredOutCount() {
    return filteredOutCount;
  }

  public void setFilteredOutCount(int filteredOutCount) {
    this.filteredOutCount = filteredOutCount;
  }
}
