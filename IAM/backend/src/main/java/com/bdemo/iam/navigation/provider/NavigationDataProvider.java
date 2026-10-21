package com.bdemo.iam.navigation.provider;

import com.bdemo.iam.navigation.model.NavigationAppEntity;
import com.bdemo.iam.navigation.model.NavigationMenuEntity;
import com.bdemo.iam.navigation.model.NavigationMenuPermEntity;
import java.util.List;

/**
 * 阶段 2-B.2 导航数据提供层接口。
 * 严格分离物理数据库提供者 (DatabaseNavigationProvider) 与离线受控提供者 (FixtureNavigationProvider)。
 */
public interface NavigationDataProvider {

  /**
   * 获取所有活跃子系统元数据。
   */
  List<NavigationAppEntity> getActiveApps();

  /**
   * 获取所有符合导航候选门槛的菜单项。
   */
  List<NavigationMenuEntity> getNavEligibleMenus();

  /**
   * 获取所有页面视图权限映射规则。
   */
  List<NavigationMenuPermEntity> getViewPermissionRules();

  /**
   * 获取所有操作权限映射规则。
   */
  List<NavigationMenuPermEntity> getOperationPermissionRules();

  /**
   * 当前提供者名称（如 "DATABASE" 或 "FIXTURE"）。
   */
  String getProviderName();
}
