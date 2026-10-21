package com.bdemo.iam.navigation.provider;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.navigation.mapper.NavigationMenuMapper;
import com.bdemo.iam.navigation.model.NavigationAppEntity;
import com.bdemo.iam.navigation.model.NavigationMenuEntity;
import com.bdemo.iam.navigation.model.NavigationMenuPermEntity;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 阶段 2-B.2 数据库物理导航数据提供者。
 * 通过 MyBatis / openGauss 真实查询 iam_navigation_app, iam_navigation_menu, iam_navigation_menu_permission。
 *
 * 强制安全门禁 (Fail-Closed)：
 * 当数据库不可达、连接超时或查询异常时，坚决 Fail-Closed 抛出 50001 异常，严禁静默回退内存伪造数据！
 */
@Component
public class DatabaseNavigationProvider implements NavigationDataProvider {

  private static final Logger log = LoggerFactory.getLogger(DatabaseNavigationProvider.class);

  private final NavigationMenuMapper menuMapper;

  /** 隔离联调实例可加载候选页（默认 false，正式实例不设置该开关）。 */
  @org.springframework.beans.factory.annotation.Value("${iam.navigation.isolated-test-candidates:false}")
  private boolean includeCandidates = false;

  @Autowired
  public DatabaseNavigationProvider(NavigationMenuMapper menuMapper) {
    this.menuMapper = menuMapper;
  }

  @Override
  public List<NavigationAppEntity> getActiveApps() {
    if (menuMapper == null) {
      throw new BizException(500, 50001, "数据库导航服务不可用: NavigationMenuMapper 未就绪");
    }
    try {
      List<NavigationAppEntity> apps = menuMapper.selectActiveApps();
      return apps != null ? apps : Collections.emptyList();
    } catch (Exception e) {
      log.error("Failed to query navigation apps from database: {}", e.getMessage(), e);
      throw new BizException(500, 50001, "数据库导航系统查询异常: " + e.getMessage());
    }
  }

  @Override
  public List<NavigationMenuEntity> getNavEligibleMenus() {
    if (menuMapper == null) {
      throw new BizException(500, 50001, "数据库导航服务不可用: NavigationMenuMapper 未就绪");
    }
    try {
      List<NavigationMenuEntity> menus = menuMapper.selectNavEligibleMenus(includeCandidates);
      return menus != null ? menus : Collections.emptyList();
    } catch (Exception e) {
      log.error("Failed to query navigation menus from database: {}", e.getMessage(), e);
      throw new BizException(500, 50001, "数据库导航菜单查询异常: " + e.getMessage());
    }
  }

  @Override
  public List<NavigationMenuPermEntity> getViewPermissionRules() {
    if (menuMapper == null) {
      throw new BizException(500, 50001, "数据库导航服务不可用: NavigationMenuMapper 未就绪");
    }
    try {
      List<NavigationMenuPermEntity> rules = menuMapper.selectViewPermissionRules();
      return rules != null ? rules : Collections.emptyList();
    } catch (Exception e) {
      log.error("Failed to query view permission rules from database: {}", e.getMessage(), e);
      throw new BizException(500, 50001, "数据库视图权限规则查询异常: " + e.getMessage());
    }
  }

  @Override
  public List<NavigationMenuPermEntity> getOperationPermissionRules() {
    if (menuMapper == null) {
      throw new BizException(500, 50001, "数据库导航服务不可用: NavigationMenuMapper 未就绪");
    }
    try {
      List<NavigationMenuPermEntity> rules = menuMapper.selectOperationPermissionRules();
      return rules != null ? rules : Collections.emptyList();
    } catch (Exception e) {
      log.error("Failed to query operation permission rules from database: {}", e.getMessage(), e);
      throw new BizException(500, 50001, "数据库操作权限规则查询异常: " + e.getMessage());
    }
  }

  @Override
  public String getProviderName() {
    return "DATABASE";
  }
}
