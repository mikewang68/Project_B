package com.bdemo.iam.navigation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.log.OperationLogger;
import com.bdemo.iam.navigation.service.NavigationAddressPublicationService;
import com.bdemo.iam.navigation.service.NavigationAddressPublicationService.Address;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.sso.SsoServiceIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class NavigationAddressPublicationTest {
  JdbcTemplate db;NavigationAddressPublicationService service;
  @BeforeEach void prepare(){db=mock(JdbcTemplate.class);service=new NavigationAddressPublicationService(db,
      new ObjectMapper(),mock(SsoServiceIdentity.class),mock(OperationLogger.class),"example.test");}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  void login(boolean platform,boolean readonly){var user=new LoginUser("u","operator","operator",platform,Set.of());user.setReadOnly(readonly);
    user.setAdminAppCodes(Set.of("IAM"));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));}
  Map<String,Object> active(){return new HashMap<>(Map.of("app_code","WMS","domain_name","warehouse.example.test",
      "public_scheme","https","public_port",443,"origin_scheme","https","origin_ip","192.0.2.10",
      "origin_port",18444,"module_path","/","address_version",3L));}
  @Test void dedicatedIamAdministratorAndGlobalReadonlyCannotChangePlatformAddresses(){
    login(false,false);assertThrows(BizException.class,()->service.list());verifyNoInteractions(db);
    login(true,true);assertThrows(BizException.class,()->service.list());verifyNoInteractions(db);
  }
  @Test void staleDraftIsRejectedBeforeAnyWrite(){
    login(true,false);when(db.queryForList(anyString(),eq("WMS"))).thenReturn(List.of(active()));
    assertThrows(BizException.class,()->service.draft("WMS",2,new Address("new.example.test","https",443,"https","192.0.2.10",18444,"/")));
    verify(db,never()).update(anyString(),any(Object[].class));
  }
  @Test void unpublishedOrExpiredHistoricalVersionCannotBeUsedForAnSsoTransaction(){
    when(db.queryForList(anyString(),eq("WMS"))).thenReturn(List.of(active()));
    assertThrows(BizException.class,()->service.runtime("WMS",2L));
    assertEquals("https://warehouse.example.test/",service.runtime("WMS",null).get("baseUrl"));
    assertEquals(3L,service.runtime("WMS",null).get("version"));
  }
  @Test void rejectsForeignHostsAndInsecureBrowserFallback(){
    login(true,false);when(db.queryForList(anyString(),eq("WMS"))).thenReturn(List.of(active()));
    assertThrows(BizException.class,()->service.draft("WMS",3,new Address("external.invalid","https",443,"https","192.0.2.10",18444,"/")));
    assertThrows(BizException.class,()->service.draft("WMS",3,new Address("","https",443,"http","192.0.2.10",18080,"/")));
  }
  @Test void resolvesDomainAndIpPortsIndependently(){
    assertEquals("https://warehouse.example.test/ehm/",new Address("warehouse.example.test","https",443,"http","192.0.2.10",18090,"/ehm/").baseUrl());
    assertEquals("https://192.0.2.10:18444/",new Address("","https",443,"https","192.0.2.10",18444,"/").baseUrl());
  }
}
