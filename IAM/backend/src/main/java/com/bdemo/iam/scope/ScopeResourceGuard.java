package com.bdemo.iam.scope;

import com.bproject.iam.context.*;

import com.bdemo.iam.common.BizException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resource and operation checks precede filter generation. Never execute browser-supplied SQL. */
@Service
public class ScopeResourceGuard {
  private final JdbcTemplate db;
  public ScopeResourceGuard(JdbcTemplate db){this.db=db;}
  public record AuthorizedFilter(String condition,String template,Map<String,String> parameters) {}
  public AuthorizedFilter authorize(UserContext context,String resourceKey,String menuKey,String action) {
    if(context==null||!context.hasAction(menuKey,action)||context.readOnly()&&!ScopeContextService.READONLY_ACTIONS.contains(action))
      throw BizException.forbidden("资源动作未获授权");
    var rows=db.queryForList("SELECT r.scope_kind,r.owner_app_code,r.alias_profile,n.navigation_app_code FROM iam.iam_scope_resource r JOIN iam.iam_navigation_menu n ON n.stable_menu_key=? WHERE r.resource_key=? AND r.enabled=TRUE",menuKey,resourceKey);
    if(rows.size()!=1)throw BizException.forbidden("资源未登记或未启用");
    var r=rows.get(0);String kind=r.get("scope_kind").toString(),owner=r.get("owner_app_code").toString();
    String consumer=context.serviceAudience();
    boolean shared=kind.equals("PROJECT_REGION_SHARED");
    if(!shared&&!owner.equals(r.get("navigation_app_code"))||shared&&!consumer.equals(r.get("navigation_app_code")))
      throw BizException.forbidden("资源与消费菜单不匹配");
    ScopeSql.Columns profile;
    try{profile=ScopeSql.Columns.valueOf(r.get("alias_profile").toString());}catch(Exception bad){throw BizException.forbidden("资源列映射未核定");}
    if(kind.equals("GLOBAL")) {
      if(!ScopeContextService.READONLY_ACTIONS.contains(action)&&!context.adminLevel().equals("L1_SUPER"))throw BizException.forbidden("全局基础数据只允许全局管理员维护");
      return new AuthorizedFilter(" AND 1=1 "," AND 1=1 ",Map.of());
    }
    if(shared) {
      var permitted=new TreeSet<ScopeTriple>();
      for(var s:context.activeScope())if(s.subsystemCode().equals(consumer)&&db.queryForObject("SELECT count(*) FROM iam.iam_shared_resource_grant g JOIN iam.iam_project_system_grant p ON p.project_id=g.project_id AND p.region_id=g.region_id AND p.subsystem_code=g.consumer_app_code AND p.l1_allowed=TRUE AND p.l2_enabled=TRUE WHERE g.resource_key=? AND g.project_id=? AND g.region_id=? AND g.consumer_app_code=? AND g.op_code=? AND g.enabled=TRUE",Integer.class,resourceKey,s.projectId(),s.regionId(),consumer,action)>0)permitted.add(s);
      var filters=ScopeSql.build(ScopeSql.Mode.SCOPED,permitted,profile);
      return new AuthorizedFilter(filters.my_project_zone_info(),filters.my_project_zone_info_template(),filters.scope_params());
    }
    if(!kind.equals("REGION_SYSTEM")&&!kind.equals("PROJECT"))throw BizException.forbidden("未知资源范围类型");
    if(context.global())return new AuthorizedFilter(" AND 1=1 "," AND 1=1 ",Map.of());
    var owned=context.activeScope().stream().filter(s->s.subsystemCode().equals(owner)).toList();
    var filters=ScopeSql.build(ScopeSql.Mode.SCOPED,owned,profile);
    if(kind.equals("REGION_SYSTEM"))return new AuthorizedFilter(filters.my_info(),filters.my_info_template(),filters.scope_params());
    if(owned.isEmpty())return new AuthorizedFilter(" AND 1=0 "," AND 1=0 ",Map.of());
    var projectIds=new TreeSet<String>();owned.forEach(s->projectIds.add(s.projectId()));
    var literal=new ArrayList<String>();var template=new ArrayList<String>();var params=new LinkedHashMap<String,String>();int i=0;
    for(var p:projectIds){literal.add(profile.projectColumn()+" = '"+ScopeTriple.identifier(p,64)+"'");template.add(profile.projectColumn()+" = :p"+i);params.put("p"+i,p);i++;}
    return new AuthorizedFilter(" AND ("+String.join(" OR ",literal)+") "," AND ("+String.join(" OR ",template)+") ",Collections.unmodifiableMap(params));
  }
}
