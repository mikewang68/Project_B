package com.bdemo.iam.sso;

import com.bdemo.iam.sso.mapper.SsoLogoutMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 统一注销（SLO）的下游通知器。
 *
 * <p><b>为什么需要它</b>：{@code SsoTicketController#revoke} 只撤销"当前登录用户自己还没消费的票据"，
 * 它要求 IAM 登录态、以用户身份调用，因此**不能被业务系统用来撤销已建立的业务会话**。
 * 业务系统需要的是一个服务间通道：IAM 在用户登出时，以服务身份通知各系统"这个 sid 失效了"。
 *
 * <p><b>设计</b>：
 *
 * <ol>
 *   <li>登出时把"待通知"写入 {@code iam.sso_logout_notify}（持久化，进程重启不丢）；
 *   <li>立即尝试投递；失败则留给后台重试；
 *   <li>每个受众使用**该受众自己的**服务令牌（不共用），路径取自配置
 *       {@code app.sso.logout-endpoints[audience]}，因为各系统接口不一定同名；
 *   <li>成功判定 = HTTP 2xx **且** 响应体业务码为成功。HTTP 200 + 业务错误码记为失败。
 * </ol>
 *
 * <p><b>不做什么</b>：不把用户 JWT 当作服务身份；不假设所有系统共用一个路径；
 * 不在投递失败时静默吞掉——失败原因写入 {@code last_error} 并保留可重试状态。
 */
@Service
public class SsoLogoutNotifier {

  private static final Logger log = LoggerFactory.getLogger(SsoLogoutNotifier.class);

  /** 投递结果。 */
  public record Delivery(boolean ok, String error) {}

  private final SsoLogoutMapper mapper;
  private final SsoServiceIdentity services;
  private final ObjectMapper objectMapper;
  private final Map<String, String> endpoints;
  private final Duration timeout;
  private final HttpClient http;
  private final ScheduledExecutorService retryExecutor;

  public SsoLogoutNotifier(
      SsoLogoutMapper mapper,
      SsoServiceIdentity services,
      ObjectMapper objectMapper,
      @Value("${app.sso.logout-endpoints:}") String endpointsSpec,
      @Value("${app.sso.logout-timeout-ms:3000}") long timeoutMs,
      @Value("${app.sso.logout-retry-seconds:60}") long retrySeconds) {
    this.mapper = mapper;
    this.services = services;
    this.objectMapper = objectMapper;
    this.endpoints = parseEndpoints(endpointsSpec);
    this.timeout = Duration.ofMillis(Math.max(500, Math.min(timeoutMs, 10_000)));
    this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();

    // 后台重试：只处理"已到期且未投递"的记录。
    // 用单线程守护调度器；生产多实例部署时应改为分布式锁 + 共享队列。
    this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "sso-logout-retry");
      t.setDaemon(true);
      return t;
    });
    if (retrySeconds > 0) {
      this.retryExecutor.scheduleWithFixedDelay(
          this::retryPendingSafely, retrySeconds, retrySeconds, TimeUnit.SECONDS);
    }
  }

  /**
   * 登记一次待通知，并立即尝试投递。
   *
   * @param sid       IAM 会话 id（= 登录 JWT 的 jti），业务系统按它撤销本地会话
   * @param subject   IAM 稳定主体
   * @param audiences 需要通知的受众（通常是该 sid 下所有签发过的票据受众）
   * @param reason    原因码，如 PORTAL_LOGOUT
   * @return 本次投递成功的受众；失败项已落库并可重试
   */
  public List<String> notifyLogout(String sid, String subject, List<String> audiences, String reason) {
    List<String> delivered = new java.util.ArrayList<>();
    if (sid == null || sid.isBlank()) {
      log.warn("logout notify 跳过：sid 为空");
      return List.of();
    }
    for (String aud : audiences) {
      String id = sid + ":" + aud;
      try {
        mapper.insertPending(id, sid, subject, aud, reason);
      } catch (RuntimeException e) {
        // 幂等：同 (sid,aud) 重复登记不算错误
        log.debug("logout notify 已存在或写入失败: aud={}, err={}", aud, e.getClass().getSimpleName());
      }
      Delivery d = deliver(id, sid, subject, aud);
      if (d.ok()) {
        delivered.add(aud);
      } else {
        log.warn("注销通知投递失败，已登记重试: aud={}, reason={}", aud, d.error());
      }
    }
    return delivered;
  }

  /** 取出到期的待投递记录并逐个重试。 */
  void retryPending() {
    List<SsoLogoutMapper.Pending> pending = mapper.findPending(20);
    for (SsoLogoutMapper.Pending p : pending) {
      Delivery d = deliver(p.id(), p.sessionId(), p.subject(), p.audience());
      if (d.ok()) {
        log.info("注销通知重试成功: aud={}, attempt={}", p.audience(), p.attemptCount() + 1);
      } else {
        mapper.markFailed(p.id(), d.error());
      }
    }
  }

  private void retryPendingSafely() {
    try {
      retryPending();
    } catch (RuntimeException e) {
      log.warn("注销重试任务异常: {}", e.getClass().getSimpleName());
    }
  }

  /** 真正的投递。 */
  private Delivery deliver(String id, String sid, String subject, String aud) {
    mapper.markAttempt(id);

    String endpoint = endpoints.get(aud);
    if (endpoint == null || endpoint.isBlank()) {
      String err = "未配置 " + aud + " 的注销端点（app.sso.logout-endpoints）";
      mapper.markFailed(id, err);
      return new Delivery(false, err);
    }
    String token = services.tokenFor(aud);
    if (token == null || token.isBlank()) {
      String err = "未登记 " + aud + " 的服务身份令牌";
      mapper.markFailed(id, err);
      return new Delivery(false, err);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("iamSessionId", sid);
    payload.put("subject", subject);
    payload.put("audience", aud);
    payload.put("reason", "PORTAL_LOGOUT");

    try {
      String body = objectMapper.writeValueAsString(payload);
      HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
          .timeout(timeout)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("X-SSO-Service-Token", token)
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
          .build();

      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      // 成功判定：HTTP 2xx + 业务码成功。
      // 许多系统用 HTTP 200 承载业务错误，只看状态码会漏判。
      if (response.statusCode() / 100 != 2) {
        String err = "HTTP " + response.statusCode();
        mapper.markFailed(id, err);
        return new Delivery(false, err);
      }
      if (!businessOk(response.body(), objectMapper)) {
        String err = "业务码失败: " + truncate(response.body());
        mapper.markFailed(id, err);
        return new Delivery(false, err);
      }
      mapper.markDelivered(id);
      return new Delivery(true, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      mapper.markFailed(id, "interrupted");
      return new Delivery(false, "interrupted");
    } catch (Exception e) {
      String err = e.getClass().getSimpleName() + ": " + truncate(String.valueOf(e.getMessage()));
      mapper.markFailed(id, err);
      return new Delivery(false, err);
    }
  }

  /**
   * 解析业务成功码。兼容三种常见形态：
   * {@code {"code":0}}、{@code {"code":"0"}}、{@code {"ok":true}}。
   *
   * <p>包级可见 + 静态，便于单测直接覆盖，不必构造完整 Spring 上下文。
   */
  static boolean businessOk(String body, ObjectMapper objectMapper) {
    if (body == null || body.isBlank()) {
      // 空响应体视为成功（部分实现只返回 204/空 200）
      return true;
    }
    try {
      var node = objectMapper.readTree(body);
      if (node.has("ok")) {
        return node.path("ok").asBoolean(false);
      }
      if (node.has("code")) {
        var code = node.get("code");
        if (code.isNumber()) {
          return code.asInt() == 0;
        }
        return "0".equals(code.asText("")) || "OK".equalsIgnoreCase(code.asText(""));
      }
      // 没有已知成功标志：保守判定为失败，避免"HTTP 200 即成功"的误判
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  private static String truncate(String s) {
    if (s == null) {
      return "";
    }
    return s.length() <= 200 ? s : s.substring(0, 200);
  }

  /** 格式：{@code wms=http://host/path;scs=http://host/path} */
  static Map<String, String> parseEndpoints(String spec) {
    Map<String, String> out = new LinkedHashMap<>();
    if (spec == null || spec.isBlank()) {
      return out;
    }
    for (String entry : spec.split(";")) {
      String item = entry.trim();
      if (item.isEmpty()) {
        continue;
      }
      int eq = item.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      out.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
    }
    return out;
  }

  /** 供测试观察当前配置的受众。 */
  public java.util.Set<String> configuredAudiences() {
    return java.util.Set.copyOf(endpoints.keySet());
  }

  /** 供测试关闭后台线程。 */
  void shutdown() {
    retryExecutor.shutdownNow();
  }
}
