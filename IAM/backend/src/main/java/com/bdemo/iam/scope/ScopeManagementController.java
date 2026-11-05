package com.bdemo.iam.scope;

import com.bdemo.iam.common.*;
import com.bdemo.iam.provisioning.UserCreation;
import com.bdemo.iam.org.OrgService;
import com.bdemo.iam.org.domain.OrgNode;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.user.UserService;
import com.bdemo.iam.user.domain.User;
import com.bdemo.iam.user.dto.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/management")
@ConditionalOnProperty(name="iam.scope-v2.enabled",havingValue="true")
public class ScopeManagementController {
  private final ScopeManagementService service;private final ScopeContextService contexts;
  private final ScopeProvisioning scopes;private final UserCreation creation;private final UserService users;
  private final OrgService organizations;
  public ScopeManagementController(ScopeManagementService service,ScopeContextService contexts,
      ScopeProvisioning scopes,UserCreation creation,UserService users,OrgService organizations) {
    this.service=service;this.contexts=contexts;this.scopes=scopes;this.creation=creation;this.users=users;
    this.organizations=organizations;
  }
  /** Management metadata remains available when an unconfigured project has no business tuple. */
  @GetMapping("/organizations") public R<List<OrgNode>> organizations() {
    var context=contexts.requireManager(false);
    if(!"L1_SUPER".equals(context.adminLevel())||context.readOnly()
        ||!context.managementCapabilities().contains("CONFIGURE_PLATFORM"))
      throw BizException.forbidden("组织目录仅供L1配置人员使用");
    return R.ok(organizations.tree());
  }
  @GetMapping("/overview") public R<Map<String,Object>> overview(){return R.ok(service.overview());}
  @GetMapping("/users") public R<List<Map<String,Object>>> users(){return R.ok(service.users());}
  @GetMapping("/users/availability") public R<Map<String,Object>> availability(@RequestParam String username){return R.ok(service.availability(username));}
  @PostMapping("/users") public ResponseEntity<R<User>> createUser(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody UserUpsertRequest input) {
    contexts.requireManager(true);var user=creation.create(key,input);
    boolean ready=user.getFabricIdentity()!=null&&"READY".equals(user.getFabricIdentity().get("state"));
    return ResponseEntity.status(ready?201:202).body(R.ok(user));
  }
  @PutMapping("/users/{id}/status") public R<User> userStatus(@PathVariable String id,@Valid @RequestBody Status input) {
    scopes.requireOwned("USER",id);return R.ok(users.setStatus(id,input.status(),JwtAuthFilter.currentUser()));
  }
  public record Status(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="active|disabled") String status) {}
  @PutMapping("/users/{id}/password") public R<Void> password(@PathVariable String id,@Valid @RequestBody ResetPasswordRequest input) {
    scopes.requireOwned("USER",id);users.resetPassword(id,input.password());return R.ok();
  }
  @PutMapping("/users/{id}/roles") public R<User> assign(@PathVariable String id,@RequestBody AssignRolesRequest input) {
    scopes.requireOwned("USER",id);return R.ok(users.assignRoles(id,input.roleIds()));
  }
  @DeleteMapping("/users/{id}") public R<Void> deleteUser(@PathVariable String id) {
    scopes.requireOwned("USER",id);users.delete(id,JwtAuthFilter.currentUser());return R.ok();
  }
  @GetMapping("/roles") public R<List<Map<String,Object>>> roles(){return R.ok(service.roles());}
  @GetMapping("/role-options") public R<List<Map<String,Object>>> options(){return R.ok(service.roleOptions());}
  @PostMapping("/roles") public R<Map<String,String>> createRole(@RequestBody ScopeManagementService.RoleInput input){return R.ok(Map.of("id",service.saveRole(null,input)));}
  @PutMapping("/roles/{id}") public R<Map<String,String>> updateRole(@PathVariable String id,@RequestBody ScopeManagementService.RoleInput input){return R.ok(Map.of("id",service.saveRole(id,input)));}
  @DeleteMapping("/roles/{id}") public R<Void> deleteRole(@PathVariable String id){service.deleteRole(id);return R.ok();}
  @PutMapping("/projects") public R<Void> project(@RequestBody ScopeManagementService.ProjectInput input){service.project(input);return R.ok();}
  @PutMapping("/regions") public R<Void> region(@RequestBody ScopeManagementService.RegionInput input){service.region(input);return R.ok();}
  @PutMapping("/system-grants") public R<Void> system(@RequestBody ScopeManagementService.SystemGrant input){service.systemGrant(input);return R.ok();}
  @PutMapping("/menu-grants") public R<Void> menu(@RequestBody ScopeManagementService.MenuGrant input){service.menuGrant(input);return R.ok();}
  @PutMapping("/menu-actions") public R<Void> menuAction(@RequestBody ScopeManagementService.MenuAction input){service.menuAction(input);return R.ok();}
  @PutMapping("/operations") public R<Void> operation(@RequestBody ScopeManagementService.OperationInput input){service.operation(input);return R.ok();}
  @PutMapping("/applications") public R<Void> application(@RequestBody ScopeManagementService.ApplicationInput input){service.application(input);return R.ok();}
  @PutMapping("/permission-mappings") public R<Void> mapping(@RequestBody ScopeManagementService.PermissionMapping input){service.permissionMapping(input);return R.ok();}
  @PutMapping("/resources") public R<Void> resource(@RequestBody ScopeManagementService.ResourceInput input){service.resource(input);return R.ok();}
  @PutMapping("/shared-grants") public R<Void> shared(@RequestBody ScopeManagementService.SharedGrant input){service.sharedGrant(input);return R.ok();}
  @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<R<Void>> invalidScope(IllegalArgumentException error) {
    return ResponseEntity.badRequest().body(R.fail(400,"范围或参数格式无效"));
  }
}
