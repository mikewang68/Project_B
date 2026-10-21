package com.bdemo.iam.sso;

import com.bdemo.iam.navigation.service.NavigationProjectionService;
import com.bdemo.iam.sso.mapper.SsoSubjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 换票前的可用性检查与范围解析。
 *
 * <p>契约 D2/R2：{@code sub} 在目标系统必须存在显式映射；未命中即拒绝，不得 fallback。
 *
 * <p>V1 的映射来源优先级：
 *
 * <ol>
 *   <li>{@code iam.sso_subject_binding} 显式登记（最严格）；
 *   <li>回退到「该用户在 IAM 导航树中是否被授权访问该 app」——这是既有且可信的授权来源。
 * </ol>
 *
 * <p>范围（company/org/domain/zone）一律来自服务端主体，不读请求参数。
 */
@Service
public class SsoSubjectGuard {

  private final SsoSubjectMapper bindings;
  private final NavigationProjectionService navigation;

  public SsoSubjectGuard(SsoSubjectMapper bindings, NavigationProjectionService navigation) {
    this.bindings = bindings;
    this.navigation = navigation;
  }

  /**
   * 确认该主体可换取目标系统的票据。
   *
   * @throws SsoTicketService.SsoTicketException 未授权时
   */
  public void requireBound(String userId, String username, String audience) {
    String aud = audience.trim().toLowerCase();

    Map<String, Object> binding = bindings.find(userId, aud);
    if (binding != null) {
      Object enabled = binding.get("enabled");
      boolean on = enabled == null || "1".equals(String.valueOf(enabled)) || Boolean.TRUE.equals(enabled);
      if (!on) {
        throw new SsoTicketService.SsoTicketException(
            "该主体在目标系统的映射已停用: " + aud);
      }
      return;
    }

    // 无显式映射时，回退到 IAM 导航授权（最小可用、fail-closed）
    if (!navigationAllows(userId, username, aud)) {
      throw new SsoTicketService.SsoTicketException("该主体未被授权访问目标系统: " + aud);
    }
  }

  /** 解析范围。优先显式映射，其次主体自带范围。 */
  public Map<String, String> scopeFor(
      String userId, String username, String audience, String fallbackCompany, String fallbackZone) {
    Map<String, String> scope = new HashMap<>();
    Map<String, Object> binding = bindings.find(userId, audience.trim().toLowerCase());
    if (binding != null) {
      scope.put("company", str(binding.get("company_code"), fallbackCompany));
      scope.put("org", str(binding.get("org_code"), ""));
      scope.put("domain", str(binding.get("domain_code"), ""));
      scope.put("zone", str(binding.get("zone_code"), fallbackZone));
    } else {
      scope.put("company", fallbackCompany == null ? "" : fallbackCompany);
      scope.put("org", "");
      scope.put("domain", "");
      scope.put("zone", fallbackZone == null ? "" : fallbackZone);
    }
    return scope;
  }

  private boolean navigationAllows(String userId, String username, String aud) {
    try {
      // 复用既有且已验收的授权来源：该主体实际能看到的 app 集合。
      // projectNavigationTree 内部依赖 JwtAuthFilter.currentUser()，
      // 因此只能在持有登录态的请求线程中调用（换票端点满足此条件）。
      var tree = navigation.projectNavigationTree(null, false);
      if (tree == null || tree.getSystems() == null) {
        return false;
      }
      return tree.getSystems().stream()
          .anyMatch(s -> s.getAppCode() != null && s.getAppCode().equalsIgnoreCase(aud));
    } catch (Exception e) {
      // 任何异常一律保守拒绝，不 fallback 到放行
      return false;
    }
  }

  private static String str(Object value, String fallback) {
    if (value == null) return fallback == null ? "" : fallback;
    String s = String.valueOf(value);
    return s.isBlank() ? (fallback == null ? "" : fallback) : s;
  }
}
