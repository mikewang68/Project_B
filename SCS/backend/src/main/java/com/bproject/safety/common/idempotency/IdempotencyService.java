package com.bproject.safety.common.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 基于 Kvrocks（Redis 协议）的通用幂等服务。
 *
 * <p>前端对所有非 GET 请求自动携带 Idempotency-Key；多实例部署时，
 * 不能使用 JVM 内存做去重，因此以 Kvrocks 为共享存储：</p>
 * <pre>
 *   idempotency:{key} = PROCESSING | 结果摘要，带 TTL
 * </pre>
 * 本阶段只提供通用基础能力（业务写接口在后续阶段接入）。
 * Kvrocks 不可用时降级为直接执行，不阻断业务。
 */
@Service
public class IdempotencyService {
    public static final String KEY_PREFIX = "idempotency:";
    public static final String PROCESSING = "PROCESSING";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public IdempotencyService(StringRedisTemplate redis,
                              @Value("${app.idempotency.ttl-seconds:600}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** 已完成的重复请求：返回上次结果摘要；处理中或不存在返回 empty。 */
    public Optional<String> getCompletedResult(String key) {
        if (isBlank(key)) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(storageKey(key));
            if (value == null || PROCESSING.equals(value)) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (RuntimeException ex) {
            log.warn("Kvrocks 读取幂等键失败，降级放行 key={} cause={}", key, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * 尝试占用幂等键。
     *
     * @return true=首次占用成功；false=键已存在（重复请求或并发请求）
     */
    public boolean acquire(String key) {
        if (isBlank(key)) {
            return true;
        }
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(storageKey(key), PROCESSING, ttl);
            return !Boolean.FALSE.equals(ok);
        } catch (RuntimeException ex) {
            log.warn("Kvrocks 写入幂等键失败，降级放行 key={} cause={}", key, ex.toString());
            return true;
        }
    }

    /** 请求成功完成：写入结果摘要并续期 TTL。 */
    public void complete(String key, String resultSummary) {
        if (isBlank(key)) {
            return;
        }
        try {
            redis.opsForValue().set(storageKey(key),
                    resultSummary == null ? "OK" : resultSummary, ttl);
        } catch (RuntimeException ex) {
            log.warn("Kvrocks 写入幂等结果失败 key={} cause={}", key, ex.toString());
        }
    }

    /** 请求执行失败：删除占位，允许客户端重试。 */
    public void release(String key) {
        if (isBlank(key)) {
            return;
        }
        try {
            redis.delete(storageKey(key));
        } catch (RuntimeException ex) {
            log.warn("Kvrocks 释放幂等键失败 key={} cause={}", key, ex.toString());
        }
    }

    /**
     * 通用幂等执行模板：相同 key 只真正执行一次 action，重复请求直接返回首次结果摘要。
     * 处理中的并发重复请求抛出 IllegalStateException（由上层映射为 409）。
     */
    public String process(String key, Supplier<String> action) {
        if (isBlank(key)) {
            return action.get();
        }
        Optional<String> cached = getCompletedResult(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (!acquire(key)) {
            throw new IllegalStateException("相同 Idempotency-Key 的请求正在处理中: " + key);
        }
        try {
            String result = action.get();
            complete(key, result);
            return result;
        } catch (RuntimeException ex) {
            release(key);
            throw ex;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
    private String storageKey(String key) {
        var context=com.bproject.safety.config.ScsCentralScope.current();
        if(context==null)return KEY_PREFIX+key;
        // L1_SUPER 平台总管理员（GLOBAL/SCOPED 均豁免唯一 scope）：键按用户+URI 维度隔离（与 admin 种子账号行为一致）
        if(context.adminLevel().equals("L1_SUPER")&&context.scopeMode()!=com.bproject.iam.context.ScopeSql.Mode.DENY)
          return KEY_PREFIX+"v2:global:"+context.userId()+":"+key;
        var tuple=com.bproject.safety.config.ScsCentralScope.writeScope();
        var attrs=(org.springframework.web.context.request.ServletRequestAttributes)org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        var request=attrs.getRequest();
        String identity=context.userId()+"\n"+tuple.projectId()+"\n"+tuple.regionId()+"\n"+tuple.subsystemCode()+"\n"+request.getMethod()+"\n"+request.getRequestURI()+"\n"+key;
        try{return KEY_PREFIX+"v2:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
