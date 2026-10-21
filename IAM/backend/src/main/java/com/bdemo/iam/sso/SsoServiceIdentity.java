package com.bdemo.iam.sso;

import com.bdemo.iam.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 目标后端的**服务身份**校验（V1.1）。
 *
 * <p>问题：V1 的 {@code /verify-and-consume} 仅靠票据签名鉴权。任何拿到票据的人（哪怕是浏览器）
 * 都能把票换成身份断言，等于把"谁能消费"与"票是谁的"混为一谈。
 *
 * <p>V1.1：消费端点要求调用方提供**独立的服务凭据**（{@code X-SSO-Service-Token}），
 * 且该凭据只被授予某一个受众。凭据与票据 {@code aud} 不匹配即 403。
 *
 * <p>配置方式（{@code app.sso.service-tokens}）：逗号分隔的 {@code audience=token} 列表，
 * 例如 {@code wms=<32+字符随机串>,scs=<...>}。未配置任何服务凭据时，
 * 消费端点整体 fail-closed（拒绝所有调用）。
 *
 * <p>凭据比较使用常量时间比较，避免时序侧信道；凭据值不写日志、不回显。
 */
@Service
public class SsoServiceIdentity {

  private final Map<String, byte[]> byAudience = new HashMap<>();

  public SsoServiceIdentity(AppProperties properties) {
    String raw = properties.getSso().getServiceTokens();
    if (raw == null || raw.isBlank()) {
      return; // 未配置：byAudience 为空，consume 调用全部拒绝
    }
    for (String entry : raw.split(",")) {
      String e = entry.trim();
      if (e.isEmpty()) continue;
      int eq = e.indexOf('=');
      if (eq <= 0) continue;
      String aud = e.substring(0, eq).trim().toLowerCase();
      String token = e.substring(eq + 1).trim();
      if (aud.isEmpty() || token.length() < 24) continue;
      byAudience.put(aud, token.getBytes(StandardCharsets.UTF_8));
    }
  }

  /** 是否至少配置了一个服务凭据。 */
  public boolean configured() {
    return !byAudience.isEmpty();
  }

  /**
   * 校验服务凭据是否对应该受众。
   *
   * @return true 仅当凭据存在、非空、且与该 audience 登记值常量时间相等
   */
  public boolean authorize(String audience, String presentedToken) {
    if (audience == null || presentedToken == null) return false;
    byte[] expected = byAudience.get(audience.trim().toLowerCase());
    if (expected == null || expected.length == 0) return false;
    byte[] actual = presentedToken.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, actual);
  }

  /** 已配置的受众列表（只返回名字，不含凭据）。 */
  public java.util.Set<String> audiences() {
    return byAudience.keySet();
  }
}
