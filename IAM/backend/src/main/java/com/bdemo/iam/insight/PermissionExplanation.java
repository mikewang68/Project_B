package com.bdemo.iam.insight;

import com.bdemo.iam.role.dto.RoleBrief;

import java.util.List;

/**
 * 单条权限的"为什么有 / 没有"解释。
 */
public class PermissionExplanation {

    private String permissionCode;
    /** true=拥有；false=未拥有 */
    private boolean owned;
    private List<RoleBrief> sourceRoles;
    /** 未拥有时的原因 */
    private String reason;

    public PermissionExplanation() {
    }

    public PermissionExplanation(String permissionCode, boolean owned, List<RoleBrief> sourceRoles, String reason) {
        this.permissionCode = permissionCode;
        this.owned = owned;
        this.sourceRoles = sourceRoles;
        this.reason = reason;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(String permissionCode) {
        this.permissionCode = permissionCode;
    }

    public boolean isOwned() {
        return owned;
    }

    public void setOwned(boolean owned) {
        this.owned = owned;
    }

    public List<RoleBrief> getSourceRoles() {
        return sourceRoles;
    }

    public void setSourceRoles(List<RoleBrief> sourceRoles) {
        this.sourceRoles = sourceRoles;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
