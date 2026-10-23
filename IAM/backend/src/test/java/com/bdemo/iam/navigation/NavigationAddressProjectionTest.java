package com.bdemo.iam.navigation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.navigation.model.NavigationAppEntity;
import com.bdemo.iam.navigation.provider.DatabaseNavigationProvider;
import com.bdemo.iam.navigation.provider.FixtureNavigationProvider;
import com.bdemo.iam.navigation.service.NavigationDataLoader;
import com.bdemo.iam.navigation.service.NavigationProjectionService;
import com.bdemo.iam.security.LoginUser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class NavigationAddressProjectionTest {
  private DatabaseNavigationProvider provider;
  private NavigationProjectionService service;
  private NavigationAppEntity address;
  @BeforeEach void setUp() {
    var loader = new NavigationDataLoader();
    provider = mock(DatabaseNavigationProvider.class);
    when(provider.getProviderName()).thenReturn("DATABASE");
    when(provider.getNavEligibleMenus()).thenReturn(loader.getDefaultMenus());
    var rules = loader.getDefaultPerms();
    rules.forEach(rule -> rule.setApprovalStatus("APPROVED"));
    when(provider.getViewPermissionRules()).thenReturn(rules);
    address = new NavigationAppEntity(); address.setAppCode("IAM");
    address.setDomainName("identity.example.test"); address.setPublicScheme("https");
    address.setOriginIp("192.0.2.74"); address.setOriginPort(18091); address.setModulePath("/");
    address.setEntryBasePath("/iam/");
    when(provider.getActiveApps()).thenReturn(List.of(address));
    service = new NavigationProjectionService(provider,new FixtureNavigationProvider(loader));
    login(new LoginUser("test-id", "test-user", "Test", true, Set.of()));
  }
  private void login(LoginUser user) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
  }
  @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
  @Test void disabledByDefaultDoesNotQueryAddressMetadata() {
    assertNull(service.projectNavigationTree("IAM", false).getSystems().get(0).getEntryBaseUrl());
    verify(provider,never()).getActiveApps();
  }
  @Test void enabledProjectsConfiguredBaseAndPreservesPermissionAndMenuData() {
    ReflectionTestUtils.setField(service,"externalAddressesEnabled",true);
    var system = service.projectNavigationTree("IAM",false).getSystems().get(0);
    assertEquals("https://identity.example.test/",system.getEntryBaseUrl());
    assertEquals("/iam/",system.getEntryBasePath());
    assertEquals(4,system.getChildren().size());
    assertTrue(system.getChildren().stream().allMatch(page -> page.getEntryPath().startsWith("/iam/")));
  }
  @Test void noDomainProjectsIpFallback() {
    ReflectionTestUtils.setField(service,"externalAddressesEnabled",true);
    address.setDomainName(""); address.setOriginScheme("http");
    assertEquals("http://192.0.2.74:18091/",service.projectNavigationTree("IAM",false).getSystems().get(0).getEntryBaseUrl());
  }
  @Test void invalidConfiguredAddressFailsClosed() {
    ReflectionTestUtils.setField(service,"externalAddressesEnabled",true);
    address.setDomainName("https://evil.test");
    assertEquals(50001,assertThrows(BizException.class,() -> service.projectNavigationTree("IAM",false)).getCode());
  }
  @Test void configuredDomainDoesNotGrantAnUnauthorizedSystem() {
    ReflectionTestUtils.setField(service,"externalAddressesEnabled",true);
    login(new LoginUser("scoped-id","scoped","Scoped",false,Set.of("wms:inbound:view")));
    assertTrue(service.projectNavigationTree("IAM",false).getSystems().isEmpty());
  }
}
