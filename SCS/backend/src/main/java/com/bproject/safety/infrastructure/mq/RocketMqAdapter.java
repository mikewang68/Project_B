package com.bproject.safety.infrastructure.mq;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 独立 Adapter（不使用第三方 spring-boot-starter，避免 Jakarta 兼容问题）。
 *
 * <p>本阶段只建立连接与 Producer/Admin 基础能力：
 * <ul>
 *   <li>Producer 启动后保持连接，供后续业务阶段发送消息，本阶段不主动发送任何业务消息；</li>
 *   <li>Admin 懒加载，供健康探针查询 NameServer/Broker 集群信息。</li>
 * </ul>
 * 启动时 NameServer 不可达不阻断应用启动（健康检查会持续报 DOWN）。
 */
@Component
@EnableConfigurationProperties(RocketMqProperties.class)
@ConditionalOnProperty(prefix = "app.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqAdapter {
    private static final Logger log = LoggerFactory.getLogger(RocketMqAdapter.class);

    private final RocketMqProperties properties;
    private DefaultMQProducer producer;
    private volatile DefaultMQAdminExt admin;
    private volatile boolean producerStarted;

    public RocketMqAdapter(RocketMqProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        try {
            producer = new DefaultMQProducer(properties.producerGroup());
            producer.setNamesrvAddr(properties.nameServer());
            producer.setSendMsgTimeout((int) properties.timeoutMs());
            producer.setInstanceName("safety-producer-" + UUID.randomUUID());
            producer.start();
            producerStarted = true;
            log.info("RocketMQ Producer 已启动 nameServer={} group={}",
                    properties.nameServer(), properties.producerGroup());
        } catch (Exception ex) {
            // 不阻断启动：server 部署时由 /health/ready 暴露 DOWN
            log.warn("RocketMQ Producer 启动失败，将由健康检查持续探测 nameServer={} cause={}",
                    properties.nameServer(), ex.toString());
        }
    }

    /** 懒加载 Admin（首次健康检查时创建），失败向调用方抛出。 */
    public synchronized DefaultMQAdminExt admin() throws Exception {
        if (admin == null) {
            DefaultMQAdminExt instance = new DefaultMQAdminExt();
            instance.setNamesrvAddr(properties.nameServer());
            instance.setVipChannelEnabled(false);
            instance.setInstanceName("safety-admin-" + UUID.randomUUID());
            instance.start();
            admin = instance;
        }
        return admin;
    }

    public boolean isProducerStarted() {
        return producerStarted;
    }

    public DefaultMQProducer producer() {
        return producer;
    }

    @PreDestroy
    void shutdown() {
        closeQuietly(producer);
        closeQuietly(admin);
    }

    private void closeQuietly(Object mqClient) {
        if (mqClient == null) {
            return;
        }
        try {
            mqClient.getClass().getMethod("shutdown").invoke(mqClient);
        } catch (Exception ex) {
            log.warn("RocketMQ 客户端关闭异常: {}", ex.toString());
        }
    }
}
