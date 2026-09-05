package com.mt.wms.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public final class UserAdminModels {
    private UserAdminModels() {
    }

    public record UserView(Long id, String username, String mobile, String displayName, String status,
                           boolean loginEnabled, boolean passwordChangeRequired, List<String> roles,
                           List<String> warehouses, List<String> owners) {
    }

    public record SaveUserRequest(
            @NotBlank(message = "用户名不能为空") String username,
            String mobile,
            @NotBlank(message = "姓名不能为空") String displayName,
            String password,
            String status,
            Boolean loginEnabled,
            @NotEmpty(message = "至少选择一个角色") List<String> roleCodes,
            @NotEmpty(message = "至少选择一个仓库") List<String> warehouseCodes,
            @NotEmpty(message = "至少选择一个货主") List<String> ownerCodes) {
    }

    public record RoleOption(String code, String name) {
    }
}
