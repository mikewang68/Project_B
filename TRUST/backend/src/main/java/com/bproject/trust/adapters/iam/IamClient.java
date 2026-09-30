package com.bproject.trust.adapters.iam;

import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.ports.PlatformIdentity;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IamClient implements PlatformIdentity {
  private final IntegrationSettings settings;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final boolean enabled;

  public IamClient(IntegrationSettings settings) {
    this.settings = settings;
    this.enabled = !"simulated".equalsIgnoreCase(settings.read().iamMode());
  }

  private JsonNode request(String path, String token, Object body) {
    String url = settings.read().iamUrl();
    if (url.isBlank()) throw new ApiError(503, "尚未配置 IAM 真实接口");
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(url.replaceAll("/$", "") + path))
              .timeout(Duration.ofSeconds(5))
              .header("Accept", "application/json");
      if (token != null) builder.header("Authorization", "Bearer " + token);
      if (body != null)
        builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
      var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      if (result.statusCode() == 401 || result.statusCode() == 403 || result.statusCode() == 400)
        throw new ApiError(401, "IAM 身份无效、已停用或凭据错误");
      if (result.statusCode() != 200
          || !result.headers().firstValue("Content-Type").orElse("").contains("application/json"))
        throw new ApiError(503, "IAM 未返回有效认证接口响应");
      var root = Json.MAPPER.readTree(result.body());
      if (!root.has("code") || root.path("code").asInt(-1) != 0 || !root.hasNonNull("data"))
        throw new ApiError(401, "IAM 认证失败");
      return root.get("data");
    } catch (ApiError e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiError(503, "IAM 请求中断");
    } catch (Exception e) {
      throw new ApiError(503, "IAM 暂不可用，请稍后重试");
    }
  }

  public String login(String username, String password) {
    if (!enabled) throw new ApiError(503, "当前配置使用模拟身份源，未启用真实 IAM");
    String token =
        request("/auth/login", null, Map.of("username", username, "password", password))
            .path("token")
            .asText("");
    if (token.isBlank()) throw new ApiError(503, "IAM 未返回登录凭据");
    return token;
  }

  public User current(String token) {
    if (!enabled) throw new ApiError(503, "当前配置使用模拟身份源，未启用真实 IAM");
    var data = request("/auth/me", token, null);
    var user = data.path("user");
    if (!"active".equals(user.path("status").asText()) || user.path("id").asText().isBlank())
      throw new ApiError(401, "IAM 用户不可用");
    var orgs = new ArrayList<String>();
    user.path("orgCodes").forEach(v -> orgs.add(v.asText()));
    var permissions = new HashSet<String>();
    data.path("permissions").forEach(v -> permissions.add(v.asText()));
    // No implicit super-administrator bypass for evidence or signing permissions.
    return new User(user.get("id").asText(), user.path("username").asText(), orgs, permissions);
  }
}
