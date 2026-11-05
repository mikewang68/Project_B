package com.bdemo.iam.provisioning;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bproject.iam.context.ScopeTriple;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class NativeProjectFailureTest {
  @TempDir Path directory;
  final String id="0123456789abcdef0123456789abcdef";
  final ScopeTriple scope=new ScopeTriple("P","R1","WMS");
  NativeOnboardingWorker worker()throws Exception {
    var c=NativeOnboardingWorker.JSON.createObjectNode().put("stateDirectory",directory.toString()).put("createdAfter","2020-01-01 00:00:00");
    c.putObject("databases").putObject("IAM");
    c.putArray("projectReaderScopes").addObject().put("projectId","P").put("regionId","R1").put("subsystemCode","WMS").put("companyCode","known").put("roleCode","reader");
    return spy(new NativeOnboardingWorker(c));
  }
  NativeOnboardingWorker.ProjectPerson person(){return new NativeOnboardingWorker.ProjectPerson(new NativeOnboardingWorker.Person(id,"test","L2_PROJECT",true,"P","","WMS",id,"",""),List.of(scope));}
  @Test void incompatibleChangedMappingInvalidatesOnlyThisAudienceProof()throws Exception {
    var w=worker();var p=person();
    var old=NativeOnboardingWorker.JSON.createObjectNode().put("subject",id).put("mode","L2_PROJECT").put("status","COMPLETE").put("anchorSha256","previous-known-boundary");
    NativeOnboardingWorker.atomic(w.journal(p.person()),old);
    doNothing().when(w).stamp(p.person(),List.of(),false);
    assertEquals("OWNED_NATIVE_BOUNDARY_CHANGED",assertThrows(IllegalStateException.class,()->w.reconcileProject(p)).getMessage());
    verify(w).stamp(p.person(),List.of(),false);verify(w,never()).stamp(any(),anyList(),eq(true));
  }
  @Test void failedNativeWriteCannotLeavePreviouslyReadySnapshotUsable()throws Exception {
    var w=worker();var p=person();
    doReturn(NativeOnboardingWorker.JSON.createObjectNode().put("subject",id)).when(w).intent(any(),any(),anyString());
    doThrow(new java.sql.SQLException("private detail","23503")).when(w).nativeDatabase(any(),any(),any());
    doNothing().when(w).stamp(p.person(),List.of(),false);
    assertThrows(java.sql.SQLException.class,()->w.reconcileProject(p));verify(w).stamp(p.person(),List.of(),false);
    var empty=NativeProjectPlan.snapshot(List.of(),false);assertEquals(0,empty.path("scopes").size());
  }
}
