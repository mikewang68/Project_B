package com.bproject.safety.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SCS 的 JWT 验签器（JDK 实现，无第三方依赖）。
 *
 * <p>修复的缺陷：旧实现只做 Base64 解码、不验签、接受 {@code alg:none}、不校验
 * {@code iss}/{@code aud}。
 *
 * <p>G5 加固（相对 G4 版本）：
 *
 * <ul>
 *   <li><b>算法与密钥长度绑定</b>：HS256/384/512 分别要求 ≥32/48/64 字节（RFC 7518 §3.2），
 *       避免"头里声明 HS512、实际用 32 字节密钥"的降级。
 *   <li><b>强制 required claims</b>：{@code exp}、{@code sub}、{@code aud} 缺失即拒，
 *       不接受"没写 exp 就永不过期"。
 *   <li><b>iss 不可选配</b>：生产路径必须给出 expectedIssuer，空值直接构造失败。
 *       原先"配置为空则跳过"的分支会允许任意签发方。
 *   <li><b>拒绝超长 token</b>：上限 8 KiB，避免用超大 token 消耗解析资源。
 *   <li>保持常量时间签名比较、{@code nbf}/{@code exp} 校验与 0–300 秒时钟偏移上限。
 * </ul>
 *
 * <p>受众（{@code aud}）语义仍由 {@code SecurityInterceptor} 判定 200/403：
 * 本类只保证 {@code aud} <b>存在</b>且是字符串，不做取值匹配。
 */
public final class JwtVerifier {

  /** 校验失败。消息中不含 token 原文与密钥。 */
  public static class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
      super(message);
    }
  }

  /** RFC 7518 §3.2：HS 系列密钥长度不得小于哈希输出长度。 */
  private static final int MIN_SECRET_BYTES = 32;
  private static final int MAX_TOKEN_CHARS = 8 * 1024;

  private final byte[] secret;
  private final String expectedIssuer;
  private final long clockSkewSeconds;
  private final ObjectMapper mapper;

  public JwtVerifier(
      String secret, String expectedIssuer, long clockSkewSeconds, ObjectMapper mapper) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException("SCS JWT 验签密钥未配置；认证不可用（fail-closed）");
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "SCS JWT 验签密钥长度不足 " + MIN_SECRET_BYTES + " 字节；认证不可用（fail-closed）");
    }
    if (expectedIssuer == null || expectedIssuer.isBlank()) {
      // 生产 issuer 不允许"选配"：留空等于接受任何签发方。
      throw new IllegalStateException(
          "SCS JWT issuer 未配置；拒绝在无法验证签发方的情况下启用验签（fail-closed）");
    }
    this.secret = bytes;
    this.expectedIssuer = expectedIssuer.trim();
    this.clockSkewSeconds = Math.max(0, Math.min(clockSkewSeconds, 300));
    this.mapper = mapper;
  }

  /** 验签并返回 payload。任何一项不满足即抛 {@link InvalidTokenException}。 */
  public JsonNode verify(String token) {
    if (token == null || token.isBlank()) {
      throw new InvalidTokenException("token 为空");
    }
    if (token.length() > MAX_TOKEN_CHARS) {
      throw new InvalidTokenException("token 过长");
    }
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new InvalidTokenException("token 不是三段式 JWS");
    }

    JsonNode header;
    JsonNode payload;
    try {
      header = mapper.readTree(Base64.getUrlDecoder().decode(pad(parts[0])));
      payload = mapper.readTree(Base64.getUrlDecoder().decode(pad(parts[1])));
    } catch (Exception e) {
      throw new InvalidTokenException("token 头或载荷不是合法 JSON");
    }

    String alg = header.path("alg").asText("");
    if (alg.isBlank()) {
      throw new InvalidTokenException("token 缺少 alg");
    }
    if ("none".equalsIgnoreCase(alg)) {
      throw new InvalidTokenException("拒绝 alg=none");
    }
    if (!Set.of("HS256", "HS384", "HS512").contains(alg)) {
      throw new InvalidTokenException("不支持的算法: " + alg);
    }
    requireKeyStrengthFor(alg);

    byte[] expected = hmac(alg, (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    byte[] actual;
    try {
      actual = Base64.getUrlDecoder().decode(pad(parts[2]));
    } catch (Exception e) {
      throw new InvalidTokenException("签名不是合法 Base64Url");
    }
    if (!MessageDigest.isEqual(expected, actual)) {
      throw new InvalidTokenException("签名验证失败");
    }

    // ---- required claims ----
    if (!payload.hasNonNull("exp") || payload.get("exp").asLong(0) <= 0) {
      throw new InvalidTokenException("缺少 exp");
    }
    if (payload.path("sub").asText("").isBlank()) {
      throw new InvalidTokenException("缺少 sub");
    }
    if (payload.path("iss").asText("").isBlank()) {
      throw new InvalidTokenException("缺少 iss");
    }
    if (payload.path("aud").isMissingNode() || payload.path("aud").isNull()) {
      throw new InvalidTokenException("缺少 aud");
    }

    long now = Instant.now().getEpochSecond();
    if (now - clockSkewSeconds > payload.get("exp").asLong()) {
      throw new InvalidTokenException("token 已过期");
    }
    if (payload.hasNonNull("nbf")) {
      long nbf = payload.get("nbf").asLong(0);
      if (nbf > 0 && now + clockSkewSeconds < nbf) {
        throw new InvalidTokenException("token 尚未生效");
      }
    }
    if (!expectedIssuer.equals(payload.path("iss").asText(""))) {
      throw new InvalidTokenException("issuer 不匹配");
    }
    return payload;
  }

  /** RFC 7518 §3.2：密钥长度不得小于对应哈希的输出长度。 */
  private void requireKeyStrengthFor(String alg) {
    int required =
        switch (alg) {
          case "HS256" -> 32;
          case "HS384" -> 48;
          case "HS512" -> 64;
          default -> throw new InvalidTokenException("不支持的算法: " + alg);
        };
    if (secret.length < required) {
      throw new InvalidTokenException(
          "密钥长度不足以支持 " + alg + "（需要 " + required + " 字节）");
    }
  }

  private byte[] hmac(String alg, byte[] signingInput) {
    String jca =
        switch (alg) {
          case "HS256" -> "HmacSHA256";
          case "HS384" -> "HmacSHA384";
          case "HS512" -> "HmacSHA512";
          default -> throw new InvalidTokenException("不支持的算法: " + alg);
        };
    try {
      Mac mac = Mac.getInstance(jca);
      mac.init(new SecretKeySpec(secret, jca));
      return mac.doFinal(signingInput);
    } catch (Exception e) {
      throw new InvalidTokenException("签名计算失败");
    }
  }

  /** Base64Url 去掉 padding，解码前补回。 */
  private static String pad(String s) {
    int rem = s.length() % 4;
    return rem == 0 ? s : s + "====".substring(rem);
  }
}
