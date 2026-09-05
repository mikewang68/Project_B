package com.bproject.safety.infrastructure.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 连接配置。服务器地址只来自环境变量 ROCKETMQ_NAMESRV_ADDR。
 *
 * @param enabled       是否启用（dev/test 默认关闭，server profile 由环境变量开启）
 * @param nameServer    NameServer 地址，如 10.0.0.4:9876;10.0.0.5:9876
 * @param producerGroup Producer 组名（本阶段只建立连接，不发送业务消息）
 * @param timeoutMs     管理/发送超时（毫秒）
 */
@ConfigurationProperties(prefix = "app.rocketmq")
public record RocketMqProperties(
        boolean enabled,
        String nameServer,
        String producerGroup,
        long timeoutMs) {

    public RocketMqProperties {
        if (producerGroup == null || producerGroup.isBlank()) {
            producerGroup = "safety-control-demo-producer";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 3000;
        }
    }
}
