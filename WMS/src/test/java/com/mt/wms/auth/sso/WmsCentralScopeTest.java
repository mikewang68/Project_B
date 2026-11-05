package com.mt.wms.auth.sso;

import static org.junit.jupiter.api.Assertions.*;
import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;

class WmsCentralScopeTest {
  UserContext context(Set<String> ops,boolean readOnly) {
    var triple=new ScopeTriple("PROJECT_B","SHEN_TIE","WMS");
    var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(triple));
    return new UserContext(2,"subject","operator","subject","operator","L4_OPERATOR",readOnly,"WMS",
        triple.projectId(),triple.regionId(),triple.subsystemCode(),ScopeSql.Mode.SCOPED,
        List.of(triple),List.of(triple),Set.of("business-job"),Set.of("WMS:stockin"),
        Map.of("WMS:stockin",ops),Set.of(),"1",f.my_info(),f.my_project_zone_info(),
        f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
  }
  @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
  @Test void quickReceiptRequiresBothCreationAndReceivingActions() {
    assertFalse(WmsCentralScope.route(context(Set.of("VIEW","ADD"),false),"POST","/api/v1/stockin/quick"));
    assertFalse(WmsCentralScope.route(context(Set.of("VIEW","EDIT"),false),"POST","/api/v1/stockin/quick"));
    assertTrue(WmsCentralScope.route(context(Set.of("VIEW","ADD","EDIT"),false),"POST","/api/v1/stockin/quick"));
    assertFalse(WmsCentralScope.route(context(Set.of("VIEW","ADD","EDIT"),true),"POST","/api/v1/stockin/quick"));
  }
  @Test void creationEditingAndConfirmationRequireTheirOwnMenuActions() {
    var add=context(Set.of("VIEW","ADD"),false);
    assertTrue(WmsCentralScope.route(add,"PUT","/api/v1/auth/tenant"));
    assertFalse(WmsCentralScope.route(add,"POST","/api/v1/auth/me"));
    assertFalse(WmsCentralScope.route(add,"POST","/api/v1/auth/tenant/switch"));
    assertTrue(WmsCentralScope.route(add,"POST","/api/v1/stockin"));
    assertFalse(WmsCentralScope.route(add,"POST","/api/v1/stockin/1/complete"));
    assertFalse(WmsCentralScope.route(add,"POST","/api/v1/stockin/1/cancel"));
    assertFalse(WmsCentralScope.route(add,"GET","/api/v1/inventory/balances"));
    assertFalse(WmsCentralScope.route(add,"POST","/api/v1/stockin/1/new-unreviewed-action"));
    assertTrue(WmsCentralScope.route(context(Set.of("CONFIRM"),false),"POST","/api/v1/stockin/1/complete"));
    assertFalse(WmsCentralScope.route(context(Set.of("ADD","CONFIRM"),true),"POST","/api/v1/stockin"));
  }
  @Test void concurrentPsmsInboundActionsRemainPreservedAndSeparated() {
    assertTrue(WmsCentralScope.route(context(Set.of("ADD"),false),"POST","/api/v1/stockin/psms-tasks/1/confirm"));
    assertFalse(WmsCentralScope.route(context(Set.of("ADD"),false),"POST","/api/v1/stockin/psms-tasks/1/retry-delivery"));
    assertTrue(WmsCentralScope.route(context(Set.of("EDIT"),false),"POST","/api/v1/stockin/psms-tasks/1/retry-delivery"));
    assertFalse(WmsCentralScope.route(context(Set.of("ADD","EDIT"),true),"POST","/api/v1/stockin/psms-tasks/1/confirm"));
  }
  @Test void warehouseRootUsesTheSameParameterizedTupleAndRequestLifetime() {
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    WmsCentralScope.bind(context(Set.of("VIEW"),false));
    var f=WmsCentralScope.warehouseFilter();
    assertEquals(Map.of("p0","PROJECT_B","r0","SHEN_TIE","s0","WMS"),f.scope_params());
    assertTrue(f.my_info_template().contains("o.region_id = :r0"));
    WmsCentralScope.bind(null);
    assertNull(WmsCentralScope.warehouseFilter());
  }
  @Test void aFreshCentralResponseIsValidatedBeforeAnyScopeCanBeUsed() {
    var mapper=new ObjectMapper();
    var assertion=mapper.createObjectNode().put("subject","subject").put("readOnly",false);
    assertion.set("userContext",mapper.valueToTree(context(Set.of("VIEW"),false)));
    assertEquals("SHEN_TIE",WmsCentralScope.validate(assertion,mapper).regionId());
    ((com.fasterxml.jackson.databind.node.ObjectNode)assertion.get("userContext")).put("serviceAudience","PSMS");
    assertThrows(SecurityException.class,()->WmsCentralScope.validate(assertion,mapper));
  }
  UserContext master(Set<String> ops,String level) {
    var c=context(ops,false);
    return new UserContext(c.contextVersion(),c.userId(),c.username(),c.userCode(),c.displayName(),
        level,false,c.serviceAudience(),c.projectId(),c.regionId(),c.subsystemCode(),c.scopeMode(),
        c.allowedScopes(),c.activeScope(),c.roles(),Set.of("WMS:master-data"),
        Map.of("WMS:master-data",ops),c.managementCapabilities(),c.authorizationVersion(),
        c.my_info(),c.my_project_zone_info(),c.my_info_template(),c.my_project_zone_info_template(),c.scope_params());
  }
  @Test void regionalMaintenanceCannotChangeCompanyWideCatalogsOrInventActions() {
    var regional=master(Set.of("VIEW","ADD","EDIT","DELETE"),"L3_SUBSYSTEM");
    assertTrue(WmsCentralScope.route(regional,"GET","/api/v1/master-data/owners"));
    assertTrue(WmsCentralScope.route(regional,"POST","/api/v1/master-data/warehouses"));
    assertTrue(WmsCentralScope.route(regional,"PUT","/api/v1/master-data/areas/4"));
    assertFalse(WmsCentralScope.route(regional,"POST","/api/v1/master-data/owners"));
    assertFalse(WmsCentralScope.route(regional,"DELETE","/api/v1/master-data/goods/4"));
    assertFalse(WmsCentralScope.route(regional,"POST","/api/v1/master-data/areas/4/approve"));
    assertTrue(WmsCentralScope.route(master(Set.of("EDIT"),"L1_SUPER"),"PUT","/api/v1/master-data/goods/4"));
    assertFalse(WmsCentralScope.route(master(Set.of("VIEW"),"L1_SUPER"),"PUT","/api/v1/master-data/goods/4"));
  }
  @Test void aNewWarehouseRequiresOneConcreteWritableTuple() {
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    WmsCentralScope.bind(context(Set.of("ADD"),false));
    assertEquals(new ScopeTriple("PROJECT_B","SHEN_TIE","WMS"),WmsCentralScope.concreteWriteScope());
    WmsCentralScope.bind(context(Set.of("ADD"),true));
    assertThrows(org.springframework.security.access.AccessDeniedException.class,WmsCentralScope::concreteWriteScope);
  }
  @Test void personnelNavigationNeverEnablesTheSeparateNativeIdentityWriter() {
    var c=context(Set.of("VIEW"),false);
    for (String level:List.of("L1_SUPER","L1_READONLY","L2_PROJECT","L3_SUBSYSTEM","L4_OPERATOR")) {
      var user=new UserContext(c.contextVersion(),c.userId(),c.username(),c.userCode(),c.displayName(),
          level,level.equals("L1_READONLY"),c.serviceAudience(),c.projectId(),c.regionId(),c.subsystemCode(),c.scopeMode(),
          c.allowedScopes(),c.activeScope(),c.roles(),Set.of("WMS:users"),Map.of("WMS:users",Set.of("VIEW","ADD","EDIT")),
          c.managementCapabilities(),c.authorizationVersion(),c.my_info(),c.my_project_zone_info(),
          c.my_info_template(),c.my_project_zone_info_template(),c.scope_params());
      assertEquals(!level.equals("L4_OPERATOR"),WmsCentralScope.localPermission(user,"system:user:read"));
      assertFalse(WmsCentralScope.localPermission(user,"system:user:write"));
      assertFalse(WmsCentralScope.route(user,"GET","/api/v1/system/users"));
      assertFalse(WmsCentralScope.route(user,"POST","/api/v1/system/users"));
    }
    assertFalse(WmsCentralScope.localPermission(c,"system:user:read"));
  }
}
