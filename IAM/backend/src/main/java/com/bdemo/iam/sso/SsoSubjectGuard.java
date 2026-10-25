package com.bdemo.iam.sso;

import com.bdemo.iam.sso.mapper.SsoSubjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 换票前的可用性检查与范围解析（V1.1）。
 *
 * <p>V1.1 的关键修正（对应 G2_1_CONTRACT_RECONCILIATION.md）：
 *
 * <ul>
 *   <li><b>删除导航回退</b>：V1 在缺少显式绑定时会回退到「该用户能否在 IAM 导航树中看到该系统」。
 *       这只能说明入口是否可见，不能作为业务系统主体映射与授权的依据。V1.1 起，
 *       无显式映射即 403，不设任何回退。
 *   <li><b>范围只来自映射表</b>：company/org/domain/zone 一律取 {@code iam.sso_subject_binding}
 *       的登记值，不再用登录主体字段兜底（避免"字符串透传即隔离"）。
 * </ul>
 */
@Service
public class SsoSubjectGuard {

  private final SsoSubjectMapper bindings;

  public SsoSubjectGuard(SsoSubjectMapper bindings) {
    this.bindings = bindings;
  }

  /**
   * 确认该主体在目标系统存在**启用的显式映射**。
   *
   * @throws SsoTicketService.SsoTicketException 无映射或映射停用时（403）
   */
  public void requireBound(String userId, String username, String audience) {
    String aud = audience.trim().toLowerCase();
    Map<String, Object> binding = bindings.find(userId, aud);
    if (binding == null) {
      throw new SsoTicketService.SsoTicketException(
          "该主体在目标系统无显式映射（V1.1 不设回退）: " + aud);
    }
    if (!isEnabled(binding)) {
      throw new SsoTicketService.SsoTicketException("该主体在目标系统的映射已停用: " + aud);
    }
    Object local = binding.get("local_subject");
    if (local == null || String.valueOf(local).isBlank()) {
      throw new SsoTicketService.SsoTicketException("映射存在但本地主体为空: " + aud);
    }
  }

  /** 解析范围：全部取自映射表，缺失即为空字符串（由目标系统按"空=无范围"处理）。 */
  public Map<String, String> scopeFor(String userId, String audience) {
    Map<String, String> scope = new HashMap<>();
    Map<String, Object> binding = bindings.find(userId, audience.trim().toLowerCase());
    scope.put("company", binding == null ? "" : str(binding.get("company_code")));
    scope.put("org", binding == null ? "" : str(binding.get("org_code")));
    scope.put("domain", binding == null ? "" : str(binding.get("domain_code")));
    scope.put("zone", binding == null ? "" : str(binding.get("zone_code")));
    scope.put("local", binding == null ? "" : str(binding.get("local_subject")));
    return scope;
  }

  /** 该主体在目标系统的本地受限主体名（用于审计与目标端二次校验）。 */
  public String localSubject(String userId, String audience) {
    Map<String, Object> binding = bindings.find(userId, audience.trim().toLowerCase());
    return binding == null ? "" : str(binding.get("local_subject"));
  }
  public java.util.List<String> projects(String subject,String audience){
    var values=bindings.projects(subject,audience);return values==null?java.util.List.of():java.util.List.copyOf(values);
  }

  private static boolean isEnabled(Map<String, Object> binding) {
    Object enabled = binding.get("enabled");
    if (enabled == null) return true;
    String s = String.valueOf(enabled);
    return "1".equals(s) || "true".equalsIgnoreCase(s);
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }
}
