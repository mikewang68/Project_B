package com.bdemo.iam.navigation.provider;

import com.bdemo.iam.navigation.model.NavigationAppEntity;
import com.bdemo.iam.navigation.model.NavigationMenuEntity;
import com.bdemo.iam.navigation.model.NavigationMenuPermEntity;
import com.bdemo.iam.navigation.service.NavigationDataLoader;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 阶段 2-B.2 离线受控 Fixture 导航数据提供者。
 * 仅用于离线契约测试、Schema 校验测试与无数据库环境的受控回归。
 */
@Component
public class FixtureNavigationProvider implements NavigationDataProvider {

  private final NavigationDataLoader dataLoader;

  @Autowired
  public FixtureNavigationProvider(NavigationDataLoader dataLoader) {
    this.dataLoader = dataLoader;
  }

  @Override
  public List<NavigationAppEntity> getActiveApps() {
    return Collections.emptyList();
  }

  @Override
  public List<NavigationMenuEntity> getNavEligibleMenus() {
    return dataLoader.getDefaultMenus();
  }

  @Override
  public List<NavigationMenuPermEntity> getViewPermissionRules() {
    return dataLoader.getDefaultPerms();
  }

  @Override
  public List<NavigationMenuPermEntity> getOperationPermissionRules() {
    return Collections.emptyList();
  }

  @Override
  public String getProviderName() {
    return "FIXTURE";
  }
}
