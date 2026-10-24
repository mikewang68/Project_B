package com.bdemo.auth;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
class EmcsAlertPermissionsTest {
 @Test void confirmationDoesNotAllowOtherStatusChanges(){
  var current=new EmcsSsoSessions.Current(new AuthenticatedUser(42,"shift"),Set.of("ops"),Set.of("energy:alert:edit"),false,Set.of("ems:alert:confirm:execute"));
  assertTrue(EmcsAlertPermissions.permits(current,"ack"));
  for(String target:Set.of("dispatched","processing","closed","false_closed","escalated"))assertFalse(EmcsAlertPermissions.permits(current,target));
 }
 @Test void readonlyVetoAppliesToAnAdministrator(){
  var current=new EmcsSsoSessions.Current(new AuthenticatedUser(42,"reader"),Set.of("admin"),Set.of("EMCS_APPLICATION_ADMIN"),true,Set.of("ems:alert:flow:execute"));
  assertFalse(EmcsAlertPermissions.permits(current,"ack"));
 }
}
