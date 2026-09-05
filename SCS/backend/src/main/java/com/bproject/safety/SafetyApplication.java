package com.bproject.safety;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 装卸作业安全卡控系统 Backend Demo 启动类。
 *
 * <p>模块化单体：node4 / node5 运行同一份 JAR。本阶段只包含公共能力与基础设施连接，
 * 不包含告警、AI、人员、设备、规则等业务模块。</p>
 */
@SpringBootApplication
public class SafetyApplication {
    public static void main(String[] args) {
        SpringApplication.run(SafetyApplication.class, args);
    }
}
