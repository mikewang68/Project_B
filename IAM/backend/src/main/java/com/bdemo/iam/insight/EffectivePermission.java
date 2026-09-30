package com.bdemo.iam.insight;

import com.bdemo.iam.role.dto.RoleBrief;

import java.util.List;

/**
 * 一条有效权限及其来源角色。
 */
public class EffectivePermission {

    private String permissionCode;
    private String permissionName;
    private List<RoleBrief> sourceRoles;

    public EffectivePermission() {
    }

    public EffectivePermission(String permissionCode, String permissionName, List<RoleBrief> sourceRoles) {
        this.permissionCode = permissionCode;
        this.permissionName = permissionName;
        this.sourceRoles = sourceRoles;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }

    public String getPermissionName() {
        return permissionName;
    }

    public void setPermissionName(String permissionName) {
        this.permissionName = permissionName;
    }

    public List<RoleBrief> getSourceRoles() {
        return sourceRoles;
    }

    public void setSourceRoles(List<RoleBrief> sourceRoles) {
        this.sourceRoles = sourceRoles;
    }
}
