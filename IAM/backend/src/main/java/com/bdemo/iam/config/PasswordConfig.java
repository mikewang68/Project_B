package com.bdemo.iam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 独立的密码编码器配置，避免 SecurityConfig（依赖 LoginUserLoader=UserService）
 * 与 UserService（依赖 PasswordEncoder）之间的 Bean 循环依赖。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
