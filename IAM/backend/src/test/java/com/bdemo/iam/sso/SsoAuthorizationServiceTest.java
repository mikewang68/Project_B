package com.bdemo.iam.sso;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.security.*;
import com.bdemo.iam.security.mapper.SessionMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SsoAuthorizationServiceTest {
  final SessionMapper sessions=mock(SessionMapper.class);
  final LoginUserLoader users=mock(LoginUserLoader.class);
  final SsoSubjectGuard subjects=mock(SsoSubjectGuard.class);
  final LoginUser user=new LoginUser("subject","operator","Operator",false,Set.of("pps:plan:read"));
  final SsoAuthorizationService service=new SsoAuthorizationService(sessions,users,subjects);
  @BeforeEach void setup(){
    user.setScopedApps(Set.of("PSMS"));
    when(sessions.active("sid","subject")).thenReturn(1);when(users.load("subject")).thenReturn(user);
    when(subjects.localSubject("subject","psms")).thenReturn("business-user");
    when(subjects.scopeFor("subject","psms")).thenReturn(Map.of("company","","org","","domain","","zone","","local","business-user"));
  }
  @Test void explicitApplicationGrantUsesTheMappedLocalSubject(){assertEquals("business-user",service.current("subject","sid","psms").get("localSubject"));}
  @Test void currentRevocationIsImmediate(){when(sessions.active("sid","subject")).thenReturn(0);assertThrows(SsoTicketService.SsoTicketException.class,()->service.current("subject","sid","psms"));verifyNoInteractions(users);}
  @Test void disabledUserIsRejected(){when(users.load("subject")).thenReturn(null);assertThrows(SsoTicketService.SsoTicketException.class,()->service.current("subject","sid","psms"));}
  @Test void unrelatedPermissionDoesNotGrantTarget(){assertThrows(SsoTicketService.SsoTicketException.class,()->service.current("subject","sid","trust"));}
  @Test void missingMappingCannotFallbackToAdministrator(){user.setSuperAdmin(true);doThrow(new SsoTicketService.SsoTicketException("无显式映射")).when(subjects).requireBound("subject","operator","psms");assertThrows(SsoTicketService.SsoTicketException.class,()->service.current("subject","sid","psms"));}
  @Test void readonlyWinsAndVersionTracksChanges(){
    user.setSuperAdmin(true);String version=(String)service.current("subject","sid","psms").get("authorizationVersion");
    user.setReadOnly(true);var a=service.current("subject","sid","psms");
    assertEquals(true,a.get("readOnly"));assertEquals(false,a.get("superAdmin"));assertNotEquals(version,a.get("authorizationVersion"));
  }
  @Test void readonlyModeCannotRetainAnUnauthorizedAudience(){
    user.setReadOnly(true);user.setAccessMode("READONLY_AUDITOR");
    assertEquals(true,service.current("subject","sid","psms").get("readOnly"));
    user.setPermissions(Set.of("iam:user:list:view"));user.setScopedApps(Set.of("IAM"));
    assertThrows(SsoTicketService.SsoTicketException.class,()->service.current("subject","sid","psms"));
    user.setSuperAdmin(true);
    assertThrows(SsoTicketService.SsoTicketException.class,()->service.current("subject","sid","psms"));
  }
  com.bdemo.iam.scope.ScopeContextService versionTwo() {
    var scopes=mock(com.bdemo.iam.scope.ScopeContextService.class);
    var registry=mock(com.bdemo.iam.scope.ApplicationRegistry.class);
    when(registry.accepts("psms")).thenReturn(true);when(registry.contract("psms")).thenReturn(2);
    when(registry.permissionSystem("psms")).thenReturn("pps");
    org.springframework.test.util.ReflectionTestUtils.setField(service,"scopeContexts",scopes);
    org.springframework.test.util.ReflectionTestUtils.setField(service,"registry",registry);
    user.setUserContext(mock(com.bproject.iam.context.UserContext.class));
    return scopes;
  }
  @Test void consumerAssertionLoadsTheCentralSidSelectionBeforeAudienceProjection() {
    var scopes=versionTwo();
    var selected=mock(com.bproject.iam.context.UserContext.class);
    doAnswer(call->{user.setUserContext(selected);return null;}).when(scopes).forSession(user,"sid");
    when(scopes.forAudience(user,"psms")).thenReturn(selected);
    assertSame(selected,service.current("subject","sid","psms").get("userContext"));
    var order=inOrder(scopes);order.verify(scopes).forSession(user,"sid");order.verify(scopes).forAudience(user,"psms");
  }
  @Test void currentSelectionRevocationCannotFallBackToDefaultAudience() {
    var scopes=versionTwo();
    doAnswer(call->{user.setScopedApps(Set.of());return null;}).when(scopes).forSession(user,"sid");
    assertThrows(SsoTicketService.SsoTicketException.class,()->service.current("subject","sid","psms"));
    verify(scopes,never()).forAudience(any(),anyString());
  }
  @Test void currentNativeReadinessIsCheckedAfterSessionSelectionOnEveryAssertion() {
    var scopes=versionTwo();var selected=mock(com.bproject.iam.context.UserContext.class);
    when(scopes.forAudience(user,"psms")).thenReturn(selected);
    service.current("subject","sid","psms");verify(subjects).requireNativeScope("subject","psms",selected);
    doThrow(new SsoTicketService.SsoTicketException("NATIVE_SCOPE_MAPPING_REQUIRED")).when(subjects).requireNativeScope("subject","psms",selected);
    assertThrows(SsoTicketService.SsoTicketException.class,()->service.current("subject","sid","psms"));
    verify(scopes,times(2)).forSession(user,"sid");
  }
  @Test void malformedOrExpiredSelectionUsesTheExistingSsoDenialContract() {
    var scopes=versionTwo();
    doThrow(com.bdemo.iam.common.BizException.unauthorized("invalid selection")).when(scopes).forSession(user,"sid");
    assertThrows(SsoTicketService.SsoTicketException.class,()->service.current("subject","sid","psms"));
    verifyNoInteractions(subjects);
  }
}
