package com.bdemo.iam.user.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record UserUpsertRequest(
        String username,
        @NotBlank(message = "请输入姓名") String name,
        String phone,
        String email,
        String password,
        List<String> roleIds,
        List<String> orgCodes,
        String status,
        String blockchainId,
        String blockchainAddress) {
}
