package com.bdemo.iam.auth.dto;

import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.user.domain.User;

import java.util.List;

public record LoginResponse(
        String token,
        User user,
        List<Role> roles,
        List<String> permissions) {
}
