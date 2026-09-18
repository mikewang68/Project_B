package com.bdemo.iam.user.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AssignRolesRequest(
        @NotEmpty(message = "请至少选择一个角色") List<String> roleIds) {
}
