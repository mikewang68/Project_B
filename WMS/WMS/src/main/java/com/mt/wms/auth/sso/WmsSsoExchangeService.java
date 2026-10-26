package com.mt.wms.auth.sso;

import com.mt.wms.auth.AuthRepository;
import com.mt.wms.auth.WmsPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 可信 SSO 兑换服务（契约 V1.1，集中式消费）。
 *
 * <p>流程：
 *
 * <ol>
 *   <li>WMS 把票据提交给 IAM 的 {@code /auth/sso/verify-and-consume}，携带服务身份凭据；
 *   <li>IAM 验签、校验 iss/aud/时间窗/jti 唯一/sid 活跃，并**原子消费**该 jti；
 *   <li>IAM 返回身份断言（subject、sid、范围、localSubject）；
 *   <li>WMS 按 subject 查本地映射，建立自己的受限会话。
 * </ol>
 *
 * <p>WMS <b>不</b>自行验签、<b>不</b>自行消费票据、<b>不</b>接受前端 claims。
 * 因此不需要也不持有票据签名密钥。
 *
 * <p>失败一律拒绝：无映射、IAM 不可达、IAM 返回非 200、响应缺字段。
 */
@Service
public class WmsSsoExchangeService {

  private final WmsSsoProperties properties;
  private final WmsSsoSubjectRepository subjects;
  private final AuthRepository authRepository;
  private final ObjectMapper mapper;
  private final HttpClient http;

  public WmsSsoExchangeService(
      WmsSsoProperties properties,
      WmsSsoSubjectRepository subjects,
      AuthRepository authRepository,
      ObjectMapper mapper) {
    this.properties = properties;
    this.subjects = subjects;
    this.authRepository = authRepository;
    this.mapper = mapper;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
        .build();
  }

  /** 兑换结果：成功时带本地主体；失败带稳定的错误码。 */
  public record Result(boolean ok, String errorCode, String message, WmsPrincipal principal, String iamSubject, String iamSessionId) {
    static Result fail(String code, String message) {
      return new Result(false, code, message, null, null, null);
    }
  }

