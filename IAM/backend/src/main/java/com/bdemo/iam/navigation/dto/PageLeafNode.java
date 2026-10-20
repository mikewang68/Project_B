package com.bdemo.iam.navigation.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * E21 三级导航树节点：APP -> DIRECTORY -> PAGE。
 *
 * 兼容说明：
 * - 保留原有字段（stableMenuKey / menuName / entryMode / entryPath / routePath / pageKey / tabKey），
 *   既有 Portal 与测试消费者不被打断；
 * - 新增 nodeType、children，使目录可携带子页；
 * - PAGE 节点 children 始终为空数组，DIRECTORY 节点 entryPath 始终为空串（目录不可跳转）。
 */
public class PageLeafNode {

  private String stableMenuKey;
  private String hierarchyCode;
  private int level = 2;
  private String menuName;
  private String nodeType = "PAGE";
  private String entryMode;
  private String entryPath;
  private String routePath = "";
  private String pageKey = "";
  private String tabKey = "";
  private List<PageLeafNode> children = new ArrayList<>();

  public PageLeafNode() {}

  public String getStableMenuKey() {
    return stableMenuKey;
  }

  public void setStableMenuKey(String stableMenuKey) {
    this.stableMenuKey = stableMenuKey;
  }

  public String getHierarchyCode() {
    return hierarchyCode;
  }

  public void setHierarchyCode(String hierarchyCode) {
    this.hierarchyCode = hierarchyCode;
  }

  public int getLevel() {
    return level;
  }

  public void setLevel(int level) {
    this.level = level;
  }

  public String getMenuName() {
    return menuName;
  }

  public void setMenuName(String menuName) {
    this.menuName = menuName;
  }

  /** PAGE 或 DIRECTORY。目录不可点击跳转。 */
  public String getNodeType() {
    return nodeType;
  }

  public void setNodeType(String nodeType) {
    this.nodeType = nodeType;
  }

  public boolean isDirectory() {
    return "DIRECTORY".equalsIgnoreCase(nodeType);
  }

  public String getEntryMode() {
    return entryMode;
  }

  public void setEntryMode(String entryMode) {
    this.entryMode = entryMode;
  }

  public String getEntryPath() {
    return entryPath;
  }

  public void setEntryPath(String entryPath) {
    this.entryPath = entryPath;
  }

  public String getRoutePath() {
    return routePath;
  }

  public void setRoutePath(String routePath) {
    this.routePath = routePath == null ? "" : routePath;
  }

  public String getPageKey() {
    return pageKey;
  }

  public void setPageKey(String pageKey) {
    this.pageKey = pageKey == null ? "" : pageKey;
  }

  public String getTabKey() {
    return tabKey;
  }

  public void setTabKey(String tabKey) {
    this.tabKey = tabKey == null ? "" : tabKey;
  }

  public List<PageLeafNode> getChildren() {
    return children;
  }

  public void setChildren(List<PageLeafNode> children) {
    this.children = children == null ? new ArrayList<>() : children;
  }
}
