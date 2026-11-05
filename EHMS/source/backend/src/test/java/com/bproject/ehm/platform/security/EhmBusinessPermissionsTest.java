package com.bproject.ehm.platform.security;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
class EhmBusinessPermissionsTest {
 @Test void publishedGovernanceResourcesRequireTheirOwnMenuAndNativePermission(){
  String[][] reads={{"device-templates","device-template","ehealth:asset:template:view"},
   {"calibration-records","sensor-cal","ehealth:asset:sensor:view"},{"configuration-changes","config-change","ehealth:asset:history:view"},
   {"my-tasks","my-tasks","ehealth:wb:todo:view"},{"shift-handovers","shift-handover","ehealth:wb:shift:view"},
   {"reliability/failure-modes","fmeca","ehealth:asset:fmeca:view"},{"reliability/fault-codes","fault-codes","ehealth:alert:code:view"},
   {"reliability/alarm-rules","alarm-rules","ehealth:alert:rule:view"},{"reliability/knowledge-cases","knowledge","ehealth:alert:kb:view"},
   {"reliability/sla-policies","sla","ehealth:alert:sla:view"}};
  for(var row:reads){var operations=java.util.Map.of("EHMS:"+row[1],Set.of("VIEW"));String path="/api/ehm/v1/"+row[0];
   assertTrue(EhmBusinessPermissions.permits(centralActor(true,Set.of(row[2]),operations),"GET",path),path);
   assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of(),operations),"GET",path),path);
   assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of(row[2]),java.util.Map.of("EHMS:fleet",Set.of("VIEW"))),"GET",path),path);
   assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of(row[2]),operations),"POST",path),path);
   assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of(row[2]),operations),"GET",path+"/export"),path);
  }
 }
 @Test void governanceConfirmationIsIndependentAndUnknownEndpointsFailClosed(){
  String[][] transitions={{"device-templates/T/publish","device-template","ehealth:asset:template:edit"},
   {"configuration-changes/C/approve","config-change","ehealth:asset:history:edit"},
   {"configuration-changes/C/apply","config-change","ehealth:asset:history:edit"},
   {"my-tasks/T/complete","my-tasks","ehealth:wb:todo:execute"},
   {"shift-handovers/H/submit","shift-handover","ehealth:wb:shift:edit"},
   {"shift-handovers/H/receive","shift-handover","ehealth:wb:shift:edit"},
   {"reliability/alarm-rules/R/publish","alarm-rules","ehealth:alert:rule:execute"},
   {"reliability/knowledge-cases/K/verify","knowledge","ehealth:alert:kb:edit"}};
  for(var row:transitions){String path="/api/ehm/v1/"+row[0];var permissions=Set.of(row[2]);
   assertTrue(EhmBusinessPermissions.permits(centralActor(false,permissions,java.util.Map.of("EHMS:"+row[1],Set.of("CONFIRM"))),"POST",path),path);
   assertFalse(EhmBusinessPermissions.permits(centralActor(true,permissions,java.util.Map.of("EHMS:"+row[1],Set.of("CONFIRM"))),"POST",path),path);
   for(String op:Set.of("VIEW","ADD","EDIT"))assertFalse(EhmBusinessPermissions.permits(centralActor(false,permissions,java.util.Map.of("EHMS:"+row[1],Set.of(op))),"POST",path),path);
  }
  for(String path:Set.of("reliability","reliability/unknown","my-tasks/T/unknown","device-templates/T/delete"))
   assertNull(EhmCentralScope.governanceRoute("POST",path));
 }
 @Test void governanceAddAndEditMatchOnlyImplementedEndpoints(){
  assertEquals("ADD",EhmCentralScope.governanceRoute("POST","shift-handovers/generate").action());
  assertEquals("EDIT",EhmCentralScope.governanceRoute("PUT","reliability/sla-policies/L3").action());
  assertEquals("EDIT",EhmCentralScope.governanceRoute("POST","reliability/alarm-rules/R/disable").action());
  assertNull(EhmCentralScope.governanceRoute("POST","reliability/sla-policies"));
  assertNull(EhmCentralScope.governanceRoute("DELETE","device-templates/T"));
  assertNull(EhmCentralScope.governanceRoute("PUT","calibration-records/C"));
 }
 @Test void simulationStatusReadDoesNotGrantControlOrAccountChanges(){var a=actor(false,Set.of("ehealth:wb:cockpit:view"));assertTrue(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/system/simulation"));assertFalse(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/system/simulation/start"));assertFalse(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/auth/users"));}
 EhmSsoSessions.Actor actor(boolean readonly,Set<String> permissions){return new EhmSsoSessions.Actor("subject","maintenance","USER","sid",readonly,Set.of("PROJECT_B"),permissions,false);}
 @Test void maintenanceWorkDoesNotGrantAssetOrAccountAdministration(){var a=actor(false,Set.of("ehealth:maint:order:view","ehealth:maint:order:execute"));assertTrue(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/work-orders"));assertTrue(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/work-orders/W1/start"));assertFalse(EhmBusinessPermissions.permits(a,"DELETE","/api/ehm/v1/devices/D1"));assertFalse(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/auth/users"));assertFalse(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/work-orders/W1/approve"));}
 @Test void readonlyAndExportRemainIndependent(){var a=actor(true,Set.of("ehealth:maint:order:view","ehealth:maint:order:execute"));assertFalse(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/work-orders/W1/start"));assertFalse(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/work-orders/export"));}
 @Test void maintenanceDashboardUsesItsExplicitReadGrant(){var a=actor(false,Set.of("ehealth:wb:cockpit:view"));assertTrue(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/dashboard/summary"));assertTrue(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/analytics/overview"));assertFalse(EhmBusinessPermissions.permits(a,"POST","/api/ehm/v1/dashboard/summary"));assertFalse(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/analytics/overview/export"));}
 @Test void deviceAndAlarmReadsDoNotGrantDownloads(){var a=actor(false,Set.of("ehealth:asset:archive:view","ehealth:alert:center:view"));assertTrue(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/devices"));assertFalse(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/devices/export"));assertFalse(EhmBusinessPermissions.permits(a,"GET","/api/ehm/v1/alarms/download"));}
 @Test void approvalCannotHideInAGenericStatusBody(){var a=actor(false,Set.of("ehealth:maint:order:execute"));assertTrue(EhmBusinessPermissions.permitsWorkOrderStatus(a,"SUBMITTED"));assertFalse(EhmBusinessPermissions.permitsWorkOrderStatus(a,"APPROVED"));assertFalse(EhmBusinessPermissions.permitsWorkOrderStatus(a,"REJECTED"));assertTrue(EhmBusinessPermissions.permitsWorkOrderStatus(a,"CANCELLED"));}
 EhmSsoSessions.Actor centralActor(boolean readonly,Set<String> nativePermissions,java.util.Map<String,Set<String>> operations){
  var tuple=new com.bproject.iam.context.ScopeTriple("PROJECT_B","SHEN_TIE","EHMS");
  var filter=com.bproject.iam.context.ScopeSql.build(com.bproject.iam.context.ScopeSql.Mode.SCOPED,java.util.List.of(tuple));
  var context=new com.bproject.iam.context.UserContext(2,"subject","operator","subject","operator","L4_OPERATOR",readonly,"EHMS",
   "PROJECT_B","SHEN_TIE","EHMS",com.bproject.iam.context.ScopeSql.Mode.SCOPED,java.util.List.of(tuple),java.util.List.of(tuple),
   Set.of("job"),operations.keySet(),operations,Set.of(),"1",filter.my_info(),filter.my_project_zone_info(),
   filter.my_info_template(),filter.my_project_zone_info_template(),filter.scope_params());
  return new EhmSsoSessions.Actor("subject","maintenance","USER","sid",readonly,Set.of("PROJECT_B"),nativePermissions,false,context);
 }
 @Test void telemetryRequiresBothRealtimeMenuAndNativeReadPermission(){
  String route="/api/ehm/v1/telemetry/points/P-1/samples";
  var operations=java.util.Map.of("EHMS:realtime",Set.of("VIEW"));
  assertTrue(EhmBusinessPermissions.permits(centralActor(true,Set.of("ehealth:rt:realtime:view"),operations),"GET",route));
  assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of(),operations),"GET",route));
  assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of("ehealth:rt:realtime:view"),java.util.Map.of()),"GET",route));
  assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of("ehealth:rt:realtime:view"),operations),"POST",route));
 }
 @Test void centralWorkOrderTransitionUsesConfirmWithoutRequiringEdit(){
  String route="/api/ehm/v1/work-orders/W1/status";
  var operator=centralActor(false,Set.of("ehealth:maint:order:execute"),
   java.util.Map.of("EHMS:workorders",Set.of("VIEW","CONFIRM")));
  assertTrue(EhmBusinessPermissions.permits(operator,"PATCH",route));
  assertTrue(EhmBusinessPermissions.permitsWorkOrderStatus(operator,"SUBMITTED"));
  assertFalse(EhmBusinessPermissions.permitsWorkOrderStatus(operator,"APPROVED"));
  var approver=centralActor(false,Set.of("ehealth:maint:order:execute","ehealth:maint:order:approve"),
   java.util.Map.of("EHMS:workorders",Set.of("VIEW","CONFIRM")));
  assertTrue(EhmBusinessPermissions.permitsWorkOrderStatus(approver,"APPROVED"));
 }
 @Test void editOrAddCannotGrantCentralWorkOrderConfirmation(){
  String route="/api/ehm/v1/work-orders/W1/status";
  for(var actions:java.util.List.of(Set.of("VIEW","EDIT"),Set.of("VIEW","ADD"))){
   var operator=centralActor(false,Set.of("ehealth:maint:order:execute","ehealth:maint:order:approve"),
    java.util.Map.of("EHMS:workorders",actions));
   assertFalse(EhmBusinessPermissions.permits(operator,"PATCH",route));
   assertFalse(EhmBusinessPermissions.permitsWorkOrderStatus(operator,"SUBMITTED"));
  }
  var reader=centralActor(true,Set.of("ehealth:maint:order:execute"),
   java.util.Map.of("EHMS:workorders",Set.of("VIEW","CONFIRM")));
  assertFalse(EhmBusinessPermissions.permits(reader,"PATCH",route));
 }
 @Test void telemetryIngestionRequiresEditAndNativeExecuteAndVetoesReadonly(){
  String route="/api/ehm/v1/data-quality/points/P-1/sample";
  var operations=java.util.Map.of("EHMS:data-quality",Set.of("VIEW","EDIT"));
  assertTrue(EhmBusinessPermissions.permits(centralActor(false,Set.of("ehealth:edge:quality:execute"),operations),"POST",route));
  assertFalse(EhmBusinessPermissions.permits(centralActor(true,Set.of("ehealth:edge:quality:execute"),operations),"POST",route));
  assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of("ehealth:edge:quality:view"),operations),"POST",route));
  assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of("ehealth:edge:quality:execute"),
   java.util.Map.of("EHMS:data-quality",Set.of("VIEW"))),"POST",route));
  assertFalse(EhmBusinessPermissions.permits(centralActor(false,Set.of("ehealth:edge:quality:execute"),operations),
   "POST","/api/ehm/v1/data-quality/points/P-1/unknown"));
 }
}
