package com.bdemo.admin;

import com.bdemo.auth.AuthenticatedUser;
import com.bdemo.common.AjaxResult;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system")
public class AdminController {
    private final AdminService service;public AdminController(AdminService service){this.service=service;}
    @GetMapping("/{resource:user|role|post|config|notice}/list") public AjaxResult list(@PathVariable String resource,@RequestParam Map<String,String>query){return table(service.page(resource,query));}
    @GetMapping("/dict/type/list") public AjaxResult dictTypes(@RequestParam Map<String,String>q){return table(service.page("dictType",q));}
    @GetMapping("/dict/data/list") public AjaxResult dictData(@RequestParam Map<String,String>q){return table(service.page("dictData",q));}
    @GetMapping("/dict/data/type/{type}") public AjaxResult dict(@PathVariable String type){return AjaxResult.success(service.dictByType(type));}
    @GetMapping("/config/configKey/{key}") public AjaxResult configValue(@PathVariable String key){return AjaxResult.success(service.configValue(key));}
    @GetMapping("/menu/treeselect") public AjaxResult menuTree(){return AjaxResult.success(service.menuTree());}
    @GetMapping("/menu/list") public AjaxResult menuList(@RequestParam Map<String,String>q){return AjaxResult.success(service.list("menu",q));}
    @GetMapping("/dept/list") public AjaxResult deptList(@RequestParam Map<String,String>q){return AjaxResult.success(service.list("dept",q));}
    @GetMapping("/dept/treeselect") public AjaxResult deptTree(){return AjaxResult.success(service.deptTree());}

    @GetMapping({"/user","/user/"}) public AjaxResult userOptions(){return AjaxResult.success().addAll(service.userForm(null));}
    @GetMapping("/user/deptTree") public AjaxResult userDeptTree(){return AjaxResult.success(service.deptTree());}
    @GetMapping("/user/{id:\\d+}") public AjaxResult userForm(@PathVariable long id){return AjaxResult.success().addAll(service.userForm(id));}
    @GetMapping("/menu/roleMenuTreeselect/{roleId}") public AjaxResult roleMenuTree(@PathVariable long roleId){return AjaxResult.success().addAll(service.roleMenuTree(roleId));}
    @GetMapping("/dept/list/exclude/{deptId}") public AjaxResult excludeDept(@PathVariable long deptId){return AjaxResult.success(service.excludeDept(deptId));}
    @GetMapping("/{resource:role|menu|dept|post|config|notice}/{id:\\d+}") public AjaxResult get(@PathVariable String resource,@PathVariable long id){return AjaxResult.success(service.get(resource,id));}
    @GetMapping("/dict/type/{id:\\d+}") public AjaxResult dictType(@PathVariable long id){return AjaxResult.success(service.get("dictType",id));}
    @GetMapping("/dict/data/{id:\\d+}") public AjaxResult dictData(@PathVariable long id){return AjaxResult.success(service.get("dictData",id));}
    @PostMapping("/{resource:user|role|menu|dept|post|config|notice}") public AjaxResult create(@PathVariable String resource,@RequestBody Map<String,Object>b,@AuthenticationPrincipal AuthenticatedUser u){service.create(resource,b,u.userName());return AjaxResult.success();}
    @PostMapping("/dict/type") public AjaxResult createDictType(@RequestBody Map<String,Object>b,@AuthenticationPrincipal AuthenticatedUser u){service.create("dictType",b,u.userName());return AjaxResult.success();}
    @PostMapping("/dict/data") public AjaxResult createDictData(@RequestBody Map<String,Object>b,@AuthenticationPrincipal AuthenticatedUser u){service.create("dictData",b,u.userName());return AjaxResult.success();}
    @PutMapping("/{resource:user|role|menu|dept|post|config|notice}") public AjaxResult update(@PathVariable String resource,@RequestBody Map<String,Object>b,@AuthenticationPrincipal AuthenticatedUser u){service.update(resource,b,u.userName());return AjaxResult.success();}
    @PutMapping("/dict/type") public AjaxResult updateDictType(@RequestBody Map<String,Object>b,@AuthenticationPrincipal AuthenticatedUser u){service.update("dictType",b,u.userName());return AjaxResult.success();}
    @PutMapping("/dict/data") public AjaxResult updateDictData(@RequestBody Map<String,Object>b,@AuthenticationPrincipal AuthenticatedUser u){service.update("dictData",b,u.userName());return AjaxResult.success();}
    @DeleteMapping("/{resource:user|role|menu|dept|post|config|notice}/{ids}") public AjaxResult delete(@PathVariable String resource,@PathVariable String ids){service.delete(resource,ids(ids));return AjaxResult.success();}
    @DeleteMapping("/dict/type/{ids}") public AjaxResult deleteDictType(@PathVariable String ids){service.delete("dictType",ids(ids));return AjaxResult.success();}
    @DeleteMapping("/dict/data/{ids}") public AjaxResult deleteDictData(@PathVariable String ids){service.delete("dictData",ids(ids));return AjaxResult.success();}
    @PutMapping("/user/changeStatus") public AjaxResult userStatus(@RequestBody Map<String,Object>b){service.status("user",b);return AjaxResult.success();}
    @PutMapping("/role/changeStatus") public AjaxResult roleStatus(@RequestBody Map<String,Object>b){service.status("role",b);return AjaxResult.success();}
    @PutMapping("/user/resetPwd") public AjaxResult reset(@RequestBody Map<String,Object>b){service.resetPassword(b);return AjaxResult.success();}
    @DeleteMapping("/dict/type/refreshCache") public AjaxResult refreshDict(){return AjaxResult.success();}
    @DeleteMapping("/config/refreshCache") public AjaxResult refreshConfig(){return AjaxResult.success();}
    @GetMapping("/dict/type/optionselect") public AjaxResult dictOptions(){return AjaxResult.success(service.list("dictType",Map.of()));}
    private AjaxResult table(Map<String,Object>m){return AjaxResult.success().addAll(m);}private List<Long>ids(String s){return Arrays.stream(s.split(",")).map(Long::parseLong).toList();}
}
