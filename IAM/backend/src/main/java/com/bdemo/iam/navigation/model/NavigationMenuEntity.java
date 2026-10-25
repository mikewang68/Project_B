package com.bdemo.iam.navigation.model;

public class NavigationMenuEntity {
  private String featureStatus = "READY";
  private String unavailableReason = "";
  public String getFeatureStatus() { return featureStatus; }
  public void setFeatureStatus(String value) { featureStatus = value; }
  public String getUnavailableReason() { return unavailableReason; }
  public void setUnavailableReason(String value) { unavailableReason = value; }
  private String stableMenuKey;
  private String navigationAppCode;
  private String sourceAppCode;
  private String sourceMenuId;
  private String sourceParentId;
  private String parentStableMenuKey;
  private String hierarchyCode;
  private int originalLevel;
  private int normalizedLevel;
  private int siblingSortOrder;
  private String menuName;
  private String projectedDisplayTitle;
  private String menuKind;
  private String entryMode;
  private String routePath;
  private String entryPath;
  private String pageKey;
  private String tabKey;
  private String componentPath;
  private String icon;
  private int visible;
  private int enabled;
  private int valid;
  private int navEligible;
  private String sourcePermissionRaw;

  public NavigationMenuEntity() {}

  public String getStableMenuKey() { return stableMenuKey; }
  public void setStableMenuKey(String stableMenuKey) { this.stableMenuKey = stableMenuKey; }

  public String getNavigationAppCode() { return navigationAppCode; }
  public void setNavigationAppCode(String navigationAppCode) { this.navigationAppCode = navigationAppCode; }

  public String getSourceAppCode() { return sourceAppCode; }
  public void setSourceAppCode(String sourceAppCode) { this.sourceAppCode = sourceAppCode; }

  public String getSourceMenuId() { return sourceMenuId; }
  public void setSourceMenuId(String sourceMenuId) { this.sourceMenuId = sourceMenuId; }

  public String getSourceParentId() { return sourceParentId; }
  public void setSourceParentId(String sourceParentId) { this.sourceParentId = sourceParentId; }

  public String getParentStableMenuKey() { return parentStableMenuKey; }
  public void setParentStableMenuKey(String parentStableMenuKey) { this.parentStableMenuKey = parentStableMenuKey; }

  public String getHierarchyCode() { return hierarchyCode; }
  public void setHierarchyCode(String hierarchyCode) { this.hierarchyCode = hierarchyCode; }

  public int getOriginalLevel() { return originalLevel; }
  public void setOriginalLevel(int originalLevel) { this.originalLevel = originalLevel; }

  public int getNormalizedLevel() { return normalizedLevel; }
  public void setNormalizedLevel(int normalizedLevel) { this.normalizedLevel = normalizedLevel; }

  public int getSiblingSortOrder() { return siblingSortOrder; }
  public void setSiblingSortOrder(int siblingSortOrder) { this.siblingSortOrder = siblingSortOrder; }

  public String getMenuName() { return menuName; }
  public void setMenuName(String menuName) { this.menuName = menuName; }

  public String getProjectedDisplayTitle() { return projectedDisplayTitle; }
  public void setProjectedDisplayTitle(String projectedDisplayTitle) { this.projectedDisplayTitle = projectedDisplayTitle; }

  public String getMenuKind() { return menuKind; }
  public void setMenuKind(String menuKind) { this.menuKind = menuKind; }

  public String getEntryMode() { return entryMode; }
  public void setEntryMode(String entryMode) { this.entryMode = entryMode; }

  public String getRoutePath() { return routePath; }
  public void setRoutePath(String routePath) { this.routePath = routePath; }

  public String getEntryPath() { return entryPath; }
  public void setEntryPath(String entryPath) { this.entryPath = entryPath; }

  public String getPageKey() { return pageKey; }
  public void setPageKey(String pageKey) { this.pageKey = pageKey; }

  public String getTabKey() { return tabKey; }
  public void setTabKey(String tabKey) { this.tabKey = tabKey; }

  public String getComponentPath() { return componentPath; }
  public void setComponentPath(String componentPath) { this.componentPath = componentPath; }

  public String getIcon() { return icon; }
  public void setIcon(String icon) { this.icon = icon; }

  public int getVisible() { return visible; }
  public void setVisible(int visible) { this.visible = visible; }

  public int getEnabled() { return enabled; }
  public void setEnabled(int enabled) { this.enabled = enabled; }

  public int getValid() { return valid; }
  public void setValid(int valid) { this.valid = valid; }

  public int getNavEligible() { return navEligible; }
  public void setNavEligible(int navEligible) { this.navEligible = navEligible; }

  public String getSourcePermissionRaw() { return sourcePermissionRaw; }
  public void setSourcePermissionRaw(String sourcePermissionRaw) { this.sourcePermissionRaw = sourcePermissionRaw; }
}
