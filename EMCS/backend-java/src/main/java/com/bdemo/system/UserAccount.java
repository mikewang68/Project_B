package com.bdemo.system;

public record UserAccount(
        long userId,
        Long deptId,
        String userName,
        String nickName,
        String avatar,
        String password,
        String status,
        String delFlag) {
}
