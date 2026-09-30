package com.bdemo.iam.role.dto;

/**
 * 角色-权限关系行（批量查询用，避免 N+1）。
 */
public class RolePermissionRow {

    private String roleId;
    private String permissionCode;

    public RolePermissionRow() {
    }

    public String getRoleId() {
        return roleId;
    }

    public void setRoleId(String roleId) {
        this.roleId = roleId;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }
}
