package com.bdemo.auth;
/** Confirmation is independent of dispatching, closure and escalation. */
public final class EmcsAlertPermissions {
 private EmcsAlertPermissions(){}
 public static boolean permits(EmcsSsoSessions.Current current,String target){
  if(current.readOnly())return false;
  if(current.permissions().contains("*:*:*")||current.permissions().contains("EMCS_APPLICATION_ADMIN"))return true;
  return current.centralPermissions().contains("ems:alert:flow:execute")
      || "ack".equals(target)&&current.centralPermissions().contains("ems:alert:confirm:execute");
 }
}
