package com.bdemo.iam.auth.dto;

import com.bdemo.iam.role.domain.Role;
import com.bdemo.iam.user.domain.User;

import java.util.List;

public record LoginResponse(
        String token,
        User user,
        List<Role> roles,
        List<String> permissions,
        boolean superAdmin,
        boolean readOnly,
        String accessMode,
        com.bproject.iam.context.UserContext userContext) {
    public LoginResponse(String token,User user,List<Role> roles,List<String> permissions,
        boolean superAdmin,boolean readOnly,String accessMode) {
      this(token,user,roles,permissions,superAdmin,readOnly,accessMode,null);
    }
}
