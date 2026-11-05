package com.bdemo.iam.provisioning;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeOnboardingWorkerTest {
  @TempDir Path directory;
  final ObjectMapper json=new ObjectMapper();
  final String subject="0123456789abcdef0123456789abcdef";
  NativeOnboardingWorker.Person person(String project,String region,String module,String level,String alias,String company,String org){
    return new NativeOnboardingWorker.Person(subject,"new-person",level,false,project,region,module,alias,company,org);
  }
  JsonNode policy(String module)throws Exception{return json.readTree("{\"projectId\":\"PROJECT_TEST\",\"regionId\":\"REGION_TEST\",\"subsystemCode\":\""+module+"\",\"companyCode\":\"company_test\",\"orgCode\":\"org_test\"}");}
  @Test void onlyExplicitFixedScopePeopleAreEligible()throws Exception {
    assertTrue(NativeOnboardingWorker.eligible(person("PROJECT_TEST","REGION_TEST","WMS","L4_OPERATOR",subject,"",""),policy("WMS")));
    assertFalse(NativeOnboardingWorker.eligible(person("OTHER","REGION_TEST","WMS","L4_OPERATOR",subject,"",""),policy("WMS")));
    assertFalse(NativeOnboardingWorker.eligible(person("PROJECT_TEST","OTHER","WMS","L4_OPERATOR",subject,"",""),policy("WMS")));
    assertFalse(NativeOnboardingWorker.eligible(person("PROJECT_TEST","REGION_TEST","TRUST","L4_OPERATOR",subject,"",""),policy("WMS")));
  }
  @Test void globalAndProjectAdministratorsAreExcludedFromFixedScopeProvisioning()throws Exception {
    for(String level:new String[]{"L1_SUPER","L1_READONLY","L2_PROJECT"})assertFalse(NativeOnboardingWorker.eligible(person("PROJECT_TEST","REGION_TEST","WMS",level,subject,"",""),policy("WMS")));
  }
  @Test void existingAliasesAndClaimsCannotBeReassigned()throws Exception {
    assertFalse(NativeOnboardingWorker.eligible(person("PROJECT_TEST","REGION_TEST","WMS","L4_OPERATOR","old-operator","",""),policy("WMS")));
    assertFalse(NativeOnboardingWorker.eligible(person("PROJECT_TEST","REGION_TEST","WMS","L4_OPERATOR",subject,"other_company",""),policy("WMS")));
    assertFalse(NativeOnboardingWorker.eligible(person("PROJECT_TEST","REGION_TEST","TRUST","L4_OPERATOR",subject,"","other_org"),policy("TRUST")));
    assertFalse(NativeOnboardingWorker.eligible(person("PROJECT_TEST","REGION_TEST","SCS","L4_OPERATOR",subject,"company_test",""),policy("SCS")));
  }
  @Test void emcsAliasIsDeterministicBoundedAndDoesNotAffectOtherSystems(){
    String alias=NativeOnboardingWorker.alias("EMCS",subject);assertEquals("lw_0123456789abcdef01234567",alias);assertTrue(alias.length()<=30);
    assertEquals(subject,NativeOnboardingWorker.alias("WMS",subject));
    assertThrows(IllegalStateException.class,()->NativeOnboardingWorker.alias("EMCS","name' OR 1=1"));
  }
  @Test void policyHashChangesWithNativeAssignment()throws Exception {
    var a=policy("WMS");var b=a.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)b).put("companyCode","other_company");
    assertNotEquals(NativeOnboardingWorker.policyHash(a),NativeOnboardingWorker.policyHash(b));
  }
  @Test void nativeDisplayLimitsPreserveWholeUnicodeCharacters(){
    assertEquals("新人🙂",NativeOnboardingWorker.label("新人🙂员",3));
    assertEquals("person",NativeOnboardingWorker.label("person",30));
  }
  @Test void publishedRegistryIsWholeAndPreservesExistingEntries()throws Exception {
    var file=directory.resolve("registry.json");var data=json.createObjectNode();data.putObject("existing").put("enabled",true);
    NativeOnboardingWorker.atomic(file,data);var previous=Files.getPosixFilePermissions(file);
    data.putObject(subject).put("enabled",true);NativeOnboardingWorker.atomic(file,data);
    assertEquals(data,json.readTree(Files.readString(file)));assertEquals(previous,Files.getPosixFilePermissions(file));
    try(var files=Files.list(directory)){assertEquals(1,files.count());}
  }
  @Test void symbolicLinkRegistryCannotRedirectWrites()throws Exception {
    var target=directory.resolve("private.json");Files.writeString(target,"original");var link=directory.resolve("registry.json");Files.createSymbolicLink(link,target);
    assertThrows(IllegalStateException.class,()->NativeOnboardingWorker.atomic(link,json.createObjectNode()));assertEquals("original",Files.readString(target));
  }
}
