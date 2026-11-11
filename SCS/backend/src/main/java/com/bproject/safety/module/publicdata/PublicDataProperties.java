package com.bproject.safety.module.publicdata;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 公共底座设备数据接接收配置（董博涵交接包 2026-11-11）。
 * 默认关闭；enabled=true 且隧道可达（Broker 通告 127.0.0.1:39921）时消费器才注册。
 * 密钥经环境变量注入（PUBLIC_DATA_MQ_ACCESS_KEY / PUBLIC_DATA_MQ_SECRET_KEY），严禁写入仓库。
 */
@ConfigurationProperties(prefix = "app.public-data")
public record PublicDataProperties(
        boolean enabled,
        String nameserver,
        String accessKey,
        String secretKey,
        String group,
        String topic,
        String httpBaseUrl,
        String httpToken,
        boolean backfillEnabled,
        int backfillPageSize) {

    public PublicDataProperties {
        if (nameserver == null || nameserver.isBlank()) nameserver = "127.0.0.1:39887";
        if (group == null || group.isBlank()) group = "b-device-committed-scs-v1";
        if (topic == null || topic.isBlank()) topic = "b-device-committed-v1";
        if (httpBaseUrl == null || httpBaseUrl.isBlank()) httpBaseUrl = "http://127.0.0.1:38121";
        if (backfillPageSize <= 0 || backfillPageSize > 100) backfillPageSize = 100;
    }
}
