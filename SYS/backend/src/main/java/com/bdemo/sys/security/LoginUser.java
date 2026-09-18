package com.bdemo.sys.security;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class LoginUser {

    private String userId;
    private String username;
    private String displayName;
    private boolean superAdmin;
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
