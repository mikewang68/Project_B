package com.bdemo.iam.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record UserUpsertRequest(
        String username,
        @NotBlank(message = "请输入姓名") String name,
        String phone,
        String email,
        String password,
        List<String> roleIds,
        List<String> orgCodes,
        @Pattern(regexp = "active|disabled", message = "状态值非法") String status,
        String blockchainId,
        String blockchainAddress) {
}
