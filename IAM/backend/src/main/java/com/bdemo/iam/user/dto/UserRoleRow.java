package com.bdemo.iam.user.dto;

/**
 * 用户-角色关系行（批量查询用，避免 N+1）。
 */
public class UserRoleRow {

    private String userId;
    private String roleId;

    public UserRoleRow() {
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRoleId() {
        return roleId;
    }

    public void setRoleId(String roleId) {
        this.roleId = roleId;
    }
}
