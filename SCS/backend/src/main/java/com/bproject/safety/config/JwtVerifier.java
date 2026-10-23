package com.bproject.safety.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SCS 的 JWT 验签器（JDK 实现，无第三方依赖）。
 *
 * <p>G5 加固（HS 通道，沿用）：算法与密钥长度绑定、强制 required claims（exp/sub/iss/aud）、
 * issuer 不可选配、拒绝超长 token、常量时间比较、nbf/exp 与 0–300 秒时钟偏移上限。
 *
 * <p>G6 增强（P1-1，IAM RS256 对接）：
 *
 * <ul>
 *   <li><b>非对称验签</b>：支持 RS256/RS384/RS512/ES256，公钥经 PEM
 *       （{@code app.security.jwt-public-key-pem}，支持字面 {@code \n}）或 JWKS 端点
 *       （{@code app.security.jwt-jwks-url}，按 {@code kid} 选择密钥）注入；JWKS 支持密钥轮换
 *       （TTL 缓存 + 未知 kid 触发限流刷新）。</li>
 *   <li><b>算法白名单配置化</b>（{@code app.security.jwt-allowed-algorithms}）：
 *       白名单外算法一律拒绝。</li>
 *   <li><b>算法混淆结构性免疫</b>：HS 家族只使用配置的对称密钥、RS/ES 家族只走
 *       {@link Signature} 公钥验签，两条路径无共享密钥材料——"拿公钥当 HMAC 秘钥"
 *       的经典混淆攻击在本实现中不可能成立。</li>
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

  private static final Set<String> HS_ALGS = Set.of("HS256", "HS384", "HS512");
  private static final Set<String> RSA_ALGS = Set.of("RS256", "RS384", "RS512");
  private static final Set<String> SUPPORTED_ALGS =
      Set.of("HS256", "HS384", "HS512", "RS256", "RS384", "RS512", "ES256");

  /** JWKS 缓存 TTL 与未知 kid 触发刷新的最小间隔（防刷新风暴）。 */
  private static final long JWKS_CACHE_TTL_MS = 15 * 60 * 1000L;
  private static final long JWKS_REFRESH_MIN_INTERVAL_MS = 30 * 1000L;

  private final byte[] secret;
  private final String expectedIssuer;
  private final long clockSkewSeconds;
  private final ObjectMapper mapper;
  private final Set<String> allowedAlgorithms;
  private final PublicKey staticPublicKey;
  private final String jwksUrl;
  private final HttpClient httpClient;
  private volatile Map<String, PublicKey> jwksCache = Map.of();
  private volatile long jwksFetchedAt = 0;
  private volatile long jwksLastRefreshAttempt = 0;

  /** 兼容构造器（HS-only）：既有调用与测试保持不变。 */
  public JwtVerifier(
      String secret, String expectedIssuer, long clockSkewSeconds, ObjectMapper mapper) {
    this(secret, expectedIssuer, clockSkewSeconds, mapper,
        Set.of("HS256", "HS384", "HS512"), null, null);
  }

  /**
   * 全参构造器（fail-closed）：
   * 白名单含 HS 系列则必须提供对称密钥；含 RS 或 ES 系列则必须提供 PEM 或 JWKS 之一；
   * issuer 始终必填。静态 PEM 解析失败在构造期即抛出（启动期暴露，而非逐请求失败）。
   */
  public JwtVerifier(
      String secret,
      String expectedIssuer,
      long clockSkewSeconds,
      ObjectMapper mapper,
      Set<String> allowedAlgorithms,
      String publicKeyPem,
      String jwksUrl) {
    if (expectedIssuer == null || expectedIssuer.isBlank()) {
      // 生产 issuer 不允许"选配"：留空等于接受任何签发方。
      throw new IllegalStateException(
          "SCS JWT issuer 未配置；拒绝在无法验证签发方的情况下启用验签（fail-closed）");
    }
    if (allowedAlgorithms == null || allowedAlgorithms.isEmpty()) {
      throw new IllegalStateException("SCS JWT 算法白名单为空（fail-closed）");
    }
    for (String alg : allowedAlgorithms) {
      if (!SUPPORTED_ALGS.contains(alg)) {
        throw new IllegalStateException("SCS JWT 不支持的算法进入白名单: " + alg);
      }
    }
    boolean needsHmac = allowedAlgorithms.stream().anyMatch(HS_ALGS::contains);
    boolean needsAsym = allowedAlgorithms.stream().anyMatch(a -> !HS_ALGS.contains(a));
    boolean hasHmac = secret != null && !secret.isBlank();
    String pem = publicKeyPem == null ? "" : publicKeyPem.trim();
    String jwks = jwksUrl == null ? "" : jwksUrl.trim();
    boolean hasAsym = !pem.isBlank() || !jwks.isBlank();

    if (needsHmac && !hasHmac) {
      throw new IllegalStateException(
          "SCS JWT 白名单含 HS 系列但对称密钥未配置；认证不可用（fail-closed）");
    }
    if (hasHmac) {
      byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
      if (bytes.length < MIN_SECRET_BYTES) {
        throw new IllegalStateException(
            "SCS JWT 验签密钥长度不足 " + MIN_SECRET_BYTES + " 字节；认证不可用（fail-closed）");
      }
      this.secret = bytes;
    } else {
      this.secret = null;
    }
    if (needsAsym && !hasAsym) {
      throw new IllegalStateException(
          "SCS JWT 白名单含 RS/ES 系列但公钥（PEM / JWKS）未配置；认证不可用（fail-closed）");
    }
    if (!needsAsym && hasAsym) {
      // 显式拒绝"配了公钥但白名单不含非对称算法"的半截配置
      throw new IllegalStateException(
          "SCS JWT 配置了公钥但算法白名单不含 RS/ES 系列（fail-closed）");
    }
    if (!pem.isBlank()) {
      try {
        this.staticPublicKey = parsePemPublicKey(pem);
      } catch (Exception e) {
        throw new IllegalStateException("SCS JWT 公钥 PEM 解析失败: " + e.getMessage());
      }
    } else {
      this.staticPublicKey = null;
    }
    this.jwksUrl = jwks.isBlank() ? null : jwks;
    this.httpClient =
        this.jwksUrl == null
            ? null
            : HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
    this.expectedIssuer = expectedIssuer.trim();
    this.clockSkewSeconds = Math.max(0, Math.min(clockSkewSeconds, 300));
    this.mapper = mapper;
    this.allowedAlgorithms = Set.copyOf(allowedAlgorithms);
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
    if (!allowedAlgorithms.contains(alg)) {
      throw new InvalidTokenException("算法不在白名单: " + alg);
    }

    byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
    byte[] actual;
    try {
      actual = Base64.getUrlDecoder().decode(pad(parts[2]));
    } catch (Exception e) {
      throw new InvalidTokenException("签名不是合法 Base64Url");
    }

    if (HS_ALGS.contains(alg)) {
      verifyHmac(alg, signingInput, actual);
    } else {
      String kid = header.path("kid").asText("");
      verifyAsymmetric(alg, kid.isBlank() ? null : kid, signingInput, actual);
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

  // ---------- 对称（HS 家族）：仅使用配置密钥，与公钥路径零共享 ----------

  private void verifyHmac(String alg, byte[] signingInput, byte[] actual) {
    if (secret == null) {
      throw new InvalidTokenException("HS 密钥未配置，拒绝 HS 家族 token");
    }
    requireKeyStrengthFor(alg);
    byte[] expected = hmac(alg, signingInput);
    if (!MessageDigest.isEqual(expected, actual)) {
      throw new InvalidTokenException("签名验证失败");
    }
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

  // ---------- 非对称（RS / ES 家族）：仅使用公钥 Signature 验签 ----------

  private void verifyAsymmetric(String alg, String kid, byte[] signingInput, byte[] actual) {
    PublicKey key = resolvePublicKey(alg, kid);
    if (key == null) {
      throw new InvalidTokenException("未找到匹配的验签公钥");
    }
    try {
      Signature sig = Signature.getInstance(jcaAlgorithm(alg));
      sig.initVerify(key);
      sig.update(signingInput);
      boolean valid;
      if ("ES256".equals(alg)) {
        valid = sig.verify(ecRawToDer(actual));
      } else {
        valid = sig.verify(actual);
      }
      if (!valid) {
        throw new InvalidTokenException("签名验证失败");
      }
    } catch (InvalidTokenException e) {
      throw e;
    } catch (Exception e) {
      // 附带原因类名便于诊断（不含密钥与 token 原文）
      throw new InvalidTokenException("签名验证失败: " + e.getClass().getSimpleName());
    }
  }

  private static String jcaAlgorithm(String alg) {
    return switch (alg) {
      case "RS256" -> "SHA256withRSA";
      case "RS384" -> "SHA384withRSA";
      case "RS512" -> "SHA512withRSA";
      case "ES256" -> "SHA256withECDSA";
      default -> throw new InvalidTokenException("不支持的算法: " + alg);
    };
  }

  /** 静态 PEM 模式忽略 kid；JWKS 模式按 kid 选择（缺失 kid 时仅单密钥集可用）。 */
  private PublicKey resolvePublicKey(String alg, String kid) {
    if (staticPublicKey != null) {
      return typeMatches(alg, staticPublicKey) ? staticPublicKey : null;
    }
    if (jwksUrl == null) {
      return null;
    }
    if (jwksCache.isEmpty() || Instant.now().toEpochMilli() - jwksFetchedAt > JWKS_CACHE_TTL_MS) {
      refreshJwks();
    }
    PublicKey key = selectJwksKey(alg, kid);
    if (key == null) {
      // 未知 kid：可能是 IAM 刚轮换密钥，限流刷新一次后重试（为密钥轮换留路）
      refreshJwks();
      key = selectJwksKey(alg, kid);
    }
    return key;
  }

  private PublicKey selectJwksKey(String alg, String kid) {
    Map<String, PublicKey> cache = jwksCache;
    if (kid != null) {
      PublicKey key = cache.get(alg + "|" + kid);
      return key != null && typeMatches(alg, key) ? key : null;
    }
    // 无 kid 的 token：仅当 JWKS 恰好只有一个唯一公钥时回退可用（多密钥集必须携带 kid）
    var distinct =
        cache.values().stream().filter(k -> typeMatches(alg, k)).collect(Collectors.toSet());
    return distinct.size() == 1 ? distinct.iterator().next() : null;
  }

  private static boolean typeMatches(String alg, PublicKey key) {
    String algo = key.getAlgorithm();
    if (RSA_ALGS.contains(alg)) {
      return "RSA".equals(algo);
    }
    return "EC".equals(algo);
  }

  private synchronized void refreshJwks() {
    long now = Instant.now().toEpochMilli();
    boolean ttlStale = now - jwksFetchedAt > JWKS_CACHE_TTL_MS;
    if (!ttlStale && now - jwksLastRefreshAttempt < JWKS_REFRESH_MIN_INTERVAL_MS) {
      return; // 未知 kid 触发的刷新被限流：30 秒内已尝试过
    }
    jwksLastRefreshAttempt = now;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(jwksUrl))
              .timeout(java.time.Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("JWKS HTTP " + response.statusCode());
      }
      Map<String, PublicKey> parsed = parseJwks(response.body());
      if (!parsed.isEmpty()) {
        this.jwksCache = parsed;
        this.jwksFetchedAt = now;
      }
    } catch (Exception e) {
      // 拉取失败时沿用旧缓存（若为空则后续验签按"未找到公钥"拒绝——fail-closed）；
      // review-#9：留痕便于排障（密钥轮换/网络故障此前完全无迹可查）
      org.slf4j.LoggerFactory.getLogger(JwtVerifier.class)
          .warn("JWKS 拉取失败（url 已配置），沿用旧缓存重试: {}", e.getClass().getSimpleName());
    }
  }

  private Map<String, PublicKey> parseJwks(String body) throws Exception {
    JsonNode keys = mapper.readTree(body).path("keys");
    Map<String, PublicKey> out = new ConcurrentHashMap<>();
    KeyFactory rsaFactory = KeyFactory.getInstance("RSA");
    KeyFactory ecFactory = KeyFactory.getInstance("EC");
    for (JsonNode k : keys) {
      String kty = k.path("kty").asText("");
      String kid = k.path("kid").asText("");
      if ("RSA".equals(kty) && k.hasNonNull("n") && k.hasNonNull("e")) {
        BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(pad(k.get("n").asText())));
        BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(pad(k.get("e").asText())));
        PublicKey key = rsaFactory.generatePublic(new RSAPublicKeySpec(n, e));
        if (!kid.isBlank()) {
          out.put("RS256|" + kid, key);
          out.put("RS384|" + kid, key);
          out.put("RS512|" + kid, key);
        } else {
          out.put("RSA|anonymous", key);
        }
      } else if ("EC".equals(kty) && "P-256".equals(k.path("crv").asText("")) && k.hasNonNull("x")
          && k.hasNonNull("y")) {
        BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode(pad(k.get("x").asText())));
        BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode(pad(k.get("y").asText())));
        PublicKey key = ecFactory.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256Params()));
        if (!kid.isBlank()) {
          out.put("ES256|" + kid, key);
        }
      }
    }
    return out;
  }

  private static ECParameterSpec p256Params() throws Exception {
    java.security.AlgorithmParameters ap = java.security.AlgorithmParameters.getInstance("EC");
    ap.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
    return ap.getParameterSpec(ECParameterSpec.class);
  }

  /** 支持 "-----BEGIN PUBLIC KEY-----" 框架与字面 \n 转义（env 注入便利）。 */
  private static PublicKey parsePemPublicKey(String pem) throws Exception {
    String normalized = pem.replace("\\n", "\n").replace("\r", "");
    StringBuilder b64 = new StringBuilder();
    for (String line : normalized.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("-----")) {
        continue;
      }
      b64.append(trimmed);
    }
    byte[] der = Base64.getDecoder().decode(b64.toString());
    X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
    try {
      return KeyFactory.getInstance("RSA").generatePublic(spec);
    } catch (Exception rsaFail) {
      return KeyFactory.getInstance("EC").generatePublic(spec);
    }
  }

  /** RFC 7515：ES256 签名为 64 字节 raw R||S，JCA 需要 DER SEQUENCE 编码。 */
  private static byte[] ecRawToDer(byte[] raw) {
    if (raw == null || raw.length != 64) {
      throw new InvalidTokenException("ES256 签名长度非法");
    }
    byte[] r = derInteger(raw, 0, 32);
    byte[] s = derInteger(raw, 32, 32);
    // SEQUENCE 内容长度 = 两个 INTEGER 的总字节数（不含 SEQUENCE 自身的 tag+len 头）
    int len = r.length + s.length;
    byte[] der = new byte[len + 2];
    der[0] = 0x30;
    der[1] = (byte) len;
    System.arraycopy(r, 0, der, 2, r.length);
    System.arraycopy(s, 0, der, 2 + r.length, s.length);
    return der;
  }

  private static byte[] derInteger(byte[] src, int off, int len) {
    int start = off;
    int length = len;
    while (length > 1 && src[start] == 0) {
      start++;
      length--;
    }
    boolean pad = (src[start] & 0x80) != 0;
    byte[] out = new byte[2 + length + (pad ? 1 : 0)];
    out[0] = 0x02;
    out[1] = (byte) (length + (pad ? 1 : 0));
    int i = 2;
    if (pad) {
      out[i++] = 0;
    }
    System.arraycopy(src, start, out, i, length);
    return out;
  }

  /** Base64Url 去掉 padding，解码前补回。 */
  private static String pad(String s) {
    int rem = s.length() % 4;
    return rem == 0 ? s : s + "====".substring(rem);
  }

  /** 仅供测试与诊断：当前 JWKS 缓存规模。 */
  int jwksCacheSize() {
    return jwksCache.size();
  }

  /** 仅供测试：将 JWKS 缓存时间戳回拨，模拟 TTL 过期以验证轮换刷新路径。 */
  void ageJwksCacheForTest(long ageMs) {
    this.jwksFetchedAt = Instant.now().toEpochMilli() - ageMs;
  }
}
