package com.bproject.safety.infrastructure.cache;

import com.bproject.safety.infrastructure.health.InfrastructureProbe;
import com.bproject.safety.infrastructure.health.ProbeResult;
import java.time.Duration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Kvrocks 探针：Kvrocks 兼容 Redis 协议，直接执行 PING。
 * 同时供后续验证 GET/SET/TTL 基础能力。
 */
@Component
public class KvrocksProbe implements InfrastructureProbe {
    private final RedisConnectionFactory connectionFactory;

    public KvrocksProbe(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String componentName() {
        return "cache";
    }

    @Override
    public ProbeResult probe() {
        try (var connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            return "PONG".equalsIgnoreCase(pong)
                    ? ProbeResult.up("Kvrocks PING PONG")
                    : ProbeResult.down("unexpected PING result: " + pong);
        } catch (RuntimeException ex) {
            return ProbeResult.down(rootCause(ex));
        }
    }

    /** 基础能力自检：SET -> GET -> TTL（仅健康检查使用，不写业务数据）。 */
    public ProbeResult selfCheck() {
        String key = "health:kvrocks:selfcheck";
        try (var connection = connectionFactory.getConnection()) {
            byte[] raw = key.getBytes();
            connection.stringCommands().setEx(raw, 30, "1".getBytes());
            byte[] value = connection.stringCommands().get(raw);
            Long ttl = connection.keyCommands().ttl(raw);
            connection.keyCommands().del(raw);
            if (value != null && ttl != null && ttl > 0) {
                return ProbeResult.up("SET/GET/TTL OK");
            }
            return ProbeResult.down("SET/GET/TTL unexpected result");
        } catch (RuntimeException ex) {
            return ProbeResult.down(rootCause(ex));
        }
    }

    private String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    static Duration defaultCommandTimeout() {
        return Duration.ofSeconds(2);
    }
}
