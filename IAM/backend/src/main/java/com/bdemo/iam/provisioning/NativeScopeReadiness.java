package com.bdemo.iam.provisioning;

import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Provisioned native resources are an additional limit, never an IAM authorization grant. */
public final class NativeScopeReadiness {
  private static final ObjectMapper JSON=new ObjectMapper();
  private static final Set<String> CONSUMERS=Set.of("WMS","PSMS","SCS","EHMS","EMCS","TRUST");
  private NativeScopeReadiness() {}
  public static boolean required(UserContext context,String audience) {
    return context!=null&&CONSUMERS.contains(audience.toUpperCase(Locale.ROOT))
        &&!Set.of("L1_SUPER","L1_READONLY").contains(context.adminLevel());
  }
  public static void require(UserContext context,String audience,String snapshot) {
    if(!required(context,audience))return;
    String app=audience.toUpperCase(Locale.ROOT);
    if(context.scopeMode()!=ScopeSql.Mode.SCOPED||context.activeScope().isEmpty())
      throw new IllegalStateException("NATIVE_SCOPE_AUTHORIZATION_DENIED");
    if(snapshot==null||snapshot.isBlank())throw new IllegalStateException("NATIVE_SCOPE_NOT_PROVISIONED");
    try {
      JsonNode root=JSON.readTree(snapshot);
      if(root.path("version").asInt()!=1||!Set.of("READY","PARTIAL").contains(root.path("state").asText())
          ||!root.path("scopes").isArray())throw new IllegalStateException("NATIVE_SCOPE_NOT_READY");
      Set<ScopeTriple> ready=new HashSet<>();
      for(JsonNode item:root.path("scopes")) {
        ScopeTriple scope=new ScopeTriple(item.path("projectId").asText(),item.path("regionId").asText(),item.path("subsystemCode").asText());
        if(!scope.subsystemCode().equals(app))throw new IllegalStateException("NATIVE_SCOPE_SNAPSHOT_INVALID");
        ready.add(scope);
      }
      for(ScopeTriple scope:context.activeScope())
        if(!scope.subsystemCode().equals(app)||!context.allowedScopes().contains(scope)||!ready.contains(scope))
          throw new IllegalStateException("NATIVE_SCOPE_MAPPING_REQUIRED");
    }catch(IllegalStateException e){throw e;}
    catch(Exception invalid){throw new IllegalStateException("NATIVE_SCOPE_SNAPSHOT_INVALID");}
  }
}
