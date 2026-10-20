package com.bdemo.iam.navigation.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.navigation.mapper.NavigationMenuMapper;
import com.bdemo.iam.navigation.model.NavigationAppEntity;
import com.bdemo.iam.navigation.model.NavigationMenuEntity;
import com.bdemo.iam.navigation.model.NavigationMenuPermEntity;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class DatabaseNavigationProviderTest {

  private NavigationMenuMapper mapper;
  private DatabaseNavigationProvider provider;

  @BeforeEach
  void setUp() {
    mapper = mock(NavigationMenuMapper.class);
    provider = new DatabaseNavigationProvider(mapper);
  }

  @Test
  void test_getActiveApps_success() {
    NavigationAppEntity app = new NavigationAppEntity();
    app.setAppCode("IAM");
    app.setDisplayName("统一身份系统");
    when(mapper.selectActiveApps()).thenReturn(List.of(app));

    List<NavigationAppEntity> res = provider.getActiveApps();
    assertEquals(1, res.size());
    assertEquals("IAM", res.get(0).getAppCode());
    verify(mapper, times(1)).selectActiveApps();
  }

  @Test
  void test_getNavEligibleMenus_db_error_fail_closed_50001() {
    when(mapper.selectNavEligibleMenus(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new DataAccessResourceFailureException("Connection refused"));

    BizException ex = assertThrows(BizException.class, () -> provider.getNavEligibleMenus());
    assertEquals(500, ex.getHttpStatus());
    assertEquals(50001, ex.getCode());
    assertTrue(ex.getMessage().contains("数据库导航菜单查询异常"));
  }

  @Test
  void test_getViewPermissionRules_success() {
    NavigationMenuPermEntity perm = new NavigationMenuPermEntity();
    perm.setStableMenuKey("IAM:users");
    perm.setMappingStatus("VERIFIED");
    perm.setApprovalStatus("APPROVED");
    when(mapper.selectViewPermissionRules()).thenReturn(List.of(perm));

    List<NavigationMenuPermEntity> res = provider.getViewPermissionRules();
    assertEquals(1, res.size());
    assertEquals("IAM:users", res.get(0).getStableMenuKey());
    assertEquals("VERIFIED", res.get(0).getMappingStatus());
    assertEquals("APPROVED", res.get(0).getApprovalStatus());
  }

  @Test
  void test_null_mapper_throws_50001() {
    DatabaseNavigationProvider nullProvider = new DatabaseNavigationProvider(null);
    BizException ex = assertThrows(BizException.class, nullProvider::getActiveApps);
    assertEquals(50001, ex.getCode());
  }
}
