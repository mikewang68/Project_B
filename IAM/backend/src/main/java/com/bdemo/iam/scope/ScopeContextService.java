package com.bdemo.iam.scope;

import com.bproject.iam.context.*;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads current authorization on every request; no authorization snapshot cache. */
@Service
public class ScopeContextService {
  public static final Set<String> READONLY_ACTIONS=Set.of("VIEW","DOWNLOAD","EXPORT","AUDIT_VERIFY");
  private final JdbcTemplate db;
  private final boolean enabled;
  public ScopeContextService(JdbcTemplate db,@Value("${iam.scope-v2.enabled:false}") boolean enabled) {
    this.db=db;this.enabled=enabled;
  }
  public boolean enabled(){return enabled;}
  /** Native provisioning considers only tuples with an effective business action, not management identity. */
  public List<ScopeTriple> nativeScopes(LoginUser user,String app) {
    var account=account(user.getUserId());
    var base=build(user,account,null,"IAM");
    if(!Set.of("L2_PROJECT","L3_SUBSYSTEM","L4_OPERATOR").contains(base.adminLevel()))return List.of();
    var result=new ArrayList<ScopeTriple>();
    for(var scope:base.allowedScopes())if(scope.subsystemCode().equalsIgnoreCase(app)) {
      var one=build(user,account,new Selection(user.getUserId(),"",false,List.of(scope)),app);
      if(one.scopeMode()==ScopeSql.Mode.SCOPED&&!one.menuOperations().isEmpty())result.add(scope);
    }
    return List.copyOf(result);
  }
  public Map<String,Object> account(String id) {
    var rows=db.queryForList("SELECT id,username,display_name,user_code,admin_level,read_only,scope_state,project_id,region_id,subsystem_code,authz_version,status,deleted,org_codes,inherited_from_user FROM iam.iam_user WHERE id=? AND deleted=0",id);
    if(rows.isEmpty())throw BizException.notFound("用户不存在");
    return rows.get(0);
  }
  public void enrich(LoginUser user) {
    if(!enabled)return;
    var account=account(user.getUserId());
    if(!"V2_READY".equals(account.get("scope_state")))return;
    var context=build(user,account,null,"IAM");
    apply(user,context);
  }
  public void forRequest(LoginUser user,HttpServletRequest request) {
    if(!enabled||user==null||user.getUserContext()==null)return;
    forSession(user,Objects.toString(request.getAttribute("iam.sessionId"),""));
  }
  /** IAM and consumer SSO revalidation must use the same durable central-session selection. */
  public void forSession(LoginUser user,String sid) {
    if(!enabled||user==null||user.getUserContext()==null)return;
    var selection=new ScopeSessionSelection(db).load(user.getUserId(),sid);
    apply(user,build(user,account(user.getUserId()),selection,"IAM"));
  }
  private void apply(LoginUser user,UserContext context) {
    user.setUserContext(context);user.setReadOnly(context.readOnly());
    user.setSuperAdmin(context.adminLevel().equals("L1_SUPER")&&!context.readOnly()&&context.scopeMode()!=ScopeSql.Mode.DENY);
    user.setAccessMode(context.readOnly()?"READONLY_AUDITOR":user.isSuperAdmin()?"FULL_ADMIN":"SCOPED_USER");
    var apps=new TreeSet<String>();
    if(context.scopeMode()!=ScopeSql.Mode.DENY) {
      if(context.global())apps.addAll(db.queryForList("SELECT app_code FROM iam.iam_navigation_app WHERE enabled=1 AND valid=1 AND in_nav_scope=1",String.class));
      else context.activeScope().forEach(s->apps.add(s.subsystemCode()));
    }
    user.setScopedApps(apps);
    user.setAdminAppCodes(context.adminLevel().equals("L3_SUBSYSTEM")&&!context.readOnly()?apps:Set.of());
    var permissions=new TreeSet<String>();
    for(var entry:context.menuOperations().entrySet())for(String action:entry.getValue())
      permissions.addAll(db.queryForList("SELECT p.permission_code FROM iam.iam_operation_permission_map m JOIN iam.iam_permission p ON p.id=m.permission_id AND p.status='active' WHERE m.stable_menu_key=? AND m.op_code=? AND m.mapping_status='VERIFIED'",String.class,entry.getKey(),action));
    user.setPermissions(permissions);
  }
  private UserContext build(LoginUser user,Map<String,Object> a,Selection selection,String audience) {
    String level=text(a,"admin_level"),project=nullable(a,"project_id"),region=nullable(a,"region_id"),app=nullable(a,"subsystem_code");
    boolean global=Set.of("L1_SUPER","L1_READONLY").contains(level);
    boolean readOnly=user.isReadOnly()||Boolean.TRUE.equals(a.get("read_only"))||level.equals("L1_READONLY");
    List<ScopeTriple> allowed=new ArrayList<>();
    boolean valid="active".equals(a.get("status"))&&Set.of("L1_SUPER","L1_READONLY","L2_PROJECT","L3_SUBSYSTEM","L4_OPERATOR").contains(level);
    try {
      if(!global) {
        ScopeTriple.identifier(project,64);
        if(!level.equals("L2_PROJECT"))new ScopeTriple(project,region,app);
        if(db.queryForObject("SELECT count(*) FROM iam.iam_project_preset WHERE project_id=? AND status='active'",Integer.class,project)!=1)valid=false;
      }
      if(valid) {
        String where=global?"":" AND g.project_id=?";
        var params=new ArrayList<Object>();if(!global)params.add(project);
        if(!global&&!level.equals("L2_PROJECT")){where+=" AND g.region_id=? AND g.subsystem_code=?";params.add(region);params.add(app);}
        allowed=db.query("SELECT g.project_id,g.region_id,g.subsystem_code FROM iam.iam_project_system_grant g JOIN iam.iam_project_preset p ON p.project_id=g.project_id AND p.status='active' JOIN iam.iam_region_preset r ON r.project_id=g.project_id AND r.region_id=g.region_id AND r.status='active' JOIN iam.iam_navigation_app n ON n.app_code=g.subsystem_code AND n.enabled=1 AND n.valid=1 WHERE "+(global?"TRUE":"g.l1_allowed=TRUE AND g.l2_enabled=TRUE")+where+" ORDER BY 1,2,3",(rs,n)->new ScopeTriple(rs.getString(1),rs.getString(2),rs.getString(3)),params.toArray());
      }
    }catch(IllegalArgumentException invalid){valid=false;}
    List<ScopeTriple> active=allowed;
    ScopeSql.Mode mode=valid?(global?ScopeSql.Mode.GLOBAL:allowed.isEmpty()?ScopeSql.Mode.DENY:ScopeSql.Mode.SCOPED):ScopeSql.Mode.DENY;
    if(selection!=null) {
      if(!valid||selection.global()&&!global||!allowed.containsAll(selection.scopes())){active=List.of();mode=ScopeSql.Mode.DENY;}
      else if(selection.global()&&global){active=allowed;mode=ScopeSql.Mode.GLOBAL;}
      else {active=selection.scopes().stream().filter(allowed::contains).sorted().distinct().toList();mode=active.isEmpty()?ScopeSql.Mode.DENY:ScopeSql.Mode.SCOPED;}
    }
    var menuOps=new TreeMap<String,Set<String>>();
    if(mode!=ScopeSql.Mode.DENY) {
      boolean superLevel=level.equals("L1_SUPER");
      String roleJoin=superLevel?"":" JOIN iam.iam_role_operation_grant rg ON rg.stable_menu_key=mo.stable_menu_key AND rg.op_code=mo.op_code JOIN iam.iam_role ro ON ro.id=rg.role_id AND ro.status='active' AND ro.deleted=0 JOIN iam.iam_user_role ur ON ur.role_id=ro.id AND ur.user_id=?";
      var params=new ArrayList<Object>();if(!superLevel)params.add(user.getUserId());
      boolean projectLevel=level.equals("L2_PROJECT");
      String roleWhere=superLevel?"":" AND "+(global?"ro.role_scope='GLOBAL'":projectLevel
          ?"ro.role_scope='PROJECT' AND ro.project_id=?"
          :"ro.role_scope='REGION_SYSTEM' AND ro.project_id=? AND ro.region_id=? AND ro.subsystem_code=?");
      if(!global&&!superLevel){params.add(project);if(!projectLevel){params.add(region);params.add(app);}}
      var operations=db.queryForList("SELECT DISTINCT mo.stable_menu_key,mo.op_code,n.navigation_app_code,d.allow_readonly FROM iam.iam_menu_operation mo JOIN iam.iam_operation_perm_dict d ON d.op_code=mo.op_code AND d.status='active' JOIN iam.iam_navigation_menu n ON n.stable_menu_key=mo.stable_menu_key AND n.enabled=1 AND n.valid=1 AND n.nav_eligible=1 AND n.feature_status IN ('READY','PARTIAL') JOIN iam.iam_navigation_app na ON na.app_code=n.navigation_app_code AND na.enabled=1 AND na.valid=1"+roleJoin+" WHERE mo.enabled=TRUE"+roleWhere,params.toArray());
      for(var operation:operations) {
        String key=text(operation,"stable_menu_key"),action=text(operation,"op_code"),system=text(operation,"navigation_app_code");
        if(readOnly&&(!READONLY_ACTIONS.contains(action)||!Boolean.TRUE.equals(operation.get("allow_readonly"))))continue;
        if(projectLevel&&(!READONLY_ACTIONS.contains(action)||!Boolean.TRUE.equals(operation.get("allow_readonly"))))continue;
        if(!global && !upperAllows(active,key,action,system))continue;
        menuOps.computeIfAbsent(key,k->new TreeSet<>()).add(action);
      }
    }
    var capabilities=new TreeSet<String>();
    if(valid&&(mode!=ScopeSql.Mode.DENY||level.equals("L2_PROJECT")||global)&&!level.equals("L4_OPERATOR")) {
      capabilities.add("VIEW_MANAGEMENT");
      if(!readOnly) {
        capabilities.add("CREATE_USERS");
        if(level.equals("L1_SUPER"))capabilities.addAll(Set.of("CONFIGURE_PLATFORM","GRANT_PROJECTS"));
        if(level.equals("L2_PROJECT"))capabilities.add("ENABLE_PROJECT_GRANTS");
        if(level.equals("L3_SUBSYSTEM"))capabilities.add("MANAGE_ROLES");
      }
    }
    var filters=ScopeSql.build(mode,active);
    var projectVersions=db.queryForList("SELECT project_id,authz_version,status FROM iam.iam_project_preset ORDER BY project_id");
    String version=digest(a+"|"+allowed+"|"+active+"|"+mode+"|"+readOnly+"|"+menuOps+"|"+capabilities+"|"+projectVersions);
    return new UserContext(2,user.getUserId(),user.getUsername(),text(a,"user_code"),user.getDisplayName(),level,readOnly,audience,project,region,app,mode,allowed,active,user.getRoles(),menuOps.keySet(),menuOps,capabilities,version,filters.my_info(),filters.my_project_zone_info(),filters.my_info_template(),filters.my_project_zone_info_template(),filters.scope_params());
  }
  private boolean upperAllows(List<ScopeTriple> scopes,String menu,String action,String system) {
    // One context-wide SQL filter is consumed by all modules. A menu action may
    // therefore be exposed only when it is allowed for every selected tuple of
    // that system; an OR here would spread one region's action into other regions.
    boolean found=false;
    for(var s:scopes)if(s.subsystemCode().equals(system)) {
      found=true;
      if(db.queryForObject("SELECT count(*) FROM iam.iam_project_menu_grant WHERE project_id=? AND region_id=? AND subsystem_code=? AND stable_menu_key=? AND op_code=? AND l1_allowed=TRUE AND l2_checked=TRUE",Integer.class,s.projectId(),s.regionId(),s.subsystemCode(),menu,action)==0)return false;
    }
    return found;
  }
  public UserContext requireManager(boolean write) {
    var user=JwtAuthFilter.currentUser();
    if(user==null)throw BizException.unauthorized("请先登录");
    var context=user.getUserContext();
    if(context==null||!context.mayManage()||write&&context.readOnly())throw BizException.forbidden("当前账号没有管理权限");
    return context;
  }
  public record Selection(String userId,String sid,boolean global,List<ScopeTriple> scopes) implements java.io.Serializable {
    public Selection {scopes=List.copyOf(scopes);}
  }
  public UserContext select(HttpServletRequest request,List<ScopeTriple> requested,boolean global) {
    var user=JwtAuthFilter.currentUser();var current=user==null?null:user.getUserContext();
    if(current==null)throw BizException.forbidden("当前账号不支持范围选择");
    var values=requested==null?List.<ScopeTriple>of():requested.stream().sorted().distinct().toList();
    boolean l1=Set.of("L1_SUPER","L1_READONLY").contains(current.adminLevel());
    if(global&&!l1||!current.allowedScopes().containsAll(values))throw BizException.forbidden("查看范围超出授权");
    if(Set.of("L3_SUBSYSTEM","L4_OPERATOR").contains(current.adminLevel())&&(global||!values.equals(current.allowedScopes())))throw BizException.forbidden("当前账号的项目、区域、子系统已锁定");
    String sid=Objects.toString(request.getAttribute("iam.sessionId"),"");
    if(sid.isEmpty())throw BizException.unauthorized("会话标识缺失");
    new ScopeSessionSelection(db).save(new Selection(user.getUserId(),sid,global,values));
    forRequest(user,request);return user.getUserContext();
  }
  public UserContext forAudience(LoginUser user,String app) {
    var context=user.getUserContext();if(context==null)return null;
    if(ScopeManagementAccess.allowed(context,app))
      return build(user,account(user.getUserId()),context.global()?null:
          new Selection(user.getUserId(),"",false,context.activeScope()),"IAM");
    if(!context.global()&&context.activeScope().stream().noneMatch(s->s.subsystemCode().equalsIgnoreCase(app)))throw BizException.forbidden("目标系统未获范围授权");
    return build(user,account(user.getUserId()),context.global()?null:new Selection(user.getUserId(),"",false,context.activeScope().stream().filter(s->s.subsystemCode().equalsIgnoreCase(app)).toList()),app.toUpperCase(Locale.ROOT));
  }
  public static String text(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
  public static String nullable(Map<String,Object> row,String key){return row.get(key)==null?null:row.get(key).toString();}
  private static String digest(String value) {
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    catch(Exception impossible){throw new IllegalStateException(impossible);}
  }
}
