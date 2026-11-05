package com.bdemo.iam.scope;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.LoginUser;
import com.bproject.iam.context.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ScopeWorkspaceServiceTest {
  private final JdbcTemplate db = mock(JdbcTemplate.class);
  private final ScopeWorkspaceService service = new ScopeWorkspaceService(db);
  private static final String PROJECTS = "SELECT project_id,project_name,status FROM iam.iam_project_preset";
  private static final String REGIONS = "SELECT region_id,region_name,status FROM iam.iam_region_preset WHERE project_id=? ORDER BY region_id";

  @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
  private Map<String,Object> project(String id, String status) {
    return Map.of("project_id", id, "project_name", "Project " + id, "status", status);
  }
  private Map<String,Object> region(String id, String status) {
    return Map.of("region_id", id, "region_name", "Region " + id, "status", status);
  }
  private UserContext context(String level, String project, String region, ScopeSql.Mode mode, List<ScopeTriple> scopes) {
    var filter = ScopeSql.build(mode, scopes);
    return new UserContext(2, "user", "user", "U", "User", level, "L1_READONLY".equals(level),
        "IAM", project, region, region == null ? null : "WMS", mode, scopes, scopes, Set.of(), Set.of(),
        Map.of(), "L4_OPERATOR".equals(level) ? Set.of() : Set.of("VIEW_MANAGEMENT"), "version",
        filter.my_info(), filter.my_project_zone_info(), filter.my_info_template(),
        filter.my_project_zone_info_template(), filter.scope_params());
  }
  private LoginUser login(UserContext context) {
    var user = new LoginUser("user", "user", "User", false, Set.of());
    user.setUserContext(context);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    return user;
  }
  @Test void anonymousIsDeniedAndLegacySessionGetsOnlyAnExplicitCompatibilityMarker() {
    assertEquals(401, assertThrows(BizException.class, service::current).getHttpStatus());
    login(null);
    var legacy = service.current();
    assertTrue(legacy.legacy());
    assertNull(legacy.userContext());
    assertTrue(legacy.projects().isEmpty());
    verifyNoInteractions(db);
  }
  @Test void bothL1ModesSeeOnlyRegisteredProjectsIncludingDisabledAndEmptyProjects() {
    when(db.queryForList(PROJECTS + " ORDER BY project_id"))
        .thenReturn(List.of(project("P", "active"), project("Q", "inactive")));
    when(db.queryForList(REGIONS, "P")).thenReturn(List.of(region("R1", "active"), region("R2", "inactive")));
    for (String level : List.of("L1_SUPER", "L1_READONLY")) {
      var c = context(level, null, null, ScopeSql.Mode.DENY, List.of());
      var user = login(c);
      var result = service.current();
      assertFalse(result.legacy());
      assertSame(c, result.userContext());
      assertSame(c, user.getUserContext(), "Listing projects must not select or widen scope");
      assertEquals(List.of("P", "Q"), result.projects().stream().map(ScopeWorkspaceService.Project::projectId).toList());
      assertEquals("inactive", result.projects().get(1).status());
      assertTrue(result.projects().get(1).regions().isEmpty());
      assertEquals(List.of("R1", "R2"), result.projects().get(0).regions().stream().map(ScopeWorkspaceService.Region::regionId).toList());
      assertEquals(ScopeSql.Mode.DENY, result.userContext().scopeMode());
    }
  }
  @Test void l2SeesOnlyItsProjectAndApprovedRegionsWithoutDuplicatingSystems() {
    when(db.queryForList(PROJECTS + " WHERE project_id=? ORDER BY project_id", "P"))
        .thenReturn(List.of(project("P", "active")));
    when(db.queryForList(REGIONS, "P")).thenReturn(List.of(region("R1", "active"), region("R2", "active")));
    var scopes = List.of(new ScopeTriple("P", "R1", "WMS"), new ScopeTriple("P", "R1", "V21NEW"));
    login(context("L2_PROJECT", "P", null, ScopeSql.Mode.SCOPED, scopes));
    var result = service.current();
    assertEquals(1, result.projects().size());
    assertEquals(List.of("R1"), result.projects().get(0).regions().stream().map(ScopeWorkspaceService.Region::regionId).toList());
    verify(db, never()).queryForList(PROJECTS + " ORDER BY project_id");
    verify(db, never()).queryForList(REGIONS, "Q");
  }
  @Test void l2WithoutBusinessGrantsCanSeeItsProjectButNoRegionOrGlobalBusinessScope() {
    when(db.queryForList(PROJECTS + " WHERE project_id=? ORDER BY project_id", "P"))
        .thenReturn(List.of(project("P", "active")));
    when(db.queryForList(REGIONS, "P")).thenReturn(List.of(region("R1", "active")));
    login(context("L2_PROJECT", "P", null, ScopeSql.Mode.DENY, List.of()));
    var result = service.current();
    assertEquals("P", result.projects().get(0).projectId());
    assertTrue(result.projects().get(0).regions().isEmpty());
    assertFalse(result.userContext().global());
    assertEquals(" AND 1=0 ", result.userContext().my_info());
  }
  @Test void l3AndL4SeeOnlyTheirFixedProjectAndRegionEvenWithNoActiveBusinessGrant() {
    when(db.queryForList(PROJECTS + " WHERE project_id=? ORDER BY project_id", "P"))
        .thenReturn(List.of(project("P", "active")));
    when(db.queryForList(REGIONS, "P")).thenReturn(List.of(region("R1", "inactive"), region("R2", "active")));
    for (String level : List.of("L3_SUBSYSTEM", "L4_OPERATOR")) {
      login(context(level, "P", "R1", ScopeSql.Mode.DENY, List.of()));
      var result = service.current();
      assertEquals(1, result.projects().size());
      assertEquals(List.of("R1"), result.projects().get(0).regions().stream().map(ScopeWorkspaceService.Region::regionId).toList());
      assertFalse(result.userContext().global());
    }
    verify(db, never()).queryForList(PROJECTS + " ORDER BY project_id");
  }
  @Test void missingOwnershipAndUnknownLevelFailClosedWithoutCatalogueReads() {
    login(context("L2_PROJECT", null, null, ScopeSql.Mode.DENY, List.of()));
    assertTrue(service.current().projects().isEmpty());
    login(context("UNRECOGNIZED", "P", null, ScopeSql.Mode.DENY, List.of()));
    assertEquals(403, assertThrows(BizException.class, service::current).getHttpStatus());
    verifyNoInteractions(db);
  }
  @Test void unavailableDatabaseDoesNotProduceInventedDemoProjects() {
    login(context("L1_SUPER", null, null, ScopeSql.Mode.GLOBAL, List.of()));
    when(db.queryForList(PROJECTS + " ORDER BY project_id"))
        .thenThrow(new DataAccessResourceFailureException("unavailable"));
    assertThrows(DataAccessResourceFailureException.class, service::current);
  }
  @Test void selectingAnEmptyProjectPersistsDenyAndStillAllowsOnlyL1Management() throws Exception {
    var user = login(context("L1_SUPER", null, null, ScopeSql.Mode.GLOBAL, List.of()));
    var request = new MockHttpServletRequest();
    request.setAttribute("iam.sessionId", "sid");
    var saved = new AtomicReference<String>();
    when(db.update(anyString(), anyString(), eq("sid"), eq("user"))).thenAnswer(call -> {
      saved.set(call.getArgument(1)); return 1;
    });
    when(db.queryForList(startsWith("SELECT scope_selection_json FROM iam.iam_session"), eq("sid"), eq("user")))
        .thenAnswer(call -> List.of(Map.<String,Object>of("scope_selection_json", saved.get())));
    when(db.queryForList(startsWith("SELECT id,username,display_name,user_code,admin_level"), eq("user")))
        .thenReturn(List.of(Map.of("id", "user", "username", "user", "user_code", "U",
            "admin_level", "L1_SUPER", "read_only", false, "scope_state", "V2_READY", "status", "active")));
    var contexts = new ScopeContextService(db, true);
    var selected = contexts.select(request, List.of(), false);
    assertEquals(ScopeSql.Mode.DENY, selected.scopeMode());
    assertFalse(selected.global());
    assertEquals(" AND 1=0 ", selected.my_info());
    assertEquals("L1_SUPER", selected.adminLevel());
    assertTrue(selected.mayManage());
    assertTrue(user.getAllowedAppCodes().isEmpty());
    assertEquals(403, assertThrows(BizException.class, () -> contexts.forAudience(user, "WMS")).getHttpStatus());
    var persisted = new ObjectMapper().readValue(saved.get(), ScopeContextService.Selection.class);
    assertFalse(persisted.global());
    assertTrue(persisted.scopes().isEmpty());
  }
  @Test void inventedProjectCannotBecomeAnAuthorizedSelection() {
    login(context("L2_PROJECT", "P", null, ScopeSql.Mode.SCOPED,
        List.of(new ScopeTriple("P", "R1", "WMS"))));
    var request = new MockHttpServletRequest(); request.setAttribute("iam.sessionId", "sid");
    assertEquals(403, assertThrows(BizException.class, () -> new ScopeContextService(db, true)
        .select(request, List.of(new ScopeTriple("Q", "R1", "WMS")), false)).getHttpStatus());
    verifyNoInteractions(db);
  }
}
