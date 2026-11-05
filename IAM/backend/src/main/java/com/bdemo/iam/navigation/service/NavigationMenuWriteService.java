package com.bdemo.iam.navigation.service;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.JwtAuthFilter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NavigationMenuWriteService {
  private final JdbcTemplate db;
  public NavigationMenuWriteService(JdbcTemplate db) { this.db = db; }

  public record Create(String appCode, String stableMenuKey, String parentStableMenuKey,
      String menuKind, String menuName, String routePath, String entryPath) {}

  private void administrator() {
    var user = JwtAuthFilter.currentUser();
    if (user == null || !user.isSuperAdmin() || user.isReadOnly())
      throw BizException.forbidden("仅全局管理员可维护菜单结构");
  }

  private Map<String,Object> menu(String key) {
    var rows = db.queryForList("SELECT stable_menu_key,navigation_app_code,parent_stable_menu_key,"
        + "normalized_level,menu_kind FROM iam.iam_navigation_menu WHERE stable_menu_key=? AND valid=1", key);
    if (rows.isEmpty()) throw BizException.notFound("菜单或父节点不存在");
    return rows.get(0);
  }

  public static void path(String value) {
    if (value == null || value.isEmpty()) return;
    if (value.length() > 255 || !value.startsWith("/") || value.startsWith("//")
        || value.chars().anyMatch(c -> c <= 32 || c >= 127 || "\\%?#".indexOf(c) >= 0))
      throw BizException.badRequest("菜单路径必须为站内绝对路径");
    for (String segment : value.split("/"))
      if (segment.equals(".") || segment.equals("..")) throw BizException.badRequest("菜单路径不能跳转目录");
  }

  public void validateEdit(Map<String,Object> prior,String name,String entry,String route,
      Integer visible,Integer enabled,Integer navigable,String feature) {
    administrator();
    if(name!=null&&(name.isBlank()||name.length()>128))throw BizException.badRequest("菜单名无效");
    for(Integer value:Arrays.asList(visible,enabled,navigable))
      if(value!=null&&value!=0&&value!=1)throw BizException.badRequest("菜单开关只允许0或1");
    if(feature!=null&&!Set.of("READY","PARTIAL","PENDING_VERIFICATION","PLANNED").contains(feature))
      throw BizException.badRequest("菜单发布状态无效");
    if(Integer.valueOf(1).equals(navigable)&&!"PAGE".equals(prior.get("menu_kind")))
      throw BizException.badRequest("只有页面可以直接导航");
    // Existing registered SPA entries can contain a query string. Preserve an
    // unchanged legacy entry during an unrelated edit; new/changed paths use
    // the same restricted path rules as creation.
    if(route!=null&&!Objects.equals(route,prior.get("route_path")))path(route);
    if(entry!=null&&!Objects.equals(entry,prior.get("entry_path"))) {
      path(entry);
      if("PAGE".equals(prior.get("menu_kind"))) {
        String base=db.queryForObject("SELECT entry_base_path FROM iam.iam_navigation_app WHERE app_code=?",
            String.class,prior.get("navigation_app_code"));
        if(entry.isBlank()||base!=null&&!base.isBlank()&&!base.equals("/")
            &&!entry.equals(base)&&!entry.startsWith(base.endsWith("/")?base:base+"/"))
          throw BizException.badRequest("入口路径不属于当前系统");
      }
    }
    if("PAGE".equals(prior.get("menu_kind"))&&route!=null&&route.isBlank())
      throw BizException.badRequest("页面需要路由路径");
  }

  public void create(Create input) {
    administrator();
    if (input.appCode() == null || input.stableMenuKey() == null
        || input.stableMenuKey().length() > 128
        || !input.stableMenuKey().startsWith(input.appCode() + ":")
        || input.stableMenuKey().chars().anyMatch(c -> !(c >= 'A' && c <= 'Z'
            || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || "_:-.".indexOf(c) >= 0))
        || !Set.of("DIRECTORY", "PAGE").contains(Objects.toString(input.menuKind(), ""))
        || input.menuName() == null || input.menuName().isBlank() || input.menuName().length() > 128)
      throw BizException.badRequest("菜单名称、类型或稳定键无效");
    var apps = db.queryForList("SELECT app_code,entry_base_path FROM iam.iam_navigation_app"
        + " WHERE app_code=? AND valid=1 FOR UPDATE", input.appCode());
    if (apps.isEmpty()) throw BizException.notFound("所属系统未登记");
    if (db.queryForObject("SELECT count(*) FROM iam.iam_navigation_menu WHERE stable_menu_key=?",
        Integer.class, input.stableMenuKey()) != 0) throw BizException.conflict("稳定菜单键已存在");
    var parent = menu(input.parentStableMenuKey());
    checkParent(input.appCode(), parent);
    int level = ((Number) parent.get("normalized_level")).intValue() + 1;
    if (level > 6) throw BizException.badRequest("菜单最多六级");
    path(input.routePath()); path(input.entryPath());
    if (input.menuKind().equals("PAGE")
        && (input.routePath() == null || input.routePath().isBlank()
            || input.entryPath() == null || input.entryPath().isBlank()))
      throw BizException.badRequest("页面需要路由及入口路径");
    String base = Objects.toString(apps.get(0).get("entry_base_path"), "");
    if (input.menuKind().equals("PAGE") && !base.isBlank() && !base.equals("/")
        && !input.entryPath().equals(base) && !input.entryPath().startsWith(base.endsWith("/") ? base : base + "/"))
      throw BizException.badRequest("入口路径不属于当前系统");
    int order = nextOrder(input.appCode(), input.parentStableMenuKey());
    db.update("INSERT INTO iam.iam_navigation_menu(stable_menu_key,navigation_app_code,source_app_code,"
        + "source_menu_id,parent_stable_menu_key,hierarchy_code,normalized_level,sibling_sort_order,"
        + "menu_name,menu_kind,entry_mode,route_path,entry_path,enabled,nav_eligible,feature_status,"
        + "source_reference,content_sha256,last_batch_id,last_operation_source,updated_by)"
        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,'PENDING_VERIFICATION',?,?,'V21_ADMIN_UI','ADMIN_UI',?)",
        input.stableMenuKey(), input.appCode(), input.appCode(), input.stableMenuKey(),
        input.parentStableMenuKey(), "UI-" + UUID.randomUUID().toString().replace("-", ""),
        level, order, input.menuName().trim(), input.menuKind(),
        input.menuKind().equals("PAGE") ? "SPA_ROUTE" : "NONE",
        Objects.toString(input.routePath(), ""), Objects.toString(input.entryPath(), ""),
        "L1 menu management", "0".repeat(64), JwtAuthFilter.currentUser().getUserId());
  }

  private int nextOrder(String app, String parent) {
    return db.queryForObject("SELECT COALESCE(max(sibling_sort_order),0)+1 FROM iam.iam_navigation_menu"
        + " WHERE navigation_app_code=? AND parent_stable_menu_key=? AND valid=1", Integer.class, app, parent);
  }

  private void checkParent(String app, Map<String,Object> parent) {
    if (!app.equals(parent.get("navigation_app_code"))
        || !Set.of("APP", "DIRECTORY").contains(parent.get("menu_kind")))
      throw BizException.badRequest("父节点必须是同系统的根节点或目录");
  }

  public void move(String key, String parentKey) {
    if (parentKey == null) return;
    administrator();
    var current = menu(key);
    if (Objects.equals(parentKey, current.get("parent_stable_menu_key"))) return;
    if ("APP".equals(current.get("menu_kind"))) throw BizException.badRequest("系统根节点不可移动");
    String app = current.get("navigation_app_code").toString();
    db.queryForList("SELECT app_code FROM iam.iam_navigation_app WHERE app_code=? FOR UPDATE", app);
    var parent = menu(parentKey);
    checkParent(app, parent);
    String ancestor = parentKey;
    for (int count = 0; ancestor != null && !ancestor.isBlank(); count++) {
      if (ancestor.equals(key) || count > 6) throw BizException.badRequest("菜单层级形成环");
      ancestor = (String) menu(ancestor).get("parent_stable_menu_key");
    }
    int nextLevel = ((Number) parent.get("normalized_level")).intValue() + 1;
    int oldLevel = ((Number) current.get("normalized_level")).intValue();
    var descendants = db.queryForList("WITH RECURSIVE children AS ("
        + "SELECT stable_menu_key,normalized_level FROM iam.iam_navigation_menu WHERE parent_stable_menu_key=?"
        + " UNION ALL SELECT m.stable_menu_key,m.normalized_level FROM iam.iam_navigation_menu m"
        + " JOIN children c ON m.parent_stable_menu_key=c.stable_menu_key)"
        + " SELECT * FROM children ORDER BY normalized_level", key);
    int delta = nextLevel - oldLevel;
    if (nextLevel > 6 || descendants.stream().anyMatch(r -> ((Number) r.get("normalized_level")).intValue() + delta > 6))
      throw BizException.badRequest("移动后菜单超过六级");
    db.update("UPDATE iam.iam_navigation_menu SET parent_stable_menu_key=?,normalized_level=?,"
        + "sibling_sort_order=? WHERE stable_menu_key=?", parentKey, nextLevel, nextOrder(app, parentKey), key);
    for (var child : descendants) db.update("UPDATE iam.iam_navigation_menu SET normalized_level=?,"
        + "row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE stable_menu_key=?",
        ((Number) child.get("normalized_level")).intValue() + delta, child.get("stable_menu_key"));
  }
}
