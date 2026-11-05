package com.bdemo.iam.scope;

import com.bproject.iam.context.*;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ScopeSqlTest {
  @Test void matchesTeachersExactSingleScopeContract() {
    var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(new ScopeTriple("Project_B","SHEN_TIE","WMS")));
    assertEquals(" AND project_id = 'Project_B' AND region_id = 'SHEN_TIE' AND subsystem_code = 'WMS' ",f.my_info());
    assertEquals(" AND project_id = 'Project_B' AND region_id = 'SHEN_TIE' ",f.my_project_zone_info());
    assertEquals(" AND project_id = :p0 AND region_id = :r0 AND subsystem_code = :s0 ",f.my_info_template());
    assertEquals(Map.of("p0","Project_B","r0","SHEN_TIE","s0","WMS"),f.scope_params());
  }
  @Test void preservesTuplePairingAndDeduplicatesSharedProjection() {
    var one=new ScopeTriple("P1","R1","WMS");var two=new ScopeTriple("P2","R2","SYS");
    var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(two,one,one,new ScopeTriple("P1","R1","SYS")));
    assertTrue(f.my_info().startsWith(" AND (("));
    assertFalse(f.my_info().contains(" IN "));
    assertEquals(9,f.scope_params().size());
    assertEquals(" AND ((project_id = 'P1' AND region_id = 'R1') OR (project_id = 'P2' AND region_id = 'R2')) ",f.my_project_zone_info());
    assertThrows(UnsupportedOperationException.class,()->f.scope_params().put("p0","evil"));
  }
  @Test void deniesEmptyScopeAndRejectsUnsafeIdentifiers() {
    assertEquals(" AND 1=0 ",ScopeSql.build(ScopeSql.Mode.SCOPED,List.of()).my_info());
    assertEquals(" AND 1=0 ",ScopeSql.build(ScopeSql.Mode.DENY,List.of()).my_project_zone_info());
    assertEquals(" AND 1=1 ",ScopeSql.build(ScopeSql.Mode.GLOBAL,List.of()).my_info());
    for(String value:Arrays.asList(null,"","A' OR 1=1 --","A B","A.B","A\\B","项目","A\n"))
      assertThrows(IllegalArgumentException.class,()->new ScopeTriple(value,"R","WMS"));
    assertThrows(IllegalArgumentException.class,()->new ScopeTriple("x".repeat(65),"R","WMS"));
  }
  @Test void aliasIsAnEnumChosenByServerCode() {
    var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(new ScopeTriple("P","R","WMS")),ScopeSql.Columns.ORDER);
    assertEquals(" AND o.project_id = 'P' AND o.region_id = 'R' AND o.subsystem_code = 'WMS' ",f.my_info());
    assertThrows(IllegalArgumentException.class,()->ScopeSql.Columns.valueOf("o;DROP TABLE"));
  }
}
