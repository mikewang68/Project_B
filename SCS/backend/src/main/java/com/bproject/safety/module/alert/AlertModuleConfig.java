package com.bproject.safety.module.alert;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 告警模块基础配置：提供统一 Clock（固定 Asia/Shanghai），
 * 业务代码不直接 {@link Clock#systemDefaultZone()}，便于测试注入固定时钟。
 */
@Configuration
public class AlertModuleConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock safetyClock() {
        return Clock.system(ZONE);
    }
}
