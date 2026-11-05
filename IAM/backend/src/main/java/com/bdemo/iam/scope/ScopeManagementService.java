package com.bdemo.iam.scope;

import com.bproject.iam.context.*;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.JwtAuthFilter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScopeManagementService {
  private final JdbcTemplate db;private final ScopeContextService contexts;private final ScopeProvisioning provisioning;
  public ScopeManagementService(JdbcTemplate db,ScopeContextService contexts,ScopeProvisioning provisioning){this.db=db;this.contexts=contexts;this.provisioning=provisioning;}
  private static boolean l1(UserContext c){return Set.of("L1_SUPER","L1_READONLY").contains(c.adminLevel());}
  private UserContext configure(){var c=contexts.requireManager(true);if(!c.managementCapabilities().contains("CONFIGURE_PLATFORM"))throw BizException.forbidden("全局配置仅限L1");return c;}
  private List<Map<String,Object>> scoped(String sql,String column,UserContext c) {
    return l1(c)?db.queryForList(sql):db.queryForList(sql+" AND "+column+"=?",c.projectId());
  }
  public Map<String,Object> overview() {
    var c=contexts.requireManager(false);var out=new LinkedHashMap<String,Object>();out.put("userContext",c);
    var managementApps=new HashSet<String>();
    if(c.adminLevel().equals("L2_PROJECT"))managementApps.addAll(db.queryForList("SELECT DISTINCT subsystem_code FROM iam.iam_project_system_grant WHERE project_id=? AND l1_allowed=TRUE",String.class,c.projectId()));
    else c.allowedScopes().forEach(s->managementApps.add(s.subsystemCode()));
    out.put("projects",scoped("SELECT project_id,project_name,company_name,status,authz_version FROM iam.iam_project_preset WHERE TRUE","project_id",c));
    var regions=scoped("SELECT project_id,region_id,region_name,region_type,status FROM iam.iam_region_preset WHERE TRUE","project_id",c);
    if(c.adminLevel().equals("L3_SUBSYSTEM"))regions.removeIf(r->!Objects.equals(c.regionId(),r.get("region_id")));
    out.put("regions",regions);
    var apps=db.queryForList("SELECT app_code,display_name,iam_system_code,entry_base_path,enabled,valid,in_nav_scope,context_contract_max,row_version FROM iam.iam_navigation_app ORDER BY sort_order,app_code");
    if(!l1(c))apps.removeIf(a->!managementApps.contains(a.get("app_code")));
    out.put("applications",apps);
    out.put("operations",db.queryForList("SELECT op_code,op_name,op_category,effect_kind,allow_readonly,status FROM iam.iam_operation_perm_dict ORDER BY op_code"));
    out.put("menus",db.queryForList("SELECT stable_menu_key,navigation_app_code,menu_name,enabled,valid,nav_eligible,feature_status FROM iam.iam_navigation_menu WHERE menu_kind='PAGE' ORDER BY navigation_app_code,stable_menu_key").stream().filter(r->l1(c)||managementApps.contains(r.get("navigation_app_code"))).toList());
    out.put("organizationCodes",l1(c)?db.queryForList("SELECT org_code,org_name,org_type,parent_id FROM iam.iam_org WHERE status='active' ORDER BY sort_order,id"):List.of());
    out.put("systemGrants",scoped("SELECT project_id,region_id,subsystem_code,l1_allowed,l2_enabled FROM iam.iam_project_system_grant WHERE TRUE","project_id",c).stream().filter(r->!c.adminLevel().equals("L3_SUBSYSTEM")||Objects.equals(c.regionId(),r.get("region_id"))&&Objects.equals(c.subsystemCode(),r.get("subsystem_code"))).toList());
    out.put("menuGrants",scoped("SELECT project_id,region_id,subsystem_code,stable_menu_key,op_code,l1_allowed,l2_checked FROM iam.iam_project_menu_grant WHERE TRUE","project_id",c).stream().filter(r->!c.adminLevel().equals("L3_SUBSYSTEM")||Objects.equals(c.regionId(),r.get("region_id"))&&Objects.equals(c.subsystemCode(),r.get("subsystem_code"))).toList());
    out.put("menuOperations",db.queryForList("SELECT n.stable_menu_key,n.navigation_app_code,n.menu_name,n.feature_status,n.nav_eligible,n.enabled,m.op_code,m.enabled AS action_enabled FROM iam.iam_navigation_menu n JOIN iam.iam_menu_operation m ON m.stable_menu_key=n.stable_menu_key ORDER BY n.navigation_app_code,n.stable_menu_key,m.op_code").stream().filter(r->l1(c)||managementApps.contains(r.get("navigation_app_code"))).toList());
    return out;
  }
  public List<Map<String,Object>> users() {
    var c=contexts.requireManager(false);
    var rows=db.queryForList("SELECT id,username,display_name,status,scope_state,admin_level,read_only,project_id,region_id,subsystem_code,inherited_from_user,user_code FROM iam.iam_user WHERE deleted=0 ORDER BY created_at,id");
    rows.removeIf(r->!provisioning.visible("USER",r.get("id").toString(),c));
    for(var row:rows)row.put("roleIds",db.queryForList("SELECT role_id FROM iam.iam_user_role WHERE user_id=? ORDER BY role_id",String.class,row.get("id")));
    return rows;
  }
  public Map<String,Object> availability(String username) {
    var c=contexts.requireManager(true);
    if(!c.managementCapabilities().contains("CREATE_USERS"))throw BizException.forbidden("无创建权限");
    String normalized=username==null?"":username.trim().toLowerCase(Locale.ROOT);
    if(!normalized.matches("^[A-Za-z0-9_.-]{3,64}$"))throw BizException.badRequest("用户名需为3至64位字母、数字、下划线、点或连字符");
    return Map.of("normalized",normalized,"available",db.queryForObject("SELECT count(*) FROM iam.iam_user WHERE lower(trim(username))=? AND deleted=0",Integer.class,normalized)==0);
  }
  public List<Map<String,Object>> roles() {
    var c=contexts.requireManager(false);
    var rows=db.queryForList("SELECT id,role_code,role_name,business_role_code,status,role_scope,project_id,region_id,subsystem_code,authz_version FROM iam.iam_role WHERE deleted=0 ORDER BY created_at,id");
    rows.removeIf(r->!provisioning.visible("ROLE",r.get("id").toString(),c));
    for(var r:rows)r.put("operations",db.queryForList("SELECT stable_menu_key AS \"menuKey\",op_code AS action FROM iam.iam_role_operation_grant WHERE role_id=? ORDER BY 1,2",r.get("id")));
    return rows;
  }
  public List<Map<String,Object>> roleOptions() {
    var c=contexts.requireManager(false);
    if(c.adminLevel().equals("L1_SUPER"))return db.queryForList("SELECT m.stable_menu_key AS \"menuKey\",n.menu_name AS name,m.op_code AS action FROM iam.iam_menu_operation m JOIN iam.iam_navigation_menu n ON n.stable_menu_key=m.stable_menu_key AND n.enabled=1 AND n.valid=1 AND n.nav_eligible=1 AND n.feature_status IN ('READY','PARTIAL') JOIN iam.iam_operation_perm_dict d ON d.op_code=m.op_code AND d.status='active' WHERE m.enabled=TRUE AND d.allow_readonly=TRUE AND m.op_code IN ('VIEW','DOWNLOAD','EXPORT','AUDIT_VERIFY') ORDER BY 1,3");
    if(!c.adminLevel().equals("L3_SUBSYSTEM"))return List.of();
    return db.queryForList("SELECT g.stable_menu_key AS \"menuKey\",n.menu_name AS name,g.op_code AS action FROM iam.iam_project_menu_grant g JOIN iam.iam_menu_operation m ON m.stable_menu_key=g.stable_menu_key AND m.op_code=g.op_code AND m.enabled=TRUE JOIN iam.iam_operation_perm_dict d ON d.op_code=g.op_code AND d.status='active' JOIN iam.iam_navigation_menu n ON n.stable_menu_key=g.stable_menu_key AND n.navigation_app_code=g.subsystem_code AND n.enabled=1 AND n.valid=1 AND n.nav_eligible=1 AND n.feature_status IN ('READY','PARTIAL') WHERE g.project_id=? AND g.region_id=? AND g.subsystem_code=? AND g.l1_allowed=TRUE AND g.l2_checked=TRUE ORDER BY 1,3",c.projectId(),c.regionId(),c.subsystemCode());
  }
  public record Action(String menuKey,String action) {}
  public record RoleInput(String name,String code,String status,List<Action> operations,String projectId) {
    public RoleInput(String name,String code,String status,List<Action> operations) {
      this(name,code,status,operations,null);
    }
  }
  @Transactional
  public String saveRole(String id,RoleInput input) {
    var c=contexts.requireManager(true);
    boolean global=c.adminLevel().equals("L1_SUPER");
    boolean projectRole=global&&input.projectId()!=null&&!input.projectId().isBlank();
    String roleProject=projectRole?input.projectId():c.projectId();
    if(projectRole) {
      code(roleProject,64);
      if(db.queryForObject("SELECT count(*) FROM iam.iam_project_preset WHERE project_id=? AND status='active'",Integer.class,roleProject)!=1)
        throw BizException.badRequest("项目不存在或未启用");
    }
    if(!global&&!c.managementCapabilities().contains("MANAGE_ROLES"))throw BizException.forbidden("岗位必须由对应管理员管理");
    if(input.name()==null||input.name().isBlank()||input.name().length()>64||input.code()==null||!input.code().matches("[A-Za-z0-9_-]{1,64}"))throw BizException.badRequest("岗位名称或编码无效");
    if(!Set.of("active","inactive").contains(Objects.toString(input.status(),"")))throw BizException.badRequest("岗位状态无效");
    var requested=input.operations()==null?List.<Action>of():input.operations().stream().distinct().toList();
    var allowed=roleOptions();
    for(var action:requested)if(allowed.stream().noneMatch(a->Objects.equals(action.menuKey(),a.get("menuKey"))&&Objects.equals(action.action(),a.get("action"))))throw BizException.forbidden("岗位动作超出上级授权");
    String roleId=id==null?UUID.randomUUID().toString().replace("-",""):id;
    if(id==null) {
      db.update("INSERT INTO iam.iam_role(id,role_code,role_name,status,role_scope,project_id,region_id,subsystem_code,business_role_code) VALUES(?,?,?,?,?,?,?,?,?)",roleId,"BR_"+roleId,input.name().trim(),input.status(),projectRole?"PROJECT":global?"GLOBAL":"REGION_SYSTEM",roleProject,c.regionId(),c.subsystemCode(),input.code());
      if(!global)db.update("INSERT INTO iam.iam_role_app_scope(role_id,app_code,access_level) VALUES(?,?,'BUSINESS')",roleId,c.subsystemCode());
      db.update("INSERT INTO iam.iam_management_owner(object_kind,object_id,app_code) VALUES('ROLE',?,?)",roleId,global?"GLOBAL":c.subsystemCode());
    }else{
      provisioning.requireOwned("ROLE",id);
      var existing=db.queryForMap("SELECT role_scope,project_id FROM iam.iam_role WHERE id=?",id);
      if(!Objects.equals(existing.get("role_scope"),projectRole?"PROJECT":global?"GLOBAL":"REGION_SYSTEM")
          ||!Objects.equals(existing.get("project_id"),roleProject))throw BizException.badRequest("岗位范围不可在线改写");
      db.update("UPDATE iam.iam_role SET role_name=?,business_role_code=?,status=?,authz_version=authz_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",input.name().trim(),input.code(),input.status(),id);
      db.update("DELETE FROM iam.iam_role_operation_grant WHERE role_id=?",id);
    }
    for(var action:requested)db.update("INSERT INTO iam.iam_role_operation_grant(role_id,stable_menu_key,op_code,created_by) VALUES(?,?,?,?)",roleId,action.menuKey(),action.action(),c.userId());
    provisioning.syncAllBindings();audit("SAVE_ROLE",roleId,c);return roleId;
  }
  @Transactional
  public void deleteRole(String id) {
    var c=contexts.requireManager(true);provisioning.requireOwned("ROLE",id);
    db.update("UPDATE iam.iam_role SET deleted=1,authz_version=authz_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
    provisioning.syncAllBindings();
    audit("DELETE_ROLE",id,c);
  }
  public record ProjectInput(String projectId,String name,String company,String status,Long expectedVersion) {}
  @Transactional
  public void project(ProjectInput input) {
    var c=configure();code(input.projectId(),64);name(input.name());status(input.status());
    var prior=db.queryForList("SELECT authz_version FROM iam.iam_project_preset WHERE project_id=? FOR UPDATE",input.projectId());
    if(prior.isEmpty()) {
      if(input.expectedVersion()!=null)throw BizException.conflict("项目版本不匹配");
      db.update("INSERT INTO iam.iam_project_preset(project_id,project_name,company_name,status,created_by) VALUES(?,?,?,?,?)",input.projectId(),input.name(),input.company(),input.status(),c.userId());
    }else{
      version(input.expectedVersion(),prior.get(0).get("authz_version"));
      db.update("UPDATE iam.iam_project_preset SET project_name=?,company_name=?,status=?,authz_version=authz_version+1,updated_at=CURRENT_TIMESTAMP WHERE project_id=?",input.name(),input.company(),input.status(),input.projectId());
    }
    audit("SAVE_PROJECT",input.projectId(),c);
  }
  public record RegionInput(String projectId,String regionId,String name,String type,String status,long expectedVersion) {}
  @Transactional
  public void region(RegionInput input) {
    var c=configure();code(input.projectId(),64);code(input.regionId(),64);name(input.name());name(input.type());status(input.status());
    lockProject(input.projectId(),input.expectedVersion());
    var prior=db.queryForList("SELECT region_id FROM iam.iam_region_preset WHERE project_id=? AND region_id=?",input.projectId(),input.regionId());
    if(prior.isEmpty())db.update("INSERT INTO iam.iam_region_preset(project_id,region_id,region_name,region_type,status,created_by) VALUES(?,?,?,?,?,?)",input.projectId(),input.regionId(),input.name(),input.type(),input.status(),c.userId());
    else db.update("UPDATE iam.iam_region_preset SET region_name=?,region_type=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE project_id=? AND region_id=?",input.name(),input.type(),input.status(),input.projectId(),input.regionId());
    bumpProject(input.projectId());audit("SAVE_REGION",input.projectId()+"/"+input.regionId(),c);
  }
  public record SystemGrant(ScopeTriple scope,boolean allowed,boolean enabled,long expectedVersion) {}
  @Transactional
  public void systemGrant(SystemGrant input) {
    var c=contexts.requireManager(true);var s=input.scope();
    if(!c.adminLevel().equals("L1_SUPER")&&(!c.adminLevel().equals("L2_PROJECT")||!Objects.equals(c.projectId(),s.projectId())))throw BizException.forbidden("项目授权未获许可");
    lockProject(s.projectId(),input.expectedVersion());
    var existing=db.queryForList("SELECT l1_allowed,l2_enabled FROM iam.iam_project_system_grant WHERE project_id=? AND region_id=? AND subsystem_code=?",s.projectId(),s.regionId(),s.subsystemCode());
    if(c.adminLevel().equals("L2_PROJECT")) {
      if(existing.isEmpty()||!Boolean.TRUE.equals(existing.get(0).get("l1_allowed"))||!input.allowed())throw BizException.forbidden("L2不能改变L1许可");
      db.update("UPDATE iam.iam_project_system_grant SET l2_enabled=?,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE project_id=? AND region_id=? AND subsystem_code=?",input.enabled(),c.userId(),s.projectId(),s.regionId(),s.subsystemCode());
    }else if(existing.isEmpty())db.update("INSERT INTO iam.iam_project_system_grant(project_id,region_id,subsystem_code,l1_allowed,l2_enabled,updated_by) VALUES(?,?,?,?,FALSE,?)",s.projectId(),s.regionId(),s.subsystemCode(),input.allowed(),c.userId());
    else db.update("UPDATE iam.iam_project_system_grant SET l1_allowed=?,l2_enabled=CASE WHEN ? THEN l2_enabled ELSE FALSE END,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE project_id=? AND region_id=? AND subsystem_code=?",input.allowed(),input.allowed(),c.userId(),s.projectId(),s.regionId(),s.subsystemCode());
    bumpProject(s.projectId());provisioning.syncAllBindings();audit("SYSTEM_GRANT",s.toString(),c);
  }
  public record MenuGrant(ScopeTriple scope,String menuKey,String action,boolean allowed,boolean checked,long expectedVersion) {}
  @Transactional
  public void menuGrant(MenuGrant input) {
    var c=contexts.requireManager(true);var s=input.scope();
    if(!c.adminLevel().equals("L1_SUPER")&&(!c.adminLevel().equals("L2_PROJECT")||!Objects.equals(c.projectId(),s.projectId())))throw BizException.forbidden("菜单授权未获许可");
    lockProject(s.projectId(),input.expectedVersion());
    var prior=db.queryForList("SELECT l1_allowed FROM iam.iam_project_menu_grant WHERE project_id=? AND region_id=? AND subsystem_code=? AND stable_menu_key=? AND op_code=?",s.projectId(),s.regionId(),s.subsystemCode(),input.menuKey(),input.action());
    if(c.adminLevel().equals("L2_PROJECT")) {
      if(prior.isEmpty()||!Boolean.TRUE.equals(prior.get(0).get("l1_allowed"))||!input.allowed())throw BizException.forbidden("上级没有许可该动作");
      db.update("UPDATE iam.iam_project_menu_grant SET l2_checked=?,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE project_id=? AND region_id=? AND subsystem_code=? AND stable_menu_key=? AND op_code=?",input.checked(),c.userId(),s.projectId(),s.regionId(),s.subsystemCode(),input.menuKey(),input.action());
    }else if(prior.isEmpty())db.update("INSERT INTO iam.iam_project_menu_grant(project_id,region_id,subsystem_code,stable_menu_key,op_code,l1_allowed,l2_checked,updated_by) VALUES(?,?,?,?,?,?,FALSE,?)",s.projectId(),s.regionId(),s.subsystemCode(),input.menuKey(),input.action(),input.allowed(),c.userId());
    else db.update("UPDATE iam.iam_project_menu_grant SET l1_allowed=?,l2_checked=CASE WHEN ? THEN l2_checked ELSE FALSE END,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE project_id=? AND region_id=? AND subsystem_code=? AND stable_menu_key=? AND op_code=?",input.allowed(),input.allowed(),c.userId(),s.projectId(),s.regionId(),s.subsystemCode(),input.menuKey(),input.action());
    bumpProject(s.projectId());provisioning.syncAllBindings();audit("MENU_GRANT",input.menuKey()+"/"+input.action(),c);
  }
  public record MenuAction(String menuKey,String action,boolean enabled) {}
  public record OperationInput(String code,String name,String category,String effect,boolean readonly,String status) {}
  @Transactional
  public void operation(OperationInput input) {
    var c=configure();
    if(input.code()==null||!input.code().matches("[A-Z][A-Z0-9_]{0,63}")||input.category()==null||input.category().length()>32)throw BizException.badRequest("动作编码或分类无效");
    name(input.name());status(input.status());
    if(input.name().length()>64||!Set.of("READ","WRITE","EXPORT").contains(Objects.toString(input.effect(),""))
        ||input.readonly()&&(!ScopeContextService.READONLY_ACTIONS.contains(input.code())||input.effect().equals("WRITE")))throw BizException.badRequest("动作语义或只读标志无效");
    var prior=db.queryForList("SELECT op_code FROM iam.iam_operation_perm_dict WHERE op_code=? FOR UPDATE",input.code());
    if(prior.isEmpty())db.update("INSERT INTO iam.iam_operation_perm_dict(op_code,op_name,op_category,effect_kind,allow_readonly,status,created_by) VALUES(?,?,?,?,?,?,?)",input.code(),input.name(),input.category(),input.effect(),input.readonly(),input.status(),c.userId());
    else db.update("UPDATE iam.iam_operation_perm_dict SET op_name=?,op_category=?,effect_kind=?,allow_readonly=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE op_code=?",input.name(),input.category(),input.effect(),input.readonly(),input.status(),input.code());
    audit("SAVE_OPERATION",input.code(),c);
  }
  @Transactional
  public void menuAction(MenuAction input) {
    var c=configure();
    var existing=db.queryForList("SELECT enabled FROM iam.iam_menu_operation WHERE stable_menu_key=? AND op_code=?",input.menuKey(),input.action());
    if(existing.isEmpty())db.update("INSERT INTO iam.iam_menu_operation(stable_menu_key,op_code,enabled) VALUES(?,?,?)",input.menuKey(),input.action(),input.enabled());
    else db.update("UPDATE iam.iam_menu_operation SET enabled=? WHERE stable_menu_key=? AND op_code=?",input.enabled(),input.menuKey(),input.action());
    provisioning.syncAllBindings();audit("MENU_ACTION",input.menuKey()+"/"+input.action(),c);
  }

  public record ApplicationInput(String code,String name,String permissionSystem,String entryBasePath,boolean enabled,int contextContract,Integer expectedVersion) {}
  @Transactional public void application(ApplicationInput input) {
    var c=configure();code(input.code(),32);code(input.permissionSystem(),32);name(input.name());
    if(!input.code().equals(input.code().toUpperCase(Locale.ROOT))||input.code().equals("DTS")
       ||input.contextContract()<1||input.contextContract()>2||input.entryBasePath()==null
       ||!input.entryBasePath().matches("/[A-Za-z0-9/_-]*"))throw BizException.badRequest("系统编码、路径或契约版本无效");
    // Registration must allocate a fresh ordering value. A fixed default collides
    // with the previous newly registered system and also races concurrent creates.
    db.execute("LOCK TABLE iam.iam_navigation_app IN SHARE ROW EXCLUSIVE MODE");
    var prior=db.queryForList("SELECT row_version FROM iam.iam_navigation_app WHERE app_code=? FOR UPDATE",input.code());
    if(prior.isEmpty()) {
      if(input.expectedVersion()!=null)throw BizException.conflict("系统版本不匹配");
      int order=db.queryForObject("SELECT COALESCE(max(sort_order),0)+10 FROM iam.iam_navigation_app",Integer.class);
      db.update("INSERT INTO iam.iam_navigation_app(app_code,iam_system_code,portal_module_key,source_app_code,display_name,nav_group,sort_order,entry_base_path,enabled,valid,in_nav_scope,mapping_status,content_sha256,context_contract_max,last_operation_source,updated_by) VALUES(?,?,?,?,?,'BUSINESS',?,?,?,1,1,'PENDING_CONFIRMATION',?,?,'ADMIN_UI',?)",input.code(),input.permissionSystem(),input.code().toLowerCase(Locale.ROOT),input.code(),input.name(),order,input.entryBasePath(),input.enabled()?1:0,"0".repeat(64),input.contextContract(),c.userId());
      db.update("INSERT INTO iam.iam_navigation_menu(stable_menu_key,navigation_app_code,source_app_code,source_menu_id,hierarchy_code,normalized_level,menu_name,menu_kind,entry_mode,nav_eligible,source_reference,content_sha256,last_batch_id,last_operation_source,updated_by) VALUES(?,?,?,?,?,1,?,'APP','NONE',0,'L1 registration',?,'V21_REGISTRY','ADMIN_UI',?)","APP:"+input.code(),input.code(),input.code(),"APP",input.code(),input.name(),"0".repeat(64),c.userId());
    }else{
      if(input.expectedVersion()==null||input.expectedVersion().intValue()!=((Number)prior.get(0).get("row_version")).intValue())throw BizException.conflict("系统配置已改变，请刷新");
      if(db.queryForObject("SELECT count(*) FROM iam.iam_navigation_app WHERE app_code=? AND iam_system_code<>?",Integer.class,input.code(),input.permissionSystem())>0)throw BizException.badRequest("权限系统前缀须经迁移核定，不能在线重命名");
      db.update("UPDATE iam.iam_navigation_app SET display_name=?,entry_base_path=?,enabled=?,context_contract_max=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP,updated_by=?,last_operation_source='ADMIN_UI' WHERE app_code=?",input.name(),input.entryBasePath(),input.enabled()?1:0,input.contextContract(),c.userId(),input.code());
    }
    provisioning.syncAllBindings();audit("APPLICATION",input.code(),c);
  }
  public record PermissionMapping(String menuKey,String action,String permissionId,String status,String evidence) {}
  public record ResourceInput(String key,String owner,String kind,String database,String schema,String table,String aliasProfile,boolean enabled) {}
  @Transactional public void resource(ResourceInput input) {
    var c=configure();code(input.database(),64);code(input.owner(),32);
    if(input.key()==null||!input.key().matches("[A-Za-z0-9_:.-]{1,128}")
       ||input.schema()==null||!input.schema().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
       ||input.table()==null||!input.table().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
       ||!Set.of("GLOBAL","PROJECT","REGION_SYSTEM","PROJECT_REGION_SHARED").contains(Objects.toString(input.kind(),"")))throw BizException.badRequest("资源登记无效");
    ScopeSql.Columns.valueOf(input.aliasProfile());
    var prior=db.queryForList("SELECT owner_app_code,scope_kind,database_key,table_schema,table_name,alias_profile FROM iam.iam_scope_resource WHERE resource_key=? FOR UPDATE",input.key());
    if(prior.isEmpty())db.update("INSERT INTO iam.iam_scope_resource(resource_key,owner_app_code,scope_kind,database_key,table_schema,table_name,alias_profile,enabled) VALUES(?,?,?,?,?,?,?,?)",input.key(),input.owner(),input.kind(),input.database(),input.schema(),input.table(),input.aliasProfile(),input.enabled());
    else {
      var before=prior.get(0);
      if(!Objects.equals(before.get("owner_app_code"),input.owner())||!Objects.equals(before.get("scope_kind"),input.kind())||!Objects.equals(before.get("database_key"),input.database())||!Objects.equals(before.get("table_schema"),input.schema())||!Objects.equals(before.get("table_name"),input.table())||!Objects.equals(before.get("alias_profile"),input.aliasProfile()))throw BizException.conflict("已有资源身份不可在线修改；请停用后登记新键");
      db.update("UPDATE iam.iam_scope_resource SET enabled=? WHERE resource_key=?",input.enabled(),input.key());
    }
    audit("RESOURCE",input.key(),c);
  }
  public record SharedGrant(String resourceKey,ScopeTriple scope,String action,boolean enabled,long expectedVersion) {}
  @Transactional public void sharedGrant(SharedGrant input) {
    var c=configure();var s=input.scope();lockProject(s.projectId(),input.expectedVersion());
    if(db.queryForObject("SELECT count(*) FROM iam.iam_scope_resource WHERE resource_key=? AND scope_kind='PROJECT_REGION_SHARED' AND enabled=TRUE",Integer.class,input.resourceKey())!=1)throw BizException.badRequest("共享资源尚未启用");
    var prior=db.queryForList("SELECT enabled FROM iam.iam_shared_resource_grant WHERE resource_key=? AND project_id=? AND region_id=? AND consumer_app_code=? AND op_code=?",input.resourceKey(),s.projectId(),s.regionId(),s.subsystemCode(),input.action());
    if(prior.isEmpty())db.update("INSERT INTO iam.iam_shared_resource_grant(resource_key,project_id,region_id,consumer_app_code,op_code,enabled,updated_by) VALUES(?,?,?,?,?,?,?)",input.resourceKey(),s.projectId(),s.regionId(),s.subsystemCode(),input.action(),input.enabled(),c.userId());
    else db.update("UPDATE iam.iam_shared_resource_grant SET enabled=?,updated_by=?,updated_at=CURRENT_TIMESTAMP WHERE resource_key=? AND project_id=? AND region_id=? AND consumer_app_code=? AND op_code=?",input.enabled(),c.userId(),input.resourceKey(),s.projectId(),s.regionId(),s.subsystemCode(),input.action());
    bumpProject(s.projectId());audit("SHARED_GRANT",input.resourceKey(),c);
  }
  @Transactional public void permissionMapping(PermissionMapping input) {
    var c=configure();
    if(!Set.of("PENDING","VERIFIED","DISABLED").contains(Objects.toString(input.status(),""))||input.evidence()==null||input.evidence().isBlank()||input.evidence().length()>500)throw BizException.badRequest("映射状态和核实依据必填");
    if(db.queryForObject("SELECT count(*) FROM iam.iam_permission p JOIN iam.iam_navigation_app a ON a.iam_system_code=p.system_code JOIN iam.iam_navigation_menu n ON n.navigation_app_code=a.app_code WHERE p.id=? AND n.stable_menu_key=? AND p.status='active'",Integer.class,input.permissionId(),input.menuKey())!=1)throw BizException.badRequest("权限码不属于当前菜单系统");
    var old=db.queryForList("SELECT permission_id FROM iam.iam_operation_permission_map WHERE stable_menu_key=? AND op_code=? AND permission_id=?",input.menuKey(),input.action(),input.permissionId());
    if(old.isEmpty())db.update("INSERT INTO iam.iam_operation_permission_map(stable_menu_key,op_code,permission_id,mapping_status) VALUES(?,?,?,?)",input.menuKey(),input.action(),input.permissionId(),input.status());
    else db.update("UPDATE iam.iam_operation_permission_map SET mapping_status=? WHERE stable_menu_key=? AND op_code=? AND permission_id=?",input.status(),input.menuKey(),input.action(),input.permissionId());
    db.update("INSERT INTO iam.iam_audit(id,kind,user_id,username,module,action,target,detail,result) VALUES(?,'operation',?,?,'scope','PERMISSION_MAPPING',?,?,'success')",UUID.randomUUID().toString(),c.userId(),c.username(),input.menuKey(),input.action()+"/"+input.permissionId()+"/"+input.status()+": "+input.evidence());
  }
  private void lockProject(String id,long expected) {
    var rows=db.queryForList("SELECT authz_version FROM iam.iam_project_preset WHERE project_id=? FOR UPDATE",id);
    if(rows.isEmpty())throw BizException.notFound("项目不存在");version(expected,rows.get(0).get("authz_version"));
  }
  private void bumpProject(String id){db.update("UPDATE iam.iam_project_preset SET authz_version=authz_version+1,updated_at=CURRENT_TIMESTAMP WHERE project_id=?",id);}
  private static void version(Long expected,Object actual){if(expected==null||expected.longValue()!=((Number)actual).longValue())throw BizException.conflict("配置已变化，请刷新后重试");}
  private static void code(String value,int max){try{ScopeTriple.identifier(value,max);}catch(IllegalArgumentException e){throw BizException.badRequest("编码格式无效");}}
  private static void name(String value){if(value==null||value.isBlank()||value.length()>128)throw BizException.badRequest("名称不能为空且不能超过128字");}
  private static void status(String value){if(!Set.of("active","disabled").contains(Objects.toString(value,"")))throw BizException.badRequest("状态无效");}
  private void audit(String action,String target,UserContext c) {
    db.update("INSERT INTO iam.iam_audit(id,kind,user_id,username,module,action,target,result) VALUES(?,'operation',?,?,'scope',?,?,'success')",UUID.randomUUID().toString(),c.userId(),c.username(),action,target);
  }
}
