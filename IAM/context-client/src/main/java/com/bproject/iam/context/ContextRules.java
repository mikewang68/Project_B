package com.bproject.iam.context;

import java.util.*;

/** Structural checks after authenticating the IAM service response. Not an authentication mechanism. */
public final class ContextRules {
  private ContextRules(){}
  public static UserContext validate(UserContext c,String subject,String audience) {
    if(c==null||c.contextVersion()!=2||!Objects.equals(c.userId(),subject)||subject==null||subject.isBlank()
        ||!Objects.equals(c.serviceAudience(),audience.toUpperCase(Locale.ROOT))
        ||!Set.of("L1_SUPER","L1_READONLY","L2_PROJECT","L3_SUBSYSTEM","L4_OPERATOR").contains(c.adminLevel())
        ||c.allowedScopes().size()>512||!c.allowedScopes().containsAll(c.activeScope()))throw new IllegalArgumentException("INVALID_TRUSTED_CONTEXT");
    boolean l1=Set.of("L1_SUPER","L1_READONLY").contains(c.adminLevel());
    if(c.scopeMode()==ScopeSql.Mode.GLOBAL&&!l1
        ||c.adminLevel().equals("L1_READONLY")&&!c.readOnly()
        ||c.scopeMode()==ScopeSql.Mode.SCOPED&&c.activeScope().isEmpty())throw new IllegalArgumentException("INVALID_CONTEXT_MODE");
    if(!l1) {
      ScopeTriple.identifier(c.projectId(),64);
      for(var s:c.allowedScopes())if(!s.projectId().equals(c.projectId()))throw new IllegalArgumentException("CROSS_PROJECT_CONTEXT");
    }
    if(Set.of("L3_SUBSYSTEM","L4_OPERATOR").contains(c.adminLevel())) {
      var fixed=new ScopeTriple(c.projectId(),c.regionId(),c.subsystemCode());
      if(c.allowedScopes().stream().anyMatch(s->!s.equals(fixed)))throw new IllegalArgumentException("UNLOCKED_ACCOUNT_CONTEXT");
    }
    if(c.scopeMode()==ScopeSql.Mode.SCOPED&&!audience.equalsIgnoreCase("IAM")
        &&c.activeScope().stream().anyMatch(s->!s.subsystemCode().equalsIgnoreCase(audience)))throw new IllegalArgumentException("CROSS_SYSTEM_CONTEXT");
    var f=ScopeSql.build(c.scopeMode(),c.activeScope());
    if(!f.my_info().equals(c.my_info())||!f.my_project_zone_info().equals(c.my_project_zone_info())
        ||!f.my_info_template().equals(c.my_info_template())||!f.my_project_zone_info_template().equals(c.my_project_zone_info_template())
        ||!f.scope_params().equals(c.scope_params()))throw new IllegalArgumentException("CONTEXT_FILTER_MISMATCH");
    return c;
  }
}
