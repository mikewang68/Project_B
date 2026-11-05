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
        String blockchainAddress,
        String adminLevel,
        Boolean readOnly,
        @Pattern(regexp="^[A-Za-z0-9_-]{1,64}$",message="项目编码无效") String projectId,
        @Pattern(regexp="^[A-Za-z0-9_-]{1,64}$",message="区域编码无效") String regionId,
        @Pattern(regexp="^[A-Za-z0-9_-]{1,32}$",message="子系统编码无效") String subsystemCode) {
    public UserUpsertRequest(String username,String name,String phone,String email,String password,
        List<String> roleIds,List<String> orgCodes,String status,String blockchainId,String blockchainAddress) {
      this(username,name,phone,email,password,roleIds,orgCodes,status,blockchainId,blockchainAddress,null,null,null,null,null);
    }
}
