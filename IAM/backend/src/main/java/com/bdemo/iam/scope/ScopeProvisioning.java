package com.bdemo.iam.scope;

import com.bproject.iam.context.*;

import com.bdemo.iam.common.BizException;
import com.bdemo.iam.security.*;
import com.bdemo.iam.user.dto.UserUpsertRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ScopeProvisioning {
  private final JdbcTemplate db;private final ScopeContextService contexts;
  public ScopeProvisioning(JdbcTemplate db,ScopeContextService contexts){this.db=db;this.contexts=contexts;}
  public record Plan(String actorId,String adminLevel,boolean readOnly,String projectId,
      String regionId,String subsystemCode,String inheritedFrom,List<String> orgCodes) {}
  public Plan plan(UserUpsertRequest request) {
    var actor=JwtAuthFilter.currentUser();
    boolean hasScope=request.adminLevel()!=null||request.projectId()!=null||request.regionId()!=null
        ||request.subsystemCode()!=null||request.readOnly()!=null;
    if(actor==null||actor.getUserContext()==null) {
      if(hasScope)throw BizException.forbidden("新版归属只能由分级管理员创建");
      return null;
    }
    var ctx=contexts.requireManager(true);
    if(!ctx.managementCapabilities().contains("CREATE_USERS"))throw BizException.forbidden("不能创建人员");
    String target=request.adminLevel();
    String project=request.projectId(),region=request.regionId(),system=request.subsystemCode(),parent=null;
    List<String> org=request.orgCodes()==null?List.of():List.copyOf(request.orgCodes());
    switch(ctx.adminLevel()) {
      case "L1_SUPER" -> {
        if(!Set.of("L1_READONLY","L2_PROJECT").contains(Objects.toString(target,"")))throw BizException.forbidden("L1只能创建全局只读用户或项目管理员");
        if(target.equals("L1_READONLY")) {
          if(project!=null||region!=null||system!=null)throw BizException.badRequest("全局只读用户不能固定业务归属");
        }else {
          if(region!=null||system!=null)throw BizException.badRequest("项目管理员只能固定项目");
          activeProject(project);
        }
      }
      case "L2_PROJECT" -> {
        if(!"L3_SUBSYSTEM".equals(target))throw BizException.forbidden("项目管理员只能任命L3");
        if(!Objects.equals(project,ctx.projectId()))throw BizException.forbidden("不能跨项目创建管理员");
        requireActive(new ScopeTriple(project,region,system));
        parent=ctx.userId();
        if(org.isEmpty()) {
          String inherited=ScopeContextService.text(contexts.account(ctx.userId()),"org_codes");
          org=inherited.isBlank()?List.of():List.of(inherited.split(","));
        }
      }
      case "L3_SUBSYSTEM" -> {
        if(!"L4_OPERATOR".equals(target))throw BizException.forbidden("子系统管理员只能创建L4");
        if(project!=null&&!project.equals(ctx.projectId())||region!=null&&!region.equals(ctx.regionId())
            ||system!=null&&!system.equals(ctx.subsystemCode()))throw BizException.badRequest("三项归属必须继承管理员");
        project=ctx.projectId();region=ctx.regionId();system=ctx.subsystemCode();parent=ctx.userId();
        requireActive(new ScopeTriple(project,region,system));
        var administrator=contexts.account(ctx.userId());
        Map<String,Object> projectManager=null;
        if(ScopeContextService.text(administrator,"org_codes").isBlank()) {
          String manager=ScopeContextService.nullable(administrator,"inherited_from_user");
          if(manager!=null)projectManager=contexts.account(manager);
        }
        org=organizationForCreation(administrator,projectManager);
      }
      default -> throw BizException.forbidden("当前层级不能创建人员");
    }
    boolean readonly="L1_READONLY".equals(target)||Boolean.TRUE.equals(request.readOnly());
    var plan=new Plan(ctx.userId(),target,readonly,project,region,system,parent,org);
    validateRoles(plan,request.roleIds());
    return plan;
  }
  /** New children can inherit an approved parent organization without rewriting legacy accounts. */
  static List<String> organizationForCreation(Map<String,Object> administrator,Map<String,Object> projectManager) {
    String codes=ScopeContextService.text(administrator,"org_codes");
    if(codes.isBlank()) {
      if(projectManager==null||!"L2_PROJECT".equals(projectManager.get("admin_level"))
          ||!"active".equals(projectManager.get("status"))||!"V2_READY".equals(projectManager.get("scope_state"))
          ||!Objects.equals(administrator.get("project_id"),projectManager.get("project_id"))
          ||!Objects.equals(administrator.get("inherited_from_user"),projectManager.get("id")))
        throw BizException.badRequest("管理员缺少组织路径，且没有同项目的有效上级组织配置");
      codes=ScopeContextService.text(projectManager,"org_codes");
      if(codes.isBlank())throw BizException.badRequest("所属项目管理员尚未配置合法组织路径");
    }
    // OrgService still validates every node and company before creating the new person.
    return Arrays.stream(codes.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList();
  }
  private void activeProject(String project) {
    try{ScopeTriple.identifier(project,64);}catch(IllegalArgumentException e){throw BizException.badRequest("项目编码无效");}
    if(db.queryForObject("SELECT count(*) FROM iam.iam_project_preset WHERE project_id=? AND status='active'",Integer.class,project)!=1)
      throw BizException.forbidden("项目未启用");
  }
  public void requireActive(ScopeTriple scope) {
    if(db.queryForObject("SELECT count(*) FROM iam.iam_project_system_grant g JOIN iam.iam_project_preset p ON p.project_id=g.project_id AND p.status='active' JOIN iam.iam_region_preset r ON r.project_id=g.project_id AND r.region_id=g.region_id AND r.status='active' JOIN iam.iam_navigation_app a ON a.app_code=g.subsystem_code AND a.enabled=1 AND a.valid=1 WHERE g.project_id=? AND g.region_id=? AND g.subsystem_code=? AND g.l1_allowed=TRUE AND g.l2_enabled=TRUE",Integer.class,scope.projectId(),scope.regionId(),scope.subsystemCode())!=1)
      throw BizException.forbidden("该项目、区域、系统未完整授权或已停用");
  }
  public void apply(String id,Plan plan) {
    if(plan==null)return;
    db.update("UPDATE iam.iam_user SET user_code=id,scope_state='V2_READY',admin_level=?,read_only=?,project_id=?,region_id=?,subsystem_code=?,inherited_from_user=?,authz_version=authz_version+1 WHERE id=?",plan.adminLevel(),plan.readOnly(),plan.projectId(),plan.regionId(),plan.subsystemCode(),plan.inheritedFrom(),id);
    syncBindings(id);
  }
  public void validateRoles(Plan target,List<String> roleIds) {
    var ids=roleIds==null?List.<String>of():roleIds.stream().distinct().toList();
    if(target.adminLevel().equals("L4_OPERATOR")&&ids.isEmpty())throw BizException.badRequest("请至少选择一个业务岗位");
    for(String id:ids) {
      var rows=db.queryForList("SELECT role_scope,project_id,region_id,subsystem_code,status FROM iam.iam_role WHERE id=? AND deleted=0",id);
      if(rows.isEmpty()||!"active".equals(rows.get(0).get("status")))throw BizException.badRequest("角色不存在或已停用");
      var role=rows.get(0);
      boolean valid=target.adminLevel().equals("L1_READONLY")?"GLOBAL".equals(role.get("role_scope")):
          target.adminLevel().equals("L2_PROJECT")?"PROJECT".equals(role.get("role_scope"))&&Objects.equals(target.projectId(),role.get("project_id")):
          "REGION_SYSTEM".equals(role.get("role_scope"))&&Objects.equals(target.projectId(),role.get("project_id"))&&Objects.equals(target.regionId(),role.get("region_id"))&&Objects.equals(target.subsystemCode(),role.get("subsystem_code"));
      if(!valid)throw BizException.forbidden("角色归属与目标人员不一致");
      if(target.adminLevel().equals("L4_OPERATOR")) {
        var actions=db.queryForList("SELECT stable_menu_key,op_code FROM iam.iam_role_operation_grant WHERE role_id=?",id);
        for(var action:actions)if(db.queryForObject("SELECT count(*) FROM iam.iam_project_menu_grant g JOIN iam.iam_menu_operation m ON m.stable_menu_key=g.stable_menu_key AND m.op_code=g.op_code AND m.enabled=TRUE JOIN iam.iam_operation_perm_dict d ON d.op_code=m.op_code AND d.status='active' WHERE g.project_id=? AND g.region_id=? AND g.subsystem_code=? AND g.stable_menu_key=? AND g.op_code=? AND g.l1_allowed=TRUE AND g.l2_checked=TRUE",Integer.class,target.projectId(),target.regionId(),target.subsystemCode(),action.get("stable_menu_key"),action.get("op_code"))==0)
          throw BizException.forbidden("岗位包含上级已撤销的动作，请先修正岗位");
      }
    }
  }
  public void validateExistingUserRoles(String id,List<String> ids) {
    if(!contexts.enabled())return;
    var a=contexts.account(id);if(!"V2_READY".equals(a.get("scope_state")))return;
    // Upgrade preserves identity references, including existing legacy associations. They
    // cannot grant V2 actions and must never become an option for a new assignment.
    var retainedLegacy=new HashSet<>(db.queryForList("SELECT r.id FROM iam.iam_user_role ur"
        + " JOIN iam.iam_role r ON r.id=ur.role_id WHERE ur.user_id=?"
        + " AND r.role_scope='LEGACY' AND r.status='active' AND r.deleted=0",String.class,id));
    var scopedIds=ids==null?List.<String>of():ids.stream().filter(roleId->!retainedLegacy.contains(roleId)).toList();
    validateRoles(new Plan("",ScopeContextService.text(a,"admin_level"),Boolean.TRUE.equals(a.get("read_only")),ScopeContextService.nullable(a,"project_id"),ScopeContextService.nullable(a,"region_id"),ScopeContextService.nullable(a,"subsystem_code"),null,List.of()),scopedIds);
  }
  public void refreshReadonlyBindings(String id) {
    syncBindings(id);
  }
  public void syncAllBindings() {
    if(!contexts.enabled())return;
    for(String id:db.queryForList("SELECT id FROM iam.iam_user WHERE deleted=0 AND scope_state='V2_READY'",String.class))
      syncBindings(id);
  }
  public void syncBindings(String id) {
    if(!contexts.enabled())return;
    var account=contexts.account(id);
    if(!"V2_READY".equals(account.get("scope_state")))return;
    var user=new LoginUser(id,ScopeContextService.text(account,"username"),
        ScopeContextService.text(account,"display_name"),false,Set.of());
    contexts.enrich(user);
    var context=user.getUserContext();
    var candidates=db.queryForList("SELECT app_code,enabled,valid,in_nav_scope,domain_name,origin_ip"
        + " FROM iam.iam_navigation_app WHERE context_contract_max>=2");
    for(var registered:candidates) {
      String app=registered.get("app_code").toString();
      String audience=app.toLowerCase(Locale.ROOT);
      boolean ready=((Number)registered.get("enabled")).intValue()==1
          &&((Number)registered.get("valid")).intValue()==1
          &&((Number)registered.get("in_nav_scope")).intValue()==1
          &&(!Objects.toString(registered.get("domain_name"),"").isBlank()
              ||!Objects.toString(registered.get("origin_ip"),"").isBlank());
      boolean hasMenu=context.menuOperations().keySet().stream().anyMatch(menu->
          db.queryForObject("SELECT count(*) FROM iam.iam_navigation_menu WHERE stable_menu_key=?"
              + " AND navigation_app_code=?",Integer.class,menu,app)==1);
      // A project reader can explicitly select one authorized region even when another
      // region removes that action from the default multi-region intersection.
      if(context.adminLevel().equals("L2_PROJECT"))hasMenu=!contexts.nativeScopes(user,app).isEmpty();
      boolean managementAudience=ScopeManagementAccess.allowed(context,app);
      boolean desired=ready&&(managementAudience||context.scopeMode()!=ScopeSql.Mode.DENY
          &&(context.adminLevel().equals("L1_SUPER")||hasMenu));
      if(!desired) {
        db.update("UPDATE iam.sso_subject_binding SET enabled=0,updated_at=CURRENT_TIMESTAMP"
            + " WHERE subject=? AND audience=?",id,audience);
        continue;
      }
      // Existing consumer aliases and business actor identifiers are immutable here.
      int changed=db.update("UPDATE iam.sso_subject_binding SET enabled=1,updated_at=CURRENT_TIMESTAMP"
          + " WHERE subject=? AND audience=?",id,audience);
      if(changed==0)db.update("INSERT INTO iam.sso_subject_binding(subject,audience,local_subject,enabled) VALUES(?,?,?,1)",id,audience,id);
      if(!context.global()) {
        db.update("UPDATE iam.sso_project_grant SET enabled=0"
            + " WHERE subject=? AND audience=?",id,audience);
        int updated=db.update("UPDATE iam.sso_project_grant SET enabled=1"
            + " WHERE subject=? AND audience=? AND project_id=?",id,audience,context.projectId());
        if(updated==0)db.update("INSERT INTO iam.sso_project_grant(subject,audience,project_id,enabled)"
            + " VALUES(?,?,?,1)",id,audience,context.projectId());
      }
    }
  }
  public boolean visible(String kind,String id,UserContext actor) {
    // An empty business selection must not hide management objects from an active
    // platform/project manager. Revoked managers have no VIEW_MANAGEMENT capability.
    boolean managementWithoutBusiness=actor.mayManage()
        && Set.of("L1_SUPER","L1_READONLY","L2_PROJECT").contains(actor.adminLevel());
    if(actor.scopeMode()==ScopeSql.Mode.DENY&&!managementWithoutBusiness)return false;
    if(Set.of("L1_SUPER","L1_READONLY").contains(actor.adminLevel()))return true;
    if(kind.equals("USER")) {
      var rows=db.queryForList("SELECT admin_level,scope_state,project_id,region_id,subsystem_code FROM iam.iam_user WHERE id=? AND deleted=0",id);
      if(rows.isEmpty()||!"V2_READY".equals(rows.get(0).get("scope_state")))return false;
      var target=rows.get(0);
      if(!Objects.equals(actor.projectId(),target.get("project_id")))return false;
      if(actor.adminLevel().equals("L2_PROJECT"))return Set.of("L2_PROJECT","L3_SUBSYSTEM","L4_OPERATOR").contains(target.get("admin_level"));
      return actor.adminLevel().equals("L3_SUBSYSTEM")&&Objects.equals(actor.regionId(),target.get("region_id"))&&Objects.equals(actor.subsystemCode(),target.get("subsystem_code"))&&("L4_OPERATOR".equals(target.get("admin_level"))||id.equals(actor.userId()));
    }
    if(kind.equals("ROLE")) {
      var rows=db.queryForList("SELECT project_id,region_id,subsystem_code,role_scope FROM iam.iam_role WHERE id=? AND deleted=0",id);
      if(rows.isEmpty())return false;var target=rows.get(0);
      if("PROJECT".equals(target.get("role_scope")))return actor.adminLevel().equals("L2_PROJECT")&&Objects.equals(actor.projectId(),target.get("project_id"));
      return "REGION_SYSTEM".equals(target.get("role_scope"))&&Objects.equals(actor.projectId(),target.get("project_id"))&&(actor.adminLevel().equals("L2_PROJECT")||actor.adminLevel().equals("L3_SUBSYSTEM")&&Objects.equals(actor.regionId(),target.get("region_id"))&&Objects.equals(actor.subsystemCode(),target.get("subsystem_code")));
    }
    return false;
  }
  public void requireOwned(String kind,String id) {
    var actor=contexts.requireManager(true);
    if(!visible(kind,id,actor))throw BizException.notFound("对象不存在");
    if(kind.equals("USER")) {
      var target=contexts.account(id);String level=ScopeContextService.text(target,"admin_level");
      if(level.equals("L1_SUPER"))throw BizException.forbidden("全局管理员须通过受控维护流程变更");
      if(id.equals(actor.userId()))throw BizException.badRequest("不能通过人员管理修改当前登录账号");
      if(actor.adminLevel().equals("L2_PROJECT")&&!Set.of("L3_SUBSYSTEM","L4_OPERATOR").contains(level)
          ||actor.adminLevel().equals("L3_SUBSYSTEM")&&!"L4_OPERATOR".equals(level))throw BizException.forbidden("不能管理同级或上级账号");
    }else if(kind.equals("ROLE")) {
      var role=db.queryForMap("SELECT role_scope FROM iam.iam_role WHERE id=?",id);
      if(actor.adminLevel().equals("L1_SUPER")) {
        if(!Set.of("GLOBAL","PROJECT").contains(role.get("role_scope")))throw BizException.forbidden("L1仅维护全局和项目读取岗位");
      }else if(!actor.adminLevel().equals("L3_SUBSYSTEM"))throw BizException.forbidden("业务岗位由对应L3维护");
    }
  }
}
