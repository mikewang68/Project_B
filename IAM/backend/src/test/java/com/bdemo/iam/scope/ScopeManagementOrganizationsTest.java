package com.bdemo.iam.scope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bdemo.iam.common.BizException;
import com.bdemo.iam.org.OrgService;
import com.bdemo.iam.org.domain.OrgNode;
import com.bproject.iam.context.ScopeSql;
import com.bproject.iam.context.UserContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScopeManagementOrganizationsTest {
  private final ScopeContextService contexts=mock(ScopeContextService.class);
  private final OrgService organizations=mock(OrgService.class);
  private final ScopeManagementController controller=
      new ScopeManagementController(null,contexts,null,null,null,organizations);
  private UserContext actor(String level,boolean readOnly,Set<String> capabilities) {
    var context=mock(UserContext.class);
    when(context.adminLevel()).thenReturn(level);
    when(context.readOnly()).thenReturn(readOnly);
    when(context.managementCapabilities()).thenReturn(capabilities);
    when(context.scopeMode()).thenReturn(ScopeSql.Mode.DENY);
    when(contexts.requireManager(false)).thenReturn(context);
    return context;
  }
  @Test void unconfiguredProjectDoesNotBlockL1ManagementMetadata() {
    actor("L1_SUPER",false,Set.of("VIEW_MANAGEMENT","CONFIGURE_PLATFORM"));
    var node=new OrgNode();node.setId("org");node.setCode("ORG");
    when(organizations.tree()).thenReturn(List.of(node));
    assertEquals(List.of(node),controller.organizations().getData());
    verify(organizations).tree();
  }
  @Test void subordinateLevelsAndReadonlyDoNotGainOrganizationDirectoryAccess() {
    for(String level:List.of("L1_READONLY","L2_PROJECT","L3_SUBSYSTEM","L4_OPERATOR")) {
      actor(level,false,Set.of("VIEW_MANAGEMENT","CONFIGURE_PLATFORM"));
      assertEquals(403,assertThrows(BizException.class,controller::organizations).getHttpStatus());
    }
    actor("L1_SUPER",true,Set.of("VIEW_MANAGEMENT","CONFIGURE_PLATFORM"));
    assertEquals(403,assertThrows(BizException.class,controller::organizations).getHttpStatus());
    verifyNoInteractions(organizations);
  }
  @Test void missingCapabilityOrUnauthenticatedContextFailsBeforeReadingOrganizations() {
    actor("L1_SUPER",false,Set.of("VIEW_MANAGEMENT"));
    assertEquals(403,assertThrows(BizException.class,controller::organizations).getHttpStatus());
    when(contexts.requireManager(false)).thenThrow(BizException.unauthorized("请先登录"));
    assertEquals(401,assertThrows(BizException.class,controller::organizations).getHttpStatus());
    verifyNoInteractions(organizations);
  }
}
