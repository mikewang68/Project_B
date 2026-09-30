package com.bdemo.iam.insight;

import com.bdemo.iam.role.dto.RoleBrief;

import java.util.List;

/**
 * 用户有效权限视图：用户 + 有效角色 + 最终权限（含来源）。
 */
public class EffectivePermissionView {

    private boolean enabled;
    private boolean superAdmin;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isSuperAdmin() { return superAdmin; }
    public void setSuperAdmin(boolean superAdmin) { this.superAdmin = superAdmin; }
    private String userId;
    private String username;
    private String displayName;
    private List<RoleBrief> roles;
    private List<EffectivePermission> permissions;

    public EffectivePermissionView() {
    }

    public EffectivePermissionView(String userId, String username, String displayName,
                                   List<RoleBrief> roles, List<EffectivePermission> permissions) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.roles = roles;
        this.permissions = permissions;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public List<RoleBrief> getRoles() {
        return roles;
    }

    public void setRoles(List<RoleBrief> roles) {
        this.roles = roles;
    }

    public List<EffectivePermission> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<EffectivePermission> permissions) {
        this.permissions = permissions;
    }
}
