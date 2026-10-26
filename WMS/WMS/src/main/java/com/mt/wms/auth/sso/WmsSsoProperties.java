package com.mt.wms.auth.sso;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * WMS 侧可信 SSO 配置。
 *
 * <p>WMS 不持有票据签名密钥：票据由 IAM 集中验签并一次性消费（契约 V1.1）。
 * WMS 只持有**服务身份凭据**，用于调用 IAM 的 {@code /auth/sso/verify-and-consume}。
 *
 * <p>未配置 {@code wms.sso.service-token} 或 {@code wms.sso.iam-base-url} 时，
 * SSO 端点整体关闭（fail-closed）：返回 503 而不是放行。
 */
@Component
public class WmsSsoProperties {

  /** IAM 基础地址，例如 http://127.0.0.1:18380/api/v1/iam */
  @Value("${wms.sso.iam-base-url:}")
  private String iamBaseUrl;

  /** 服务身份凭据（与服务票据的 aud 绑定），绝不写日志。 */
  @Value("${wms.sso.service-token:}")
  private String serviceToken;

  /** 票据受众，固定 wms。 */
  @Value("${wms.sso.audience:wms}")
  private String audience;

  /** 调用 IAM 的超时（毫秒）。 */
  @Value("${wms.sso.timeout-ms:5000}")
  private int timeoutMs;

  /** 是否启用。显式开关，默认关闭，避免误开。 */
  @Value("${wms.sso.enabled:false}")
  private boolean enabled;

  /**
   * 允许调用 {@code /sso/exchange} 的来源地址（逗号分隔的 IP 或 CIDR）。
   *
   * <p>止血用：该端点接受票据后直接建立会话，一旦签票密钥泄露，
   * 任何能访问本端口的人都能换取任意用户会话。默认只允许本机与回环，
   * 生产应加入 BFF 所在主机 IP。
   *
   * <p>空字符串表示拒绝全部来源（fail-closed）。
   */
  @Value("${wms.sso.exchange-allowed-cidrs:127.0.0.1/32,::1/128}")
  private String exchangeAllowedCidrs;

  /**
   * 可信反代地址（逗号分隔的 IP/CIDR）。**只有**当直连 peer 落在这个列表里时，
   * 才采信它追加的 {@code X-Forwarded-For}。为空表示永不采信 XFF，
   * 一律使用 {@code getRemoteAddr()}。
   *
   * <p>这是防止"直连时自带 X-Forwarded-For: 127.0.0.1"冒充本机的关键。
   */
  @Value("${wms.sso.trusted-proxies:}")
  private String trustedProxies;

  public boolean isUsable() {
    return enabled
        && iamBaseUrl != null
        && !iamBaseUrl.isBlank()
        && serviceToken != null
        && serviceToken.length() >= 24
        && "wms".equals(audience)
        && timeoutMs >= 100 && timeoutMs <= 30000;
  }

  public String getIamBaseUrl() {
    return iamBaseUrl;
  }

  public String getServiceToken() {
    return serviceToken;
  }

  public String getAudience() {
    return audience;
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getExchangeAllowedCidrs() {
    return exchangeAllowedCidrs == null ? "" : exchangeAllowedCidrs.trim();
  }

  public String getTrustedProxies() {
    return trustedProxies == null ? "" : trustedProxies.trim();
  }
}
