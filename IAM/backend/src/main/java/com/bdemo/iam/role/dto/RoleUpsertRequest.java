package com.bdemo.iam.role.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record RoleUpsertRequest(
        @NotBlank(message = "请输入角色名称") String name,
        @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "编码需为小写字母开头，仅含小写字母/数字/下划线")
        String code,
        String description,
        List<String> permCodes,
        @Pattern(regexp = "active|inactive", message = "状态值非法") String status) {
}
