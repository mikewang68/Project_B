package com.bdemo.iam.controller;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.common.R;
import com.bdemo.iam.log.OperationLogger;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.LoginUser;
import com.bdemo.iam.security.RequirePerm;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导航菜单维护 API。仅平台菜单同步权限可写；列表读取复用菜单树查看权限。
 * 维护范围限定为导航展示/发布字段，不改变菜单层级和权限映射。
 */
@RestController
@RequestMapping("/navigation/menus")
public class NavigationMenuAdminController {

  private final JdbcTemplate db;
  private final OperationLogger operationLogger;

  public NavigationMenuAdminController(JdbcTemplate db, OperationLogger operationLogger) {
    this.db = db;
    this.operationLogger = operationLogger;
  }

  @GetMapping
  @RequirePerm("iam:menu:tree:view")
  public R<List<Map<String, Object>>> list(@RequestParam(required = false) String appCode) {
    String where = " WHERE valid = 1";
    Object[] args = new Object[0];
    if (appCode != null && !appCode.isBlank()) {
      where += " AND navigation_app_code = ?";
      args = new Object[] {appCode.trim()};
    }
    return R.ok(db.queryForList(
        "SELECT stable_menu_key AS \"stableMenuKey\", navigation_app_code AS \"appCode\","
            + " menu_name AS \"menuName\", projected_display_title AS \"projectedDisplayTitle\","
            + " menu_kind AS \"menuKind\", entry_path AS \"entryPath\", route_path AS \"routePath\","
            + " visible, enabled, nav_eligible AS \"navEligible\", feature_status AS \"featureStatus\","
            + " unavailable_reason AS \"unavailableReason\", row_version AS \"rowVersion\","
            + " updated_by AS \"updatedBy\", updated_at AS \"updatedAt\""
            + " FROM iam.iam_navigation_menu" + where
            + " ORDER BY navigation_app_code, normalized_level, sibling_sort_order, stable_menu_key",
        args));
  }

  @PutMapping("/{stableMenuKey}")
  @RequirePerm("iam:menu:sync:execute")
  public R<Map<String, Object>> update(@PathVariable String stableMenuKey,
                                       @RequestBody MenuUpdateRequest req,
                                       HttpServletRequest http) {
    List<Map<String, Object>> existing = db.queryForList(
        "SELECT stable_menu_key FROM iam.iam_navigation_menu WHERE stable_menu_key=? AND valid=1",
        stableMenuKey);
    if (existing.isEmpty()) throw BizException.notFound("导航菜单不存在");
    LoginUser me = JwtAuthFilter.currentUser();
    String actor = me == null ? "unknown" : me.getUsername();
    db.update(
        "UPDATE iam.iam_navigation_menu SET"
            + " menu_name=COALESCE(?,menu_name),"
            + " projected_display_title=COALESCE(?,projected_display_title),"
            + " entry_path=COALESCE(?,entry_path),"
            + " route_path=COALESCE(?,route_path),"
            + " visible=COALESCE(?,visible),"
            + " enabled=COALESCE(?,enabled),"
            + " nav_eligible=COALESCE(?,nav_eligible),"
            + " feature_status=COALESCE(?,feature_status),"
            + " unavailable_reason=COALESCE(?,unavailable_reason),"
            + " row_version=row_version+1,"
            + " updated_at=CURRENT_TIMESTAMP,"
            + " last_operation_source='ADMIN_UI',"
            + " updated_by=?"
            + " WHERE stable_menu_key=? AND valid=1",
        req.menuName(), req.projectedDisplayTitle(), req.entryPath(), req.routePath(),
        req.visible(), req.enabled(), req.navEligible(), req.featureStatus(),
        req.unavailableReason(), actor, stableMenuKey);
    operationLogger.record(actor, me == null ? null : me.getUserId(), "operation", "menu", "edit",
        stableMenuKey, "修改导航菜单：" + stableMenuKey, "PUT",
        "/navigation/menus/" + stableMenuKey, http.getRemoteAddr(), "success");
    return R.ok(db.queryForMap(
        "SELECT stable_menu_key AS \"stableMenuKey\", navigation_app_code AS \"appCode\","
            + " menu_name AS \"menuName\", projected_display_title AS \"projectedDisplayTitle\","
            + " menu_kind AS \"menuKind\", entry_path AS \"entryPath\", route_path AS \"routePath\","
            + " visible, enabled, nav_eligible AS \"navEligible\", feature_status AS \"featureStatus\","
            + " unavailable_reason AS \"unavailableReason\", row_version AS \"rowVersion\","
            + " updated_by AS \"updatedBy\", updated_at AS \"updatedAt\""
            + " FROM iam.iam_navigation_menu WHERE stable_menu_key=? AND valid=1",
        stableMenuKey));
  }

  public record MenuUpdateRequest(
      String menuName,
      String projectedDisplayTitle,
      String entryPath,
      String routePath,
      Integer visible,
      Integer enabled,
      Integer navEligible,
      String featureStatus,
      String unavailableReason) {
  }
}
