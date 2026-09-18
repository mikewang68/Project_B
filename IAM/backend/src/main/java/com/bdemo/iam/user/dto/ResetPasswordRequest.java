package com.bdemo.iam.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "请输入新密码")
        @Size(min = 6, message = "密码至少 6 位")
        String password) {
}
