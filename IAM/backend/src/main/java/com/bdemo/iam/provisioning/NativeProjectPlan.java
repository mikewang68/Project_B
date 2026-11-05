package com.bdemo.iam.provisioning;

import com.bproject.iam.context.ScopeTriple;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Explicit native facts for project readers; no fallback warehouse, organization or work area. */
final class NativeProjectPlan {
  record Mapping(ScopeTriple scope,JsonNode policy) {}
  record Missing(ScopeTriple scope,String reason) {}
  record Plan(List<Mapping> mappings,List<Missing> missing,JsonNode merged,String anchor) {}
  static Plan create(List<ScopeTriple> wanted,JsonNode policies,String app)throws Exception {
    var mappings=new ArrayList<Mapping>();var missing=new ArrayList<Missing>();
    for(var scope:wanted.stream().sorted().distinct().toList()) {
      NativeOnboardingWorker.require(scope.subsystemCode().equals(app),"MIXED_NATIVE_AUDIENCE");
      JsonNode found=null;
      for(var p:policies)if(scope.projectId().equals(p.path("projectId").asText())
          &&scope.regionId().equals(p.path("regionId").asText())&&app.equals(p.path("subsystemCode").asText())) {
        NativeOnboardingWorker.require(found==null,"DUPLICATE_NATIVE_SCOPE_POLICY");found=p;
      }
      if(found==null)missing.add(new Missing(scope,"NATIVE_SCOPE_MAPPING_NOT_CONFIGURED"));
      else mappings.add(new Mapping(scope,found));
    }
    if(mappings.isEmpty())return new Plan(List.of(),List.copyOf(missing),null,"");
    var first=mappings.get(0).policy();ObjectNode merged=first.deepCopy();
    List<String> fixed=switch(app) {
      case "WMS" -> List.of("companyCode","roleCode");
      case "TRUST" -> List.of("orgCode");
      case "EMCS" -> List.of("registry","roleKey");
      default -> List.of("registry");
    };
    ObjectNode anchor=NativeOnboardingWorker.JSON.createObjectNode().put("module",app);
    for(String key:fixed) {
      String value=first.path(key).asText();NativeOnboardingWorker.require(!value.isBlank(),"EXPLICIT_NATIVE_MAPPING_REQUIRED_"+key);
      for(var m:mappings)NativeOnboardingWorker.require(value.equals(m.policy().path(key).asText()),"NATIVE_BOUNDARY_COMBINATION_UNSUPPORTED_"+key);
      anchor.put(key,value);
    }
    for(String array:List.of("permissions","routes","workAreas")) {
      var union=NativeOnboardingWorker.JSON.createArrayNode();Set<JsonNode> seen=new LinkedHashSet<>();
      for(var m:mappings)for(var v:m.policy().path(array))if(seen.add(v))union.add(v);
      if(!union.isEmpty())merged.set(array,union);
    }
    return new Plan(List.copyOf(mappings),List.copyOf(missing),merged,NativeOnboardingWorker.policyHash(anchor));
  }
  static ObjectNode snapshot(Collection<ScopeTriple> scopes,boolean complete) {
    var root=NativeOnboardingWorker.JSON.createObjectNode().put("version",1).put("state",complete?"READY":"PARTIAL");
    var values=root.putArray("scopes");for(var s:scopes.stream().distinct().sorted().toList())values.addObject()
        .put("projectId",s.projectId()).put("regionId",s.regionId()).put("subsystemCode",s.subsystemCode());
    return root;
  }
}
