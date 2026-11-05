package com.bdemo.iam.scope;

import static org.junit.jupiter.api.Assertions.*;
import com.bdemo.iam.common.BizException;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScopeOrganizationInheritanceTest {
  Map<String,Object> administrator(){return new HashMap<>(Map.of("id","manager-a","admin_level","L3_SUBSYSTEM","project_id","PROJECT_TEST","org_codes","","inherited_from_user","project-manager-a"));}
  Map<String,Object> parent(){return new HashMap<>(Map.of("id","project-manager-a","admin_level","L2_PROJECT","project_id","PROJECT_TEST","status","active","scope_state","V2_READY","org_codes","zone-test,company-test"));}
  @Test void emptyLegacyOrganizationUsesExplicitSameProjectParent(){
    var original=administrator();assertEquals(List.of("zone-test","company-test"),ScopeProvisioning.organizationForCreation(original,parent()));assertEquals("",original.get("org_codes"));
  }
  @Test void existingOrganizationIsPreservedWithoutConsultingAParent(){
    var original=administrator();original.put("org_codes","zone-other,company-other");assertEquals(List.of("zone-other","company-other"),ScopeProvisioning.organizationForCreation(original,null));
  }
  @Test void unrelatedParentCannotSupplyOrganization(){
    var foreign=parent();foreign.put("project_id","OTHER_PROJECT");assertThrows(BizException.class,()->ScopeProvisioning.organizationForCreation(administrator(),foreign));
    var unrelated=parent();unrelated.put("id","unrelated-manager");assertThrows(BizException.class,()->ScopeProvisioning.organizationForCreation(administrator(),unrelated));
  }
  @Test void inactiveOrLegacyParentIsRejected(){
    var inactive=parent();inactive.put("status","disabled");assertThrows(BizException.class,()->ScopeProvisioning.organizationForCreation(administrator(),inactive));
    var legacy=parent();legacy.put("scope_state","LEGACY");assertThrows(BizException.class,()->ScopeProvisioning.organizationForCreation(administrator(),legacy));
  }
  @Test void missingParentOrItsOrganizationIsNotInvented(){
    assertThrows(BizException.class,()->ScopeProvisioning.organizationForCreation(administrator(),null));
    var empty=parent();empty.put("org_codes","");assertThrows(BizException.class,()->ScopeProvisioning.organizationForCreation(administrator(),empty));
  }
  @Test void anotherAdministratorLevelCannotServeAsProjectParent(){
    var invalid=parent();invalid.put("admin_level","L1_SUPER");assertThrows(BizException.class,()->ScopeProvisioning.organizationForCreation(administrator(),invalid));
  }
}
