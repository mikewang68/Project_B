package com.bdemo.admin;

import com.bdemo.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminService {
    private static final Map<String,Meta> META=Map.of(
            "user",new Meta("sys_user","user_id",Set.of("dept_id","user_name","nick_name","user_type","email","phonenumber","sex","avatar","password","status","remark"),"del_flag='0'"),
            "role",new Meta("sys_role","role_id",Set.of("role_name","role_key","role_sort","data_scope","menu_check_strictly","dept_check_strictly","status","remark"),"del_flag='0'"),
            "menu",new Meta("sys_menu","menu_id",Set.of("menu_name","parent_id","order_num","path","component","query","route_name","is_frame","is_cache","menu_type","visible","status","perms","icon","remark"),"1=1"),
            "dept",new Meta("sys_dept","dept_id",Set.of("parent_id","ancestors","dept_name","order_num","leader","phone","email","status"),"del_flag='0'"),
            "post",new Meta("sys_post","post_id",Set.of("post_code","post_name","post_sort","status","remark"),"1=1"),
            "dictType",new Meta("sys_dict_type","dict_id",Set.of("dict_name","dict_type","status","remark"),"1=1"),
            "dictData",new Meta("sys_dict_data","dict_code",Set.of("dict_sort","dict_label","dict_value","dict_type","css_class","list_class","is_default","status","remark"),"1=1"),
            "config",new Meta("sys_config","config_id",Set.of("config_name","config_key","config_value","config_type","remark"),"1=1"),
            "notice",new Meta("sys_notice","notice_id",Set.of("notice_title","notice_type","notice_content","status","remark"),"1=1"));
    private final JdbcTemplate jdbc;private final PasswordEncoder passwords;
    public AdminService(JdbcTemplate jdbc,PasswordEncoder passwords){this.jdbc=jdbc;this.passwords=passwords;}
    public Map<String,Object>page(String kind,Map<String,String>query){Meta m=meta(kind);int page=intVal(query.get("pageNum"),1),size=Math.min(100,intVal(query.get("pageSize"),10));List<Object>p=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE "+m.base);for(String key:m.columns){String camel=camel(key),v=query.get(camel);if(v!=null&&!v.isBlank()&&!key.equals("password")){where.append(key.endsWith("name")||key.endsWith("title")||key.equals("config_key")?" AND "+key+" LIKE ?":" AND "+key+"=?");p.add(key.endsWith("name")||key.endsWith("title")||key.equals("config_key")?"%"+v+"%":v);}}Integer total=jdbc.queryForObject("SELECT COUNT(*) FROM "+m.table+where,Integer.class,p.toArray());p.add(size);p.add((page-1)*size);List<Map<String,Object>>rows=jdbc.queryForList("SELECT * FROM "+m.table+where+" ORDER BY "+m.id+" LIMIT ? OFFSET ?",p.toArray()).stream().map(this::camelMap).toList();return map("rows",rows,"total",total);}
    public Object list(String kind,Map<String,String>query){Meta m=meta(kind);Map<String,Object>paged=page(kind,new LinkedHashMap<>(query){{put("pageNum","1");put("pageSize","100");}});return paged.get("rows");}
    public Map<String,Object>get(String kind,long id){Meta m=meta(kind);List<Map<String,Object>>r=jdbc.queryForList("SELECT * FROM "+m.table+" WHERE "+m.id+"=?",id);if(r.isEmpty())throw new BusinessException(404,"记录不存在");return camelMap(r.get(0));}
    @Transactional public int create(String kind,Map<String,Object>body,String user){Meta m=meta(kind);Map<String,Object>values=values(m,body);if(kind.equals("user")&&values.containsKey("password"))values.put("password",passwords.encode(String.valueOf(values.get("password"))));values.put("create_by",user);values.put("create_time",Timestamp.valueOf(LocalDateTime.now()));String cols=String.join(",",values.keySet()),marks=String.join(",",java.util.Collections.nCopies(values.size(),"?"));return jdbc.update("INSERT INTO "+m.table+"("+cols+") VALUES("+marks+")",values.values().toArray());}
    @Transactional public int update(String kind,Map<String,Object>body,String user){Meta m=meta(kind);Object id=body.get(camel(m.id));if(id==null)throw new IllegalArgumentException(camel(m.id)+" 不能为空");Map<String,Object>values=values(m,body);values.remove(m.id);values.remove("password");values.put("update_by",user);values.put("update_time",Timestamp.valueOf(LocalDateTime.now()));String set=String.join(",",values.keySet().stream().map(k->k+"=?").toList());List<Object>p=new ArrayList<>(values.values());p.add(id);return jdbc.update("UPDATE "+m.table+" SET "+set+" WHERE "+m.id+"=?",p.toArray());}
    @Transactional public int delete(String kind,List<Long>ids){Meta m=meta(kind);if(ids.isEmpty())return 0;String q=String.join(",",java.util.Collections.nCopies(ids.size(),"?"));if(Set.of("user","role","dept").contains(kind))return jdbc.update("UPDATE "+m.table+" SET del_flag='2' WHERE "+m.id+" IN ("+q+")",ids.toArray());return jdbc.update("DELETE FROM "+m.table+" WHERE "+m.id+" IN ("+q+")",ids.toArray());}
    @Transactional public int status(String kind,Map<String,Object>b){Meta m=meta(kind);Object id=b.get(camel(m.id));return jdbc.update("UPDATE "+m.table+" SET status=? WHERE "+m.id+"=?",b.get("status"),id);}
    @Transactional public int resetPassword(Map<String,Object>b){if(b.get("userId")==null||b.get("password")==null)throw new IllegalArgumentException("userId/password 不能为空");return jdbc.update("UPDATE sys_user SET password=?,pwd_update_date=? WHERE user_id=?",passwords.encode(String.valueOf(b.get("password"))),Timestamp.valueOf(LocalDateTime.now()),b.get("userId"));}
    public List<Map<String,Object>>dictByType(String type){return jdbc.queryForList("SELECT * FROM sys_dict_data WHERE dict_type=? AND status='0' ORDER BY dict_sort",type).stream().map(this::camelMap).toList();}
    public String configValue(String key){List<String>r=jdbc.query("SELECT config_value FROM sys_config WHERE config_key=?",(rs,n)->rs.getString(1),key);if(r.isEmpty())throw new BusinessException(404,"参数不存在");return r.get(0);}
    public List<Map<String,Object>>menuTree(){return tree(jdbc.queryForList("SELECT * FROM sys_menu WHERE status='0' ORDER BY parent_id,order_num").stream().map(this::camelMap).toList(),0L,"menuId","parentId");}
    // RuoYi 部门树组件使用 id/label；保留 deptId/deptName 兼容管理表单。
    public List<Map<String,Object>>deptTree(){return tree(jdbc.queryForList("SELECT * FROM sys_dept WHERE del_flag='0' ORDER BY parent_id,order_num").stream().map(row -> {Map<String,Object> item=camelMap(row);item.put("id",item.get("deptId"));item.put("label",item.get("deptName"));return item;}).toList(),0L,"deptId","parentId");}
    public Map<String,Object>userForm(Long userId){List<Map<String,Object>>posts=jdbc.queryForList("SELECT post_id,post_name,post_code,status FROM sys_post WHERE status='0' ORDER BY post_sort").stream().map(this::camelMap).toList();List<Map<String,Object>>roles=jdbc.queryForList("SELECT role_id,role_name,role_key,status FROM sys_role WHERE status='0' AND del_flag='0' ORDER BY role_sort").stream().map(this::camelMap).toList();Map<String,Object>out=new LinkedHashMap<>();out.put("posts",posts);out.put("roles",roles);out.put("postIds",List.of());out.put("roleIds",List.of());if(userId!=null){out.put("data",get("user",userId));out.put("postIds",jdbc.queryForList("SELECT post_id FROM sys_user_post WHERE user_id=? ORDER BY post_id",Long.class,userId));out.put("roleIds",jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE user_id=? ORDER BY role_id",Long.class,userId));}return out;}
    public Map<String,Object>roleMenuTree(long roleId){List<Long>checked=jdbc.queryForList("SELECT menu_id FROM sys_role_menu WHERE role_id=? ORDER BY menu_id",Long.class,roleId);return map("checkedKeys",checked,"menus",menuTree());}
    public List<Map<String,Object>>excludeDept(long deptId){return jdbc.queryForList("SELECT * FROM sys_dept WHERE del_flag='0' AND dept_id<>? AND POSITION(',' || CAST(? AS text) || ',' IN ',' || ancestors || ',')=0 ORDER BY parent_id,order_num",deptId,deptId).stream().map(this::camelMap).toList();}
    private List<Map<String,Object>>tree(List<Map<String,Object>>all,long parent,String id,String parentKey){List<Map<String,Object>>out=new ArrayList<>();for(Map<String,Object>item:all)if(((Number)item.get(parentKey)).longValue()==parent){Map<String,Object>node=new LinkedHashMap<>(item);List<Map<String,Object>>children=tree(all,((Number)item.get(id)).longValue(),id,parentKey);if(!children.isEmpty())node.put("children",children);out.add(node);}return out;}
    private Map<String,Object>values(Meta m,Map<String,Object>b){Map<String,Object>v=new LinkedHashMap<>();for(String col:m.columns)if(b.containsKey(camel(col)))v.put(col,b.get(camel(col)));return v;}private Meta meta(String k){Meta m=META.get(k);if(m==null)throw new IllegalArgumentException("未知管理资源");return m;}private Map<String,Object>camelMap(Map<String,Object>r){Map<String,Object>m=new LinkedHashMap<>();r.forEach((k,v)->m.put(camel(k),v));return m;}private String camel(String s){StringBuilder b=new StringBuilder();boolean up=false;for(char c:s.toCharArray()){if(c=='_')up=true;else{b.append(up?Character.toUpperCase(c):c);up=false;}}return b.toString();}private int intVal(String v,int d){try{return Integer.parseInt(v);}catch(Exception e){return d;}}private Map<String,Object>map(Object...p){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<p.length;i+=2)m.put((String)p[i],p[i+1]);return m;}private record Meta(String table,String id,Set<String>columns,String base){}
}
