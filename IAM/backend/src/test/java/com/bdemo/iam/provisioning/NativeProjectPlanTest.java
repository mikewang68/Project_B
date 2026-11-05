package com.bdemo.iam.provisioning;

import static org.junit.jupiter.api.Assertions.*;
import com.bproject.iam.context.ScopeTriple;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeProjectPlanTest {
  final ScopeTriple a=new ScopeTriple("P","R1","WMS"),b=new ScopeTriple("P","R2","WMS");
  ObjectNode policy(ScopeTriple s) {
    return NativeOnboardingWorker.JSON.createObjectNode().put("projectId",s.projectId()).put("regionId",s.regionId())
        .put("subsystemCode",s.subsystemCode()).put("companyCode","known_company").put("roleCode","explicit_reader")
        .put("warehouseCode","warehouse_"+s.regionId());
  }
  ArrayNode policies(ObjectNode... p){var result=NativeOnboardingWorker.JSON.createArrayNode();for(var x:p)result.add(x);return result;}
  @Test void missingTupleIsExplicitAndPreservesKnownTuple()throws Exception {
    var p=NativeProjectPlan.create(List.of(a,b),policies(policy(a)),"WMS");
    assertEquals(List.of(a),p.mappings().stream().map(NativeProjectPlan.Mapping::scope).toList());
    assertEquals(b,p.missing().get(0).scope());assertEquals("NATIVE_SCOPE_MAPPING_NOT_CONFIGURED",p.missing().get(0).reason());
  }
  @Test void noPolicyDoesNotInventWarehouseOrOrganization()throws Exception {
    var p=NativeProjectPlan.create(List.of(a),policies(),"WMS");assertTrue(p.mappings().isEmpty());assertNull(p.merged());assertEquals(1,p.missing().size());
  }
  @Test void duplicateAndCrossAudienceMappingsAreRejected() {
    assertThrows(IllegalStateException.class,()->NativeProjectPlan.create(List.of(a),policies(policy(a),policy(a)),"WMS"));
    assertThrows(IllegalStateException.class,()->NativeProjectPlan.create(List.of(a),policies(policy(a)),"SCS"));
  }
  @Test void multipleRegionsShareOnlyAnExplicitSupportedNativeBoundary()throws Exception {
    var p=NativeProjectPlan.create(List.of(a,b),policies(policy(a),policy(b)),"WMS");assertEquals(2,p.mappings().size());
    assertThrows(IllegalStateException.class,()->NativeProjectPlan.create(List.of(a,b),policies(policy(a),policy(b).put("companyCode","different")),"WMS"));
    assertThrows(IllegalStateException.class,()->NativeProjectPlan.create(List.of(a,b),policies(policy(a),policy(b).put("roleCode","different")),"WMS"));
  }
  @Test void emptyNativeAnchorIsNeverDefaulted() {
    assertThrows(IllegalStateException.class,()->NativeProjectPlan.create(List.of(a),policies(policy(a).put("companyCode","")),"WMS"));
  }
  @Test void explicitTechnicalPermissionsUnionWithoutDuplicateEntries()throws Exception {
    var x=new ScopeTriple("P","R1","EHMS");var y=new ScopeTriple("P","R2","EHMS");
    var p=policy(x).put("registry","/private/known.json");p.putArray("permissions").add("device:read");
    var q=policy(y).put("registry","/private/known.json");q.putArray("permissions").add("device:read").add("report:read");
    var result=NativeProjectPlan.create(List.of(x,y),policies(p,q),"EHMS");
    assertEquals(2,result.merged().path("permissions").size());
  }
  @Test void readinessContainsSortedDistinctExactTuples() {
    var result=NativeProjectPlan.snapshot(List.of(b,a,a),false);assertEquals("PARTIAL",result.path("state").asText());
    assertEquals(2,result.path("scopes").size());assertEquals("R1",result.path("scopes").get(0).path("regionId").asText());
  }
}
