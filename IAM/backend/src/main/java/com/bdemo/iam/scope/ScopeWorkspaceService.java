package com.bdemo.iam.scope;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bproject.iam.context.UserContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A read-only project catalogue; viewing a card never grants or selects business scope. */
@Service
public class ScopeWorkspaceService {
  private final JdbcTemplate db;

  public ScopeWorkspaceService(JdbcTemplate db) { this.db = db; }

  public record Region(String regionId, String name, String status) {}
  public record Project(String projectId, String name, String status, List<Region> regions) {
    public Project { regions = List.copyOf(regions); }
  }
  public record Workspace(UserContext userContext, List<Project> projects, boolean legacy) {
    public Workspace { projects = List.copyOf(projects); }
  }

  public Workspace current() {
    var user = JwtAuthFilter.currentUser();
    if (user == null) throw BizException.unauthorized("请先登录");
    var context = user.getUserContext();
    // Legacy sessions retain their existing navigation contract, without inventing V2 scope.
    if (context == null) return new Workspace(null, List.of(), true);
    String level = context.adminLevel();
    boolean globalManager = "L1_SUPER".equals(level) || "L1_READONLY".equals(level);
    if (level == null || !globalManager && !Set.of("L2_PROJECT", "L3_SUBSYSTEM", "L4_OPERATOR").contains(level))
      throw BizException.forbidden("账号管理层级无效");
    // Ownership comes from the authenticated context, never a query/header parameter.
    if (!globalManager && (context.projectId() == null || context.projectId().isBlank()))
      return new Workspace(context, List.of(), false);

    String projectsSql = "SELECT project_id,project_name,status FROM iam.iam_project_preset";
    var rows = globalManager
        ? db.queryForList(projectsSql + " ORDER BY project_id")
        : db.queryForList(projectsSql + " WHERE project_id=? ORDER BY project_id", context.projectId());
    var projects = new ArrayList<Project>();
    for (var row : rows) {
      String projectId = ScopeContextService.text(row, "project_id");
      if (!globalManager && !Objects.equals(context.projectId(), projectId)) continue;
      var regions = new ArrayList<Region>();
      var regionRows = db.queryForList(
          "SELECT region_id,region_name,status FROM iam.iam_region_preset WHERE project_id=? ORDER BY region_id",
          projectId);
      for (var region : regionRows) {
        String regionId = ScopeContextService.text(region, "region_id");
        boolean visible = globalManager || ("L2_PROJECT".equals(level)
            ? context.allowedScopes().stream().anyMatch(s -> s.projectId().equals(projectId) && s.regionId().equals(regionId))
            : Objects.equals(context.regionId(), regionId));
        if (visible) regions.add(new Region(regionId, ScopeContextService.text(region, "region_name"),
            ScopeContextService.text(region, "status")));
      }
      // Include registered but disabled/unconfigured projects. Their lack of an allowed tuple
      // is not replaced with GLOBAL: clients must use the existing selection endpoint.
      projects.add(new Project(projectId, ScopeContextService.text(row, "project_name"),
          ScopeContextService.text(row, "status"), regions));
    }
    return new Workspace(context, projects, false);
  }
}
