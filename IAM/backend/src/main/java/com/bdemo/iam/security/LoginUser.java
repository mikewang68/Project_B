package com.bdemo.iam.security;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 认证后的当前登录用户（放入 SecurityContext principal）。
 */
public class LoginUser {

    private String userId;
    private String username;
    private String displayName;
    private boolean superAdmin;
    private boolean readOnly = false;
    private String accessMode = "SCOPED_USER";
    private Set<String> roles = new HashSet<>();
    private Set<String> scopedApps = new HashSet<>();
    private Set<String> permissions = new HashSet<>();

    public LoginUser() {
    }

    public LoginUser(String userId, String username, String displayName,
                     boolean superAdmin, Set<String> permissions) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.superAdmin = superAdmin;
        this.permissions = permissions == null ? new HashSet<>() : permissions;
    }

    public boolean hasPermission(String code) {
        return superAdmin || permissions.contains(code);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(String accessMode) {
        this.accessMode = accessMode;
    }

    public Set<String> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles == null ? new HashSet<>() : roles;
    }

    public Set<String> getScopedApps() {
        return Collections.unmodifiableSet(scopedApps);
    }

    public void setScopedApps(Set<String> scopedApps) {
        this.scopedApps = scopedApps == null ? new HashSet<>() : scopedApps;
    }

    /**
     * 可信数据域：由登录会话从 iam_user 读取，不接受客户端 Header 传入。
     * 用于用户列表等行级查询的强制过滤；任一为空表示无域授权，必须 fail-closed。
     */
    private String scopeZone;
    private String scopeCompany;

    public String getScopeZone() {
        return scopeZone;
    }

    public void setScopeZone(String scopeZone) {
        this.scopeZone = scopeZone;
    }

    public String getScopeCompany() {
        return scopeCompany;
    }

    public void setScopeCompany(String scopeCompany) {
        this.scopeCompany = scopeCompany;
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

    public boolean isSuperAdmin() {
        return superAdmin;
    }

    public void setSuperAdmin(boolean superAdmin) {
        this.superAdmin = superAdmin;
    }

    public Set<String> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions == null ? new HashSet<>() : permissions;
    }
}
