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
}
