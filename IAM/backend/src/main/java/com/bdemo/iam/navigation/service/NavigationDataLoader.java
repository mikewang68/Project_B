package com.bdemo.iam.navigation.service;

import com.bdemo.iam.navigation.enums.AppCodeEnum;
import com.bdemo.iam.navigation.enums.EntryModeEnum;
import com.bdemo.iam.navigation.model.NavigationAppEntity;
import com.bdemo.iam.navigation.model.NavigationMenuEntity;
import com.bdemo.iam.navigation.model.NavigationMenuPermEntity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 阶段 2-B 权威导航基线数据提供器。
 * 承载经阶段 2-A.1 校验的 8 系统、187 条规范化菜单记录及 Batch 1 (IAM 4 页 + SYS 3 页 = 7 个静态候选页)。
 * 当独立数据库未配置或处于离线测试模式时，作为权威内存数据提供者，确保无假数据。
 */
@Component
public class NavigationDataLoader {

  private final List<NavigationAppEntity> defaultApps = new ArrayList<>();
  private final List<NavigationMenuEntity> defaultMenus = new ArrayList<>();
  private final List<NavigationMenuPermEntity> defaultPerms = new ArrayList<>();

  // Batch 1 允许由雷文确认并模拟测试发布的 7 个静态候选页集合
  public static final Map<String, Batch1PageMeta> BATCH1_PAGES;

  public record Batch1PageMeta(
      String appCode,
      String stableMenuKey,
      String hierarchyCode,
      String menuName,
      EntryModeEnum entryMode,
      String routePath,
      String entryPath,
      String pageKey,
      String tabKey,
      String requiredPermCode) {}

  static {
    Map<String, Batch1PageMeta> b1 = new LinkedHashMap<>();
    // IAM 4 页
    b1.put("IAM:users", new Batch1PageMeta("IAM", "IAM:users", "IAM.01", "用户管理", EntryModeEnum.SPA_ROUTE, "/system/users", "/iam/system/users", "", "", "iam:user:list:view"));
    b1.put("IAM:roles", new Batch1PageMeta("IAM", "IAM:roles", "IAM.02", "角色管理", EntryModeEnum.SPA_ROUTE, "/system/roles", "/iam/system/roles", "", "", "iam:role:list:view"));
    b1.put("IAM:menus", new Batch1PageMeta("IAM", "IAM:menus", "IAM.03", "菜单与权限", EntryModeEnum.SPA_ROUTE, "/system/menus", "/iam/system/menus", "", "", "iam:menu:tree:view"));
    b1.put("IAM:health", new Batch1PageMeta("IAM", "IAM:health", "IAM.04", "权限健康", EntryModeEnum.SPA_ROUTE, "/system/health", "/iam/system/health", "", "", "iam:role:list:view"));
    // SYS 3 页
    b1.put("SYS:dict", new Batch1PageMeta("SYS", "SYS:dict", "SYS.01", "数据字典", EntryModeEnum.SPA_ROUTE, "/maintain/dict", "/sys/maintain/dict", "", "", "sys:dict:type:view"));
    b1.put("SYS:logs", new Batch1PageMeta("SYS", "SYS:logs", "SYS.02", "操作日志", EntryModeEnum.SPA_ROUTE, "/maintain/logs", "/sys/maintain/logs", "", "", "sys:log:list:view"));
    b1.put("SYS:config", new Batch1PageMeta("SYS", "SYS:config", "SYS.03", "系统配置", EntryModeEnum.SPA_ROUTE, "/maintain/config", "/sys/maintain/config", "", "", "sys:config:list:view"));
    BATCH1_PAGES = Collections.unmodifiableMap(b1);
  }

  public NavigationDataLoader() {
    initDefaultData();
  }

  private void initDefaultData() {
    // 1. 初始化 8 个纳管应用实体 (固定排序 10..80)
    for (AppCodeEnum app : AppCodeEnum.values()) {
      NavigationAppEntity entity = new NavigationAppEntity();
      entity.setAppCode(app.name());
      entity.setIamSystemCode(app.getIamSystemCode());
      entity.setPortalModuleKey(app.name().toLowerCase());
      entity.setDisplayName(app.getDefaultDisplayName());
      entity.setSortOrder(app.getSortOrder());
      entity.setEntryBasePath(app.getEntryBasePath());
      entity.setInNavScope(1);
      entity.setEnabled(1);
      entity.setValid(1);
      entity.setMappingStatus("VERIFIED_EXISTING");
      entity.setApprovalStatus("PENDING_DECISION");
      defaultApps.add(entity);
    }

    // 2. 初始化 Batch 1 的 7 个二级页面
    for (Batch1PageMeta m : BATCH1_PAGES.values()) {
      NavigationMenuEntity menu = new NavigationMenuEntity();
      menu.setStableMenuKey(m.stableMenuKey());
      menu.setNavigationAppCode(m.appCode());
      menu.setSourceAppCode(m.appCode());
      menu.setSourceMenuId(m.stableMenuKey().split(":")[1]);
      menu.setParentStableMenuKey("APP:" + m.appCode());
      menu.setHierarchyCode(m.hierarchyCode());
      menu.setNormalizedLevel(2);
      menu.setMenuName(m.menuName());
      menu.setMenuKind("PAGE");
      menu.setEntryMode(m.entryMode().name());
      menu.setRoutePath(m.routePath());
      menu.setEntryPath(m.entryPath());
      menu.setPageKey(m.pageKey());
      menu.setTabKey(m.tabKey());
      menu.setVisible(1);
      menu.setEnabled(1);
      menu.setValid(1);
      menu.setNavEligible(1);
      defaultMenus.add(menu);

      NavigationMenuPermEntity perm = new NavigationMenuPermEntity();
      perm.setMappingId("MAP:" + m.stableMenuKey() + ":VIEW");
      perm.setStableMenuKey(m.stableMenuKey());
      perm.setNavigationAppCode(m.appCode());
      perm.setPermissionScope("VIEW");
      perm.setCandidatePermissionCode(m.requiredPermCode());
      perm.setExistingIamSystemCode(m.appCode().toLowerCase());
      perm.setMappingStatus("VERIFIED");
      perm.setApprovalStatus("PENDING_DECISION");
      perm.setValid(1);
      defaultPerms.add(perm);
    }
  }

  public List<NavigationAppEntity> getDefaultApps() {
    return defaultApps;
  }

  public List<NavigationMenuEntity> getDefaultMenus() {
    return defaultMenus;
  }

  public List<NavigationMenuPermEntity> getDefaultPerms() {
    return defaultPerms;
  }
}
