package com.bdemo.iam.security;

/**
 * 根据用户 ID 加载完整登录态（角色、权限）。由业务层实现，避免安全模块与业务层循环依赖。
 */
public interface LoginUserLoader {

    LoginUser load(String userId);
}
