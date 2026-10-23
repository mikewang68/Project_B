package com.bproject.safety.config;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P1-1：JwtVerifier 非对称验签（RS256/384/512、ES256）安全测试。
 * 覆盖：签名往返、篡改/错钥/过期/未生效/issuer 不符、算法白名单、
 * 算法混淆攻击（公钥当 HMAC 秘钥）、PEM 字面 \n、JWKS kid 选择与轮换刷新、fail-closed 构造。
 */
@DisplayName("P1-1: JwtVerifier RS/ES 非对称验签与攻击面测试")
class JwtVerifierAsymmetricTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String HMAC_SECRET = "0123456789abcdef0123456789abcdef"; // 32B
  private static final String ISSUER = "iam";

  private static KeyPair rsaPair;
  private static KeyPair rsaPairOther;
  private static KeyPair ecPair;

  @BeforeAll
  static void generateKeys() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    rsaPair = rsa.generateKeyPair();
    rsaPairOther = rsa.generateKeyPair();
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    ecPair = ec.generateKeyPair();
  }

  // ---------- 工具 ----------

  private static String b64u(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static String claims(long ttlSeconds) {
    long now = java.time.Instant.now().getEpochSecond();
    return "{\"sub\":\"admin_01\",\"iss\":\"" + ISSUER + "\",\"aud\":\"safety\","
        + "\"roles\":[\"ADMIN\"],\"exp\":" + (now + ttlSeconds) + "}";
  }

  private static String rsToken(String payload, String alg, String kid, KeyPair pair)
      throws Exception {
    String header = "{\"alg\":\"" + alg + "\"" + (kid == null ? "" : ",\"kid\":\"" + kid + "\"")
        + ",\"typ\":\"JWT\"}";
    String signingInput = b64u(header.getBytes(UTF_8)) + "." + b64u(payload.getBytes(UTF_8));
    String jca = switch (alg) {
      case "RS256" -> "SHA256withRSA";
      case "RS384" -> "SHA384withRSA";
      default -> "SHA512withRSA";
    };
    Signature sig = Signature.getInstance(jca);
    sig.initSign(pair.getPrivate());
    sig.update(signingInput.getBytes(US_ASCII));
    return signingInput + "." + b64u(sig.sign());
  }

  private static String esToken(String payload, String kid, KeyPair pair) throws Exception {
    String header = "{\"alg\":\"ES256\"" + (kid == null ? "" : ",\"kid\":\"" + kid + "\"")
        + ",\"typ\":\"JWT\"}";
    String signingInput = b64u(header.getBytes(UTF_8)) + "." + b64u(payload.getBytes(UTF_8));
    Signature sig = Signature.getInstance("SHA256withECDSA");
    sig.initSign(pair.getPrivate());
    sig.update(signingInput.getBytes(US_ASCII));
    byte[] der = sig.sign();
    // DER SEQUENCE{INTEGER r, INTEGER s} → raw 64 字节 R||S
    int i = 2; // 跳过 30 len
    byte[] r = readDerInt(der, i);
    i += 2 + der[i + 1];
    byte[] s = readDerInt(der, i);
    byte[] raw = new byte[64];
    System.arraycopy(r, 0, raw, 32 - r.length, r.length);
    System.arraycopy(s, 0, raw, 64 - s.length, s.length);
    return signingInput + "." + b64u(raw);
  }

  private static byte[] readDerInt(byte[] der, int off) {
    int len = der[off + 1];
    int start = off + 2;
    int actual = len;
    while (actual > 1 && der[start] == 0) {
      start++;
      actual--;
    }
    byte[] out = new byte[actual];
    System.arraycopy(der, start, out, 0, actual);
    return out;
  }

  private static String hsToken(String payload, String alg, String secret) throws Exception {
    String header = "{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}";
    String signingInput = b64u(header.getBytes(UTF_8)) + "." + b64u(payload.getBytes(UTF_8));
    String jca = switch (alg) {
      case "HS256" -> "HmacSHA256";
      case "HS384" -> "HmacSHA384";
      default -> "HmacSHA512";
    };
    Mac mac = Mac.getInstance(jca);
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), jca));
    return signingInput + "." + b64u(mac.doFinal(signingInput.getBytes(US_ASCII)));
  }

  private static String pemOf(java.security.PublicKey key) {
    String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
    return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
  }

  private static JwtVerifier rsPemVerifier(String pem, Set<String> allowed) {
    return new JwtVerifier(null, ISSUER, 60, MAPPER, allowed, pem, null);
  }

  private static String unsignedB64Modulus(BigIntegerLike n) {
    byte[] bytes = n.bytes();
    return b64u(bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
  }

  private record BigIntegerLike(byte[] bytes) {
  }

  // ---------- RSA 家族 ----------

  @Test
  @DisplayName("RS256/384/512 PEM 往返：验签通过并返回 claims（含字面 \\n 的 PEM）")
  void rsFamilyRoundtrip() throws Exception {
    String pem = pemOf(rsaPair.getPublic()).replace("\n", "\\n");
    JwtVerifier v = rsPemVerifier(pem, Set.of("RS256", "RS384", "RS512"));
    for (String alg : new String[]{"RS256", "RS384", "RS512"}) {
      String token = rsToken(claims(600), alg, "iam-key-1", rsaPair);
      var payload = v.verify(token);
      assertThat(payload.path("sub").asText()).isEqualTo("admin_01");
    }
  }

  @Test
  @DisplayName("RS256 篡改载荷被拒（签名验证失败）")
  void rsTamperedPayloadRejected() throws Exception {
    JwtVerifier v = rsPemVerifier(pemOf(rsaPair.getPublic()), Set.of("RS256"));
    String token = rsToken(claims(600), "RS256", null, rsaPair);
    String[] parts = token.split("\\.");
    String forged = parts[0] + "." + b64u("{\"sub\":\"attacker\",\"iss\":\"iam\",\"aud\":\"safety\",\"exp\":"
        .concat(java.time.Instant.now().getEpochSecond() + 99999 + "}").getBytes(UTF_8)) + "." + parts[2];
    assertThatThrownBy(() -> v.verify(forged))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class)
        .hasMessageContaining("签名验证失败");
  }

  @Test
  @DisplayName("RS256 错误公钥被拒")
  void rsWrongKeyRejected() throws Exception {
    JwtVerifier v = rsPemVerifier(pemOf(rsaPair.getPublic()), Set.of("RS256"));
    String token = rsToken(claims(600), "RS256", null, rsaPairOther);
    assertThatThrownBy(() -> v.verify(token))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class)
        .hasMessageContaining("签名验证失败");
  }

  @Test
  @DisplayName("静态 PEM 模式忽略 kid 声明（kid 仅 JWKS 模式参与选择）")
  void staticPemIgnoresKid() throws Exception {
    JwtVerifier v = rsPemVerifier(pemOf(rsaPair.getPublic()), Set.of("RS256"));
    String token = rsToken(claims(600), "RS256", "any-random-kid", rsaPair);
    assertThatCode(() -> v.verify(token)).doesNotThrowAnyException();
  }

  // ---------- ES256 ----------

  @Test
  @DisplayName("ES256 PEM 往返（raw R||S ↔ DER 转换正确性）")
  void es256Roundtrip() throws Exception {
    JwtVerifier v = rsPemVerifier(pemOf(ecPair.getPublic()), Set.of("ES256"));
    String token = esToken(claims(600), null, ecPair);
    assertThat(v.verify(token).path("iss").asText()).isEqualTo(ISSUER);
  }

  @Test
  @DisplayName("ES256 错钥被拒")
  void es256WrongKeyRejected() throws Exception {
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    KeyPair other = ec.generateKeyPair();
    JwtVerifier v = rsPemVerifier(pemOf(ecPair.getPublic()), Set.of("ES256"));
    assertThatThrownBy(() -> v.verify(esToken(claims(600), null, other)))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class)
        .hasMessageContaining("签名验证失败");
  }

  // ---------- 算法白名单与混淆攻击 ----------

  @Test
  @DisplayName("白名单外算法被拒（RS384 token 对 RS256-only 验签器）")
  void algorithmNotAllowed() throws Exception {
    JwtVerifier v = rsPemVerifier(pemOf(rsaPair.getPublic()), Set.of("RS256"));
    assertThatThrownBy(() -> v.verify(rsToken(claims(600), "RS384", null, rsaPair)))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class)
        .hasMessageContaining("算法不在白名单");
  }

  @Test
  @DisplayName("混淆攻击一：RS-only 模式下 HS256 token（公钥字节当 HMAC 秘钥）被拒")
  void confusionAttackHsInRsOnlyMode() throws Exception {
    JwtVerifier v = rsPemVerifier(pemOf(rsaPair.getPublic()), Set.of("RS256"));
    // 攻击者拿到的是公钥：把公钥字节当作 HMAC 秘钥自签 HS256
    String attackerSecret = new String(rsaPair.getPublic().getEncoded(), UTF_8);
    String token = hsToken(claims(600), "HS256", attackerSecret);
    assertThatThrownBy(() -> v.verify(token))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class)
        .hasMessageContaining("算法不在白名单");
  }

  @Test
  @DisplayName("混淆攻击二：双通道模式（HS+RS）下公钥自签 token 签名不匹配被拒")
  void confusionAttackDualChannel() throws Exception {
    JwtVerifier v = new JwtVerifier(HMAC_SECRET, ISSUER, 60, MAPPER,
        Set.of("HS256", "RS256"), pemOf(rsaPair.getPublic()), null);
    String attackerSecret = new String(rsaPair.getPublic().getEncoded(), UTF_8);
    String token = hsToken(claims(600), "HS256", attackerSecret);
    assertThatThrownBy(() -> v.verify(token))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class)
        .hasMessageContaining("签名验证失败");
    // 对照：真正的 HS 密钥签的 token 仍可用（双通道并存）
    assertThatCode(() -> v.verify(hsToken(claims(600), "HS256", HMAC_SECRET)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("alg=none 在非对称模式下同样被拒（空签段按非三段式或 alg=none 拒绝，均安全）")
  void algNoneStillRejected() {
    JwtVerifier v = rsPemVerifier(pemOf(rsaPair.getPublic()), Set.of("RS256"));
    String noneToken = b64u("{\"alg\":\"none\"}".getBytes(UTF_8)) + "."
        + b64u(claims(600).getBytes(UTF_8)) + ".";
    assertThatThrownBy(() -> v.verify(noneToken))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class)
        .hasMessageMatching(".*(alg=none|三段式).*");
  }

  // ---------- 时间与 claims ----------

  @Test
  @DisplayName("过期 / 未生效 / issuer 不符 / 缺少 claims 逐项被拒")
  void timeAndClaimChecks() throws Exception {
    JwtVerifier v = rsPemVerifier(pemOf(rsaPair.getPublic()), Set.of("RS256"));
    long now = java.time.Instant.now().getEpochSecond();

    String expired = rsToken(
        "{\"sub\":\"u\",\"iss\":\"" + ISSUER + "\",\"aud\":\"safety\",\"exp\":" + (now - 7200) + "}",
        "RS256", null, rsaPair);
    assertThatThrownBy(() -> v.verify(expired)).hasMessageContaining("已过期");

    String nbf = rsToken(
        "{\"sub\":\"u\",\"iss\":\"" + ISSUER + "\",\"aud\":\"safety\",\"exp\":" + (now + 600)
            + ",\"nbf\":" + (now + 3600) + "}",
        "RS256", null, rsaPair);
    assertThatThrownBy(() -> v.verify(nbf)).hasMessageContaining("尚未生效");

    String badIss = rsToken(
        "{\"sub\":\"u\",\"iss\":\"evil\",\"aud\":\"safety\",\"exp\":" + (now + 600) + "}",
        "RS256", null, rsaPair);
    assertThatThrownBy(() -> v.verify(badIss)).hasMessageContaining("issuer 不匹配");

    String noExp = rsToken("{\"sub\":\"u\",\"iss\":\"iam\",\"aud\":\"safety\"}", "RS256", null, rsaPair);
    assertThatThrownBy(() -> v.verify(noExp)).hasMessageContaining("缺少 exp");

    String noAud = rsToken(
        "{\"sub\":\"u\",\"iss\":\"iam\",\"exp\":" + (now + 600) + "}", "RS256", null, rsaPair);
    assertThatThrownBy(() -> v.verify(noAud)).hasMessageContaining("缺少 aud");
  }

  // ---------- fail-closed 构造 ----------

  @Test
  @DisplayName("fail-closed：RS 白名单无公钥源 / PEM 配了但白名单无非对称 / 空白名单均拒绝构造")
  void failClosedConstructions() {
    assertThatThrownBy(() ->
        new JwtVerifier(null, ISSUER, 60, MAPPER, Set.of("RS256"), null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
        new JwtVerifier(null, ISSUER, 60, MAPPER, Set.of("HS256"), pemOf(rsaPair.getPublic()), null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
        new JwtVerifier(HMAC_SECRET, ISSUER, 60, MAPPER, Set.of(), null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() ->
        new JwtVerifier(null, ISSUER, 60, MAPPER, Set.of("RS256"), "not-a-pem", null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("兼容回归：4 参 HS-only 构造器行为不变")
  void hsOnlyLegacyConstructor() throws Exception {
    JwtVerifier v = new JwtVerifier(HMAC_SECRET, ISSUER, 60, MAPPER);
    assertThatCode(() -> v.verify(hsToken(claims(600), "HS256", HMAC_SECRET)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> v.verify(rsToken(claims(600), "RS256", null, rsaPair)))
        .hasMessageContaining("算法不在白名单");
  }

  // ---------- JWKS ----------

  private static HttpServer jwksServer;
  private static final AtomicReference<String> servedJwks = new AtomicReference<>("{}");

  private static String jwksJson(String kid, KeyPair pair) {
    RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
    return "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"use\":\"sig\",\"alg\":\"RS256\","
        + "\"n\":\"" + unsignedB64Modulus(new BigIntegerLike(pub.getModulus().toByteArray())) + "\","
        + "\"e\":\"" + unsignedB64Modulus(new BigIntegerLike(pub.getPublicExponent().toByteArray())) + "\"}]}";
  }

  @BeforeAll
  static void startJwksServer() throws Exception {
    jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    jwksServer.createContext("/jwks", ex -> {
      byte[] body = servedJwks.get().getBytes(UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, body.length);
      try (var out = ex.getResponseBody()) {
        out.write(body);
      }
    });
    jwksServer.setExecutor(Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "jwks-test");
      t.setDaemon(true);
      return t;
    }));
    jwksServer.start();
  }

  @AfterAll
  static void stopJwksServer() {
    if (jwksServer != null) {
      jwksServer.stop(0);
    }
  }

  private static JwtVerifier jwksVerifier() {
    return new JwtVerifier(null, ISSUER, 60, MAPPER, Set.of("RS256"), null,
        "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/jwks");
  }

  @Test
  @DisplayName("JWKS：按 kid 选择公钥验签通过")
  void jwksRoundtripWithKid() throws Exception {
    servedJwks.set(jwksJson("iam-2026-10", rsaPair));
    JwtVerifier v = jwksVerifier();
    String token = rsToken(claims(600), "RS256", "iam-2026-10", rsaPair);
    assertThat(v.verify(token).path("sub").asText()).isEqualTo("admin_01");
  }

  @Test
  @DisplayName("JWKS：未知 kid 且刷新后仍缺失则拒绝；错误 kid 对应错钥签名验证失败")
  void jwksUnknownKidRejected() throws Exception {
    servedJwks.set(jwksJson("iam-2026-10", rsaPair));
    JwtVerifier v = jwksVerifier();
    // 用另一密钥对签名并声称是已知 kid → 公钥不匹配 → 签名验证失败
    String forged = rsToken(claims(600), "RS256", "iam-2026-10", rsaPairOther);
    assertThatThrownBy(() -> v.verify(forged))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class)
        .hasMessageContaining("签名验证失败");
    // 完全未知的 kid：刷新（限流内直接沿用空/旧缓存）后仍无 → 未找到公钥
    servedJwks.set(jwksJson("iam-2026-10", rsaPair));
    String unknownKid = rsToken(claims(600), "RS256", "kid-not-exist", rsaPair);
    assertThatThrownBy(() -> v.verify(unknownKid))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class)
        .hasMessageContaining("未找到匹配的验签公钥");
  }

  @Test
  @DisplayName("JWKS 轮换：TTL 过期后重新拉取，新 kid 立即可用（为密钥轮换留路）")
  void jwksRotationOnTtlExpiry() throws Exception {
    servedJwks.set(jwksJson("kid-old", rsaPair));
    JwtVerifier v = jwksVerifier();
    assertThatCode(() -> v.verify(rsToken(claims(600), "RS256", "kid-old", rsaPair)))
        .doesNotThrowAnyException();

    // IAM 轮换密钥：JWKS 只发布新 kid
    servedJwks.set(jwksJson("kid-new", rsaPairOther));
    // 缓存回拨越过 TTL（模拟 15 分钟后），下一次验签触发重新拉取
    v.ageJwksCacheForTest(16 * 60 * 1000L);
    assertThatCode(() -> v.verify(rsToken(claims(600), "RS256", "kid-new", rsaPairOther)))
        .doesNotThrowAnyException();
    // 旧 kid 在轮换后失效
    assertThatThrownBy(() -> v.verify(rsToken(claims(600), "RS256", "kid-old", rsaPair)))
        .isInstanceOf(JwtVerifier.InvalidTokenException.class);
  }
}
