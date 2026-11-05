package com.bproject.iam.context;

import java.util.*;

/** Version 2 is additive to the existing SSO contract; browser copies are display-only. */
public record UserContext(int contextVersion,String userId,String username,String userCode,
    String displayName,String adminLevel,boolean readOnly,String serviceAudience,
    String projectId,String regionId,String subsystemCode,ScopeSql.Mode scopeMode,
    List<ScopeTriple> allowedScopes,List<ScopeTriple> activeScope,Set<String> roles,
    Set<String> visibleMenuKeys,Map<String,Set<String>> menuOperations,
    Set<String> managementCapabilities,String authorizationVersion,
    String my_info,String my_project_zone_info,String my_info_template,
    String my_project_zone_info_template,Map<String,String> scope_params) {
  public UserContext {
    allowedScopes=List.copyOf(allowedScopes);activeScope=List.copyOf(activeScope);
    roles=Set.copyOf(roles);visibleMenuKeys=Set.copyOf(visibleMenuKeys);
    var safe=new TreeMap<String,Set<String>>();menuOperations.forEach((k,v)->safe.put(k,Set.copyOf(v)));
    menuOperations=Collections.unmodifiableMap(safe);
    managementCapabilities=Set.copyOf(managementCapabilities);scope_params=Map.copyOf(scope_params);
  }
  public boolean global(){return scopeMode==ScopeSql.Mode.GLOBAL;}
  public boolean mayManage(){return managementCapabilities.contains("VIEW_MANAGEMENT");}
  public boolean hasAction(String menu,String action) {
    return scopeMode!=ScopeSql.Mode.DENY && menuOperations.getOrDefault(menu,Set.of()).contains(action);
  }
}