  public Result exchange(String ticket) {
    if (!properties.isUsable()) {
      return Result.fail("SSO_NOT_CONFIGURED", "WMS 可信 SSO 未配置，拒绝兑换");
    }
    if (ticket == null || ticket.isBlank()) {
      return Result.fail("SSO_TICKET_MISSING", "SSO票据不能为空");
    }

    // 1) 调 IAM 集中验票 + 一次性消费
    JsonNode assertion;
    try {
      String body = mapper.writeValueAsString(
          mapper.createObjectNode()
              .put("ticket", ticket)
              .put("audience", properties.getAudience())
              .put("consumer", "wms"));
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(properties.getIamBaseUrl() + "/auth/sso/verify-and-consume"))
          .timeout(Duration.ofMillis(properties.getTimeoutMs()))
          .header("Content-Type", "application/json")
          .header("X-SSO-Service-Token", properties.getServiceToken())
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        // IAM 已经把失败原因规范化（401/403），此处不转发其原文以防探测
        return Result.fail(
            response.statusCode() == 403 ? "SSO_FORBIDDEN" : "SSO_TICKET_REJECTED",
            "IAM 拒绝该票据（HTTP " + response.statusCode() + "）");
      }
      JsonNode root = mapper.readTree(response.body());
      if (!root.path("code").isIntegralNumber() || root.path("code").intValue() != 0) {
        return Result.fail("SSO_TICKET_REJECTED", "IAM 业务结果拒绝票据");
      }
      assertion = root.path("data");
      if (assertion.isMissingNode() || assertion.isNull()) {
        return Result.fail("SSO_BAD_ASSERTION", "IAM 响应缺少断言");
      }
    } catch (Exception e) {
      // IAM 不可达也拒绝，绝不降级为本地放行
      return Result.fail("SSO_IAM_UNAVAILABLE", "无法连接 IAM 校验服务");
    }

    String subject = text(assertion, "subject");
    String sid = text(assertion, "sessionId");
    if (subject == null || subject.isBlank()) {
      return Result.fail("SSO_BAD_ASSERTION", "断言缺少 subject");
    }
    if (!subject.matches("[a-fA-F0-9]{32}|[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
        || sid == null || sid.isBlank() || WmsSsoLogoutRegistry.isRevoked(sid)) {
      return Result.fail("SSO_BAD_ASSERTION", "断言缺少有效的稳定主体或活跃会话");
    }
    String returnedAudience = text(assertion, "audience");
    if (!"wms".equals(returnedAudience) && !"[wms]".equals(returnedAudience)) {
      return Result.fail("SSO_BAD_ASSERTION", "断言受众不匹配");
    }

    // 2) 本地显式映射：无映射 / 停用 / 账号不可用 → 拒绝
    Optional<WmsSsoSubjectRepository.Binding> binding = subjects.findActive(subject);
    if (binding.isEmpty()) {
      subjects.recordExchange(subject, 0L, sid, "NO_MAPPING");
      return Result.fail("SSO_NO_LOCAL_MAPPING", "该 IAM 主体在 WMS 无可用映射");
    }
    WmsSsoSubjectRepository.Binding b = binding.get();
    if (!b.localUsername().equals(text(assertion, "localSubject"))) {
      return Result.fail("SSO_NO_LOCAL_MAPPING", "IAM 与 WMS 显式映射不一致");
    }
    String claimedCompany = text(assertion, "company");
    if (claimedCompany != null && !claimedCompany.isBlank() && !claimedCompany.equals(b.companyCode())) {
      return Result.fail("SSO_SCOPE_MISMATCH", "票据公司与本地绑定不一致");
    }

    // 2b) Zone 范围 fail-closed。
    // 业务层当前没有 zone 维度的数据模型（隔离维度是 company/warehouse/owner），
    // 因此映射里一旦声明了非空 zone_code，就无法在查询中兑现该范围。
    // 与其"记下 zone 但不执行"，不如直接拒绝——否则等于给用户一个它没有的范围承诺。
    String claimedZone = text(assertion, "zone");
    for (String field : List.of("org", "domain")) {
      String scope = text(assertion, field);
      if (scope != null && !scope.isBlank()) {
        return Result.fail("SSO_SCOPE_MISMATCH", "WMS 无法执行所声明的 " + field + " 范围");
      }
    }
    if ((b.zoneCode() != null && !b.zoneCode().isBlank()) || (claimedZone != null && !claimedZone.isBlank())) {
      subjects.recordExchange(subject, b.localUserId(), sid, "ZONE_UNSUPPORTED");
      return Result.fail(
          "SSO_ZONE_UNSUPPORTED",
          "该主体映射了 zone_code，但 WMS 尚无 zone 维度数据模型，拒绝以更大范围放行");
    }

    // 3) 用**本地主体自己的**角色与权限构造 principal（不继承、不提权）
    List<String> roles = List.copyOf(authRepository.findRoles(b.localUserId()));
    Set<String> permissions = authRepository.findPermissions(b.localUserId());

    long companyId = companyIdOf(b.companyCode());
    boolean all = permissions.contains("tenant:all");
    if (companyId < 0 || authRepository.findWarehouses(b.localUserId(), companyId, all).isEmpty()
        || authRepository.findOwners(b.localUserId(), companyId, all).isEmpty()) {
      return Result.fail("SSO_NO_LOCAL_MAPPING", "本地主体缺少有效公司、仓库或货主范围");
    }

    WmsPrincipal principal = new WmsPrincipal(
        b.localUserId(),
        companyId,
        b.companyCode(),
        b.companyCode(),
        b.localUsername(),
        b.localUsername(),
        roles,
        permissions,
        b.passwordChangeRequired());

    subjects.recordExchange(subject, b.localUserId(), sid, "OK");
    return new Result(true, null, null, principal, subject, sid);
  }

  /** Validate the live IAM session even if a logout notification has not arrived. */
  public JsonNode currentAuthorization(String subject, String sid) {
    if (!properties.isUsable()) throw new IllegalStateException("SSO is not configured");
    try {
      var request = HttpRequest.newBuilder(URI.create(properties.getIamBaseUrl()+"/auth/sso/session/introspect"))
          .timeout(Duration.ofMillis(properties.getTimeoutMs()))
          .header("Content-Type","application/json").header("X-SSO-Service-Token",properties.getServiceToken())
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
              java.util.Map.of("subject",subject,"sessionId",sid,"audience","wms")))).build();
      var response=http.send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()==401||response.statusCode()==403)throw new SecurityException("IAM session inactive");
      if(response.statusCode()!=200)throw new IllegalStateException("IAM unavailable");
      var root=mapper.readTree(response.body());var data=root.path("data");
      if(root.path("code").asInt(-1)!=0||!data.path("active").asBoolean()
          ||!subject.equals(data.path("subject").asText())||!sid.equals(data.path("sessionId").asText())
          ||!"wms".equals(data.path("audience").asText())||!data.path("readOnly").isBoolean())
        throw new SecurityException("IAM authorization rejected");
      var local=subjects.findActive(subject).orElseThrow(()->new SecurityException("Mapping disabled"));
      if(!local.localUsername().equals(data.path("localSubject").asText())
          ||!local.companyCode().equals(data.path("company").asText()))throw new SecurityException("Scope changed");
      return data;
    } catch(SecurityException|IllegalStateException e){throw e;}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("IAM unavailable");}
    catch(Exception e){throw new IllegalStateException("IAM unavailable");}
  }
  public void globalLogout(String subject,String sid){
    try{
      var request=HttpRequest.newBuilder(URI.create(properties.getIamBaseUrl()+"/auth/sso/session/logout"))
          .timeout(Duration.ofMillis(properties.getTimeoutMs())).header("Content-Type","application/json")
          .header("X-SSO-Service-Token",properties.getServiceToken())
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(java.util.Map.of("subject",subject,"sessionId",sid,"audience","wms")))).build();
      var response=http.send(request,HttpResponse.BodyHandlers.ofString());
      if(response.statusCode()!=200||mapper.readTree(response.body()).path("code").asInt(-1)!=0)throw new IllegalStateException("Global logout unavailable");
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Global logout unavailable");}
    catch(Exception e){throw new IllegalStateException("Global logout unavailable");}
  }

  /**
   * 契约要求：company 必须来自映射，不接受前端传入。
   * 这里用 companyCode 反查 id；查不到时返回 -1，由上层拒绝。
   */
  private long companyIdOf(String companyCode) {
    try {
      return authRepository.findCompanyId(companyCode).orElse(-1L);
    } catch (Exception e) {
      return -1L;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }
}
