package com.bproject.safety.module.publicdata.mq;

import com.bproject.safety.module.publicdata.PublicDataProperties;
import com.bproject.safety.module.publicdata.service.PublicInboxProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.acl.common.AclClientRPCHook;
import org.apache.rocketmq.acl.common.SessionCredentials;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 公共底座已提交事件消费器（b-device-committed-v1 / 组 b-device-committed-scs-v1，只读身份）。
 * 可靠语义：broker ACK 前事件已落收件箱并完成业务映射（同事务）；映射失败保留 FAILED 行，
 * 不无限重投（S3 retry_count 约束），补偿走收件箱 FAILED 行重跑。
 */
@Component
@ConditionalOnProperty(prefix = "app.public-data", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PublicDataProperties.class)
public class PublicDataConsumer {

    private static final Logger log = LoggerFactory.getLogger(PublicDataConsumer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PublicDataProperties properties;
    private final PublicInboxProcessor processor;
    private DefaultMQPushConsumer consumer;

    public PublicDataConsumer(PublicDataProperties properties, PublicInboxProcessor processor) {
        this.properties = properties;
        this.processor = processor;
    }

    @PostConstruct
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer(properties.group(),
                    new AclClientRPCHook(new SessionCredentials(properties.accessKey(), properties.secretKey())),
                    new AllocateMessageQueueAveragely());
            consumer.setNamesrvAddr(properties.nameserver());
            consumer.setVipChannelEnabled(false);
            consumer.setInstanceName("scs-public-consumer");
            consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            consumer.setConsumeThreadMin(1);
            consumer.setConsumeThreadMax(1);
            consumer.setConsumeMessageBatchMaxSize(1);
            consumer.subscribe(properties.topic(), "*");
            consumer.registerMessageListener((MessageListenerConcurrently) this::onMessage);
            consumer.start();
            log.info("公共数据消费器已启动 group={} topic={} nameserver={}",
                    properties.group(), properties.topic(), properties.nameserver());
        } catch (Exception e) {
            log.error("公共数据消费器启动失败（服务继续运行，修复后重启生效）: {}", e.getMessage());
            consumer = null;
        }
    }

    private ConsumeConcurrentlyStatus onMessage(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt message : messages) {
            try {
                handle(message.getBody());
            } catch (Exception e) {
                log.error("公共事件处理失败（保留收件箱状态，待补偿重跑）: {}", e.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    void handle(byte[] bytes) throws Exception {
        JsonNode envelope = JSON.readTree(bytes);
        if (!"b.device-committed.v1".equals(envelope.path("schema_version").asText())
                || !envelope.path("simulated").asBoolean()) {
            log.warn("非公共已提交信封，忽略 schema={}", envelope.path("schema_version").asText());
            return;
        }
        String eventId = envelope.path("event_id").asText();
        if (eventId.isBlank()) {
            log.warn("公共事件缺少 event_id，忽略");
            return;
        }
        processor.ensureInbox(eventId,
                envelope.path("committed_ms").asLong(0),
                envelope.path("wire_sha256").asText(null),
                envelope.path("event"));
        try {
            processor.processMapped(eventId, envelope);
        } catch (Exception e) {
            // 失败原因保留在收件箱（REQUIRES_NEW 独立提交），消息按 broker 重投上限进 DLQ；
            // 修复后经 FAILED 行重跑补偿（processMapped 对 FAILED 状态自动重映射）
            processor.markFailed(eventId, e.getMessage());
            throw e;
        }
    }

    @PreDestroy
    public void stop() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
