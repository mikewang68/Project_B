package com.bproject.safety.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 受认证的 IAM 服务端验票（SSO 契约 V1.1）。
 *
 * <p>SCS 不持有 IAM 的登录签名密钥，也不解码前端传来的身份断言。浏览器只提交一次性票据，
 * SCS 用服务身份 {@code X-SSO-Service-Token} 调 IAM {@code /auth/sso/verify-and-consume}，
 * 由 IAM 完成签名校验、受众匹配与 {@code jti} 原子消费，返回真实主体与范围。
 *
 * <p>失败语义：网络异常、超时、IAM 非 200、响应 code 非 0 一律抛
 * {@link InvalidTicketException}，调用方返回 401，不存在"验不过就放行"的分支。
 */
public final class ScsIamTicketValidator {

  /** 票据不可用。消息不含票据原文与服务令牌。 */
  public static class InvalidTicketException extends RuntimeException {
    public InvalidTicketException(String message) {
      super(message);
    }
  }

  private final String baseUrl;
  private final String serviceToken;
  private final String audience;
  private final Duration timeout;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public ScsIamTicketValidator(
      String baseUrl, String serviceToken, String audience, long timeoutMs, ObjectMapper mapper) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    this.audience = audience == null ? "" : audience.trim();
    this.timeout = Duration.ofMillis(Math.max(500, Math.min(timeoutMs, 10000)));
    this.mapper = mapper;
    this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
  }

  /** 供 revoke 端点做常量时间比对；不在日志或响应中出现。 */
  public String serviceToken() {
    return serviceToken;
  }

  /** 三项配置齐全才可用；否则调用方不得走票据分支。 */
  public boolean isUsable() {
    return !baseUrl.isEmpty() && !serviceToken.isEmpty() && !audience.isEmpty();
  }

  public Map<String, Object> validate(String ticket) {
    if (!isUsable()) {
      throw new InvalidTicketException("IAM 验票未配置");
    }
    if (ticket == null || ticket.isBlank()) {
      throw new InvalidTicketException("票据为空");
    }

    Map<String, Object> body = new HashMap<>();
    body.put("ticket", ticket.trim());
    body.put("audience", audience);
    body.put("consumer", "scs");

    HttpRequest request;
    try {
      request =
          HttpRequest.newBuilder(URI.create(baseUrl + "/auth/sso/verify-and-consume"))
              .timeout(timeout)
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .header("X-SSO-Service-Token", serviceToken)
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
              .build();
    } catch (Exception e) {
      throw new InvalidTicketException("票据请求构造失败");
    }

    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new InvalidTicketException("IAM 验票不可达");
    }

    if (response.statusCode() != 200) {
      throw new InvalidTicketException("IAM 拒绝票据（HTTP " + response.statusCode() + "）");
    }

    JsonNode root;
    try {
      root = mapper.readTree(response.body());
    } catch (Exception e) {
      throw new InvalidTicketException("IAM 验票响应不是合法 JSON");
    }
    if (root.path("code").asInt(-1) != 0) {
      throw new InvalidTicketException("IAM 判定票据无效");
    }

    JsonNode data = root.path("data");
    String subject = data.path("subject").asText("");
    if (subject.isBlank()) {
      throw new InvalidTicketException("IAM 未返回主体");
    }
    // IAM 的 verify-and-consume 返回的 audience 形如 "[scs]"（JWT aud 数组的字符串形式），
    // 必须做包含判断而非全等，否则合法票据会被误拒。
    String aud = data.path("audience").asText("");
    if (!aud.isBlank() && !audienceMatches(aud, audience)) {
      throw new InvalidTicketException("票据受众不匹配");
    }

    Map<String, Object> result = new HashMap<>();
    result.put("sub", subject);
    result.put("username", data.path("username").asText(""));
    result.put("scope", aud);
    result.put("sessionId", data.path("sessionId").asText(""));
    result.put("roles", data.path("roles"));
    result.put("permissions", data.path("permissions"));
    for(String field:java.util.List.of("localSubject","company","org","domain","zone","readOnly","superAdmin","applicationAdmin"))
      result.put(field,mapper.convertValue(data.path(field),Object.class));
    return result;
  }

  public Map<String,Object> current(String subject,String sid){
    try{
      var req=HttpRequest.newBuilder(URI.create(baseUrl+"/auth/sso/session/introspect")).timeout(timeout)
          .header("Content-Type","application/json").header("X-SSO-Service-Token",serviceToken)
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("subject",subject,"sessionId",sid,"audience",audience)))).build();
      var response=http.send(req,HttpResponse.BodyHandlers.ofString());var root=mapper.readTree(response.body());var data=root.path("data");
      if(response.statusCode()!=200||root.path("code").asInt(-1)!=0||!data.path("active").asBoolean()
          ||!subject.equals(data.path("subject").asText())||!sid.equals(data.path("sessionId").asText())
          ||!audience.equals(data.path("audience").asText()))throw new InvalidTicketException("IAM会话已失效");
      Map<String,Object> result=mapper.convertValue(data,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
      result.put("sub",subject);result.put("scope",audience);return result;
    }catch(InvalidTicketException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InvalidTicketException("IAM暂不可用");}
    catch(Exception e){throw new InvalidTicketException("IAM暂不可用");}
  }

  public void logout(String subject, String sid) {
    try {
      var req = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/sso/session/logout")).timeout(timeout)
          .header("Content-Type", "application/json").header("X-SSO-Service-Token", serviceToken)
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
              Map.of("subject", subject, "sessionId", sid, "audience", audience)))).build();
      var response = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 || mapper.readTree(response.body()).path("code").asInt(-1) != 0)
        throw new InvalidTicketException("统一退出未完成");
    } catch (InvalidTicketException e) { throw e; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InvalidTicketException("统一退出暂不可用"); }
    catch (Exception e) { throw new InvalidTicketException("统一退出暂不可用"); }
  }

  /** 允许 "scs" / "[scs]" / "[scs, wms]" / "scs,wms" 等表示形式。 */
  private static boolean audienceMatches(String returned, String expected) {
    String cleaned = returned.replace("[", "").replace("]", "").replace(" ", "");
    for (String part : cleaned.split(",")) {
      if (part.equalsIgnoreCase(expected)) {
        return true;
      }
    }
    return false;
  }
}
