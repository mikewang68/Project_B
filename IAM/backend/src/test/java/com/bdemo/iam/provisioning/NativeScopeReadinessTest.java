package com.bdemo.iam.provisioning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.iam.context.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeScopeReadinessTest {
  final ScopeTriple one=new ScopeTriple("P","R1","WMS"),two=new ScopeTriple("P","R2","WMS");
  UserContext context(String level,List<ScopeTriple> allowed,List<ScopeTriple> selected) {
    var c=mock(UserContext.class);when(c.adminLevel()).thenReturn(level);when(c.allowedScopes()).thenReturn(allowed);
    when(c.activeScope()).thenReturn(selected);when(c.scopeMode()).thenReturn(ScopeSql.Mode.SCOPED);return c;
  }
  String snapshot(List<ScopeTriple> scopes,boolean complete){return NativeProjectPlan.snapshot(scopes,complete).toString();}
  @Test void knownScopedIdentityNeedsExplicitNativeReadiness() {
    for(String level:List.of("L2_PROJECT","L3_SUBSYSTEM","L4_OPERATOR")) {
      var c=context(level,List.of(one),List.of(one));
      assertThrows(IllegalStateException.class,()->NativeScopeReadiness.require(c,"wms",null));
      assertDoesNotThrow(()->NativeScopeReadiness.require(c,"wms",snapshot(List.of(one),true)));
    }
  }
  @Test void expandedAuthorizationCannotReuseSingleScopeNativeProfile() {
    var c=context("L2_PROJECT",List.of(one,two),List.of(one,two));
    assertEquals("NATIVE_SCOPE_MAPPING_REQUIRED",assertThrows(IllegalStateException.class,
        ()->NativeScopeReadiness.require(c,"WMS",snapshot(List.of(one),false))).getMessage());
  }
  @Test void explicitlySelectingThePreviouslyVerifiedTupleStillWorks() {
    var c=context("L2_PROJECT",List.of(one,two),List.of(one));
    assertDoesNotThrow(()->NativeScopeReadiness.require(c,"WMS",snapshot(List.of(one),false)));
  }
  @Test void nativeSnapshotCannotGrantARevokedTuple() {
    var c=context("L2_PROJECT",List.of(one),List.of(two));
    assertThrows(IllegalStateException.class,()->NativeScopeReadiness.require(c,"WMS",snapshot(List.of(one,two),true)));
  }
  @Test void deniedOrGlobalScopedUserCannotBypassNativeBound() {
    var c=context("L2_PROJECT",List.of(one),List.of(one));
    for(var mode:List.of(ScopeSql.Mode.DENY,ScopeSql.Mode.GLOBAL)) {
      when(c.scopeMode()).thenReturn(mode);
      assertThrows(IllegalStateException.class,()->NativeScopeReadiness.require(c,"WMS",snapshot(List.of(one),true)));
    }
  }
  @Test void absentEmptyWrongVersionAndWrongAudienceAreRejected() {
    var c=context("L2_PROJECT",List.of(one),List.of(one));
    for(String raw:List.of("","{}","not-json","{\"version\":2,\"state\":\"READY\",\"scopes\":[]}",snapshot(List.of(),true),snapshot(List.of(new ScopeTriple("P","R1","SCS")),true)))
      assertThrows(IllegalStateException.class,()->NativeScopeReadiness.require(c,"WMS",raw));
  }
  @Test void centralManagementAndGlobalNativeBindingsKeepTheirSeparateContract() {
    for(String level:List.of("L1_SUPER","L1_READONLY"))assertDoesNotThrow(()->NativeScopeReadiness.require(context(level,List.of(),List.of()),"WMS",null));
    var l2=context("L2_PROJECT",List.of(),List.of());
    assertDoesNotThrow(()->NativeScopeReadiness.require(l2,"IAM",null));
    assertDoesNotThrow(()->NativeScopeReadiness.require(l2,"SYS",null));
  }
  @Test void validMultipleNativeMappingsDoNotCrossMultiplyRegions() {
    var c=context("L2_PROJECT",List.of(one,two),List.of(one,two));
    assertDoesNotThrow(()->NativeScopeReadiness.require(c,"WMS",snapshot(List.of(one,two),true)));
    var other=new ScopeTriple("OTHER","R2","WMS");when(c.activeScope()).thenReturn(List.of(other));
    assertThrows(IllegalStateException.class,()->NativeScopeReadiness.require(c,"WMS",snapshot(List.of(one,two),true)));
  }
}
