package com.bdemo.iam.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 后端接口权限校验注解。value 为权限编码（如 iam:user:add:add）；
 * 超级管理员放行，未认证返回 401，已认证但无权限返回 403。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    String[] value();
}
