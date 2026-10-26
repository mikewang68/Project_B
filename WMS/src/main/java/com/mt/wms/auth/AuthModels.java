package com.mt.wms.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

public final class AuthModels {
    private AuthModels() {
    }

    public record LoginRequest(
            @NotBlank(message = "公司不能为空") String company,
            @NotBlank(message = "账号不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "原密码不能为空") String currentPassword,
            @NotBlank(message = "新密码不能为空")
            @Size(min = 8, max = 72, message = "新密码长度必须为8到72位") String newPassword) {
    }

    public record TenantSwitchRequest(
            @NotBlank(message = "仓库代码不能为空") String warehouseCode,
            @NotBlank(message = "货主代码不能为空") String ownerCode) {
    }

    public record TenantOption(Long id, String code, String name) {
    }

    public record MenuItem(String code, String parentCode, String name, String path, String icon, int sortOrder) {
    }

    public record TenantView(
            String companyCode,
            String companyName,
            TenantOption currentWarehouse,
            TenantOption currentOwner,
            List<TenantOption> warehouses,
            List<TenantOption> owners) {
    }

    public record CurrentUserView(
            Long id,
            String username,
            String displayName,
            String companyCode,
            String companyName,
            List<String> roles,
            Set<String> permissions,
            boolean passwordChangeRequired,
            List<MenuItem> menus,
            TenantView tenant) {
    }

    public record CsrfView(String headerName, String parameterName, String token) {
    }
}
