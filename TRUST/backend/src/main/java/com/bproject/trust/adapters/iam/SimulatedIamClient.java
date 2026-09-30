package com.bproject.trust.adapters.iam;

import com.bproject.trust.identity.IntegrationSettings;
import com.bproject.trust.ports.PlatformIdentity;
import com.bproject.trust.shared.web.ApiError;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Restricted in-process identity simulation for isolated integration testing only.
 *
 * <p>Activated exclusively by {@code iamMode: "simulated"} in the deployment-owned
 * {@code integration.json}. The simulated identity source is explicitly labelled on the
 * page and is never used in production configurations. Local development accounts are
 * not automatically promoted to wallet administrators.
 */
@Component
public class SimulatedIamClient implements PlatformIdentity {
  private final IntegrationSettings settings;
  private final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, SimUser> users = new ConcurrentHashMap<>();
  private volatile boolean available = true;

  public SimulatedIamClient(IntegrationSettings settings) {
    this.settings = settings;
    addUser("u-applicant", "wallet-applicant", "fixture-applicant", "ORG-A",
        Set.of("trust:wallet:read", "trust:wallet:manage", "trust:event:read", "trust:evidence:read"));
    addUser("u-reviewer", "wallet-reviewer", "fixture-reviewer", "ORG-A",
        Set.of("trust:wallet:read", "trust:wallet:review", "trust:event:read", "trust:evidence:read"));
    addUser("u-noperm", "no-permission", "fixture-noperm", "ORG-A", Set.of());
    addUser("u-otherorg", "other-org", "fixture-otherorg", "ORG-B",
        Set.of("trust:wallet:read", "trust:wallet:manage"));
    addUser("u-otherreview", "other-org-reviewer", "fixture-otherreview", "ORG-B",
        Set.of("trust:wallet:read", "trust:wallet:review"));
    addUser("u-selfreview", "wallet-self-reviewer", "fixture-selfreview", "ORG-A",
        Set.of("trust:wallet:read", "trust:wallet:manage", "trust:wallet:review"));
    addUser("u-operator", "business-operator", "fixture-operator", "ORG-A",
        Set.of("trust:event:read", "trust:event:submit", "trust:event:correct",
            "trust:evidence:read", "trust:evidence:upload", "trust:evidence:verify", "trust:evidence:export",
            "trust:task:read", "trust:task:retry", "trust:operations:read"));
  }

  /** Test hook: simulate identity service outage (IAM unreachable). */
  public void setAvailable(boolean available) {
    if (!simulated()) throw new ApiError(503, "身份模拟服务仅在隔离测试配置中启用");
    this.available = available;
  }

  public boolean isAvailable() {
    return available;
  }

  private boolean simulated() {
    return settings.read().simulated();
  }

  @Override
  public String login(String username, String password) {
    if (!simulated()) throw new ApiError(503, "身份模拟服务仅在隔离测试配置中启用");
    if (!available) throw new ApiError(503, "身份服务暂不可用，请稍后重试");
    var user = findUser(username);
    if (user == null || !user.password.equals(password))
      throw new ApiError(401, "IAM 身份无效、已停用或凭据错误");
    String token = "sim-" + java.util.UUID.randomUUID().toString().replace("-", "");
    sessions.put(token, username);
    return token;
  }

  @Override
  public User current(String token) {
    if (!simulated()) throw new ApiError(503, "身份模拟服务仅在隔离测试配置中启用");
    if (!available) throw new ApiError(503, "IAM 暂不可用，请稍后重试");
    String username = sessions.get(token);
    if (username == null) throw new ApiError(401, "IAM 身份已失效");
    var user = findUser(username);
    if (user == null) throw new ApiError(401, "IAM 用户不可用");
    return new User(user.id, user.username, user.orgCodes, user.permissions);
  }

  /** Test hook: change a user's permissions or org codes without redeploying. */
  public void updatePermissions(String username, Set<String> permissions, List<String> orgCodes) {
    if (!simulated()) throw new ApiError(503, "身份模拟服务仅在隔离测试配置中启用");
    var user = findUser(username);
    if (user == null) throw new ApiError(404, "模拟用户不存在");
    users.put(username, new SimUser(user.id, username, user.password, orgCodes, permissions));
  }

  private static final class SimUser {
    final String id, username, password;
    final List<String> orgCodes;
    final Set<String> permissions;

    SimUser(String id, String username, String password, List<String> orgCodes, Set<String> permissions) {
      this.id = id;
      this.username = username;
      this.password = password;
      this.orgCodes = List.copyOf(orgCodes);
      this.permissions = Set.copyOf(permissions);
    }
  }

  private void addUser(String id, String username, String password, String orgCode, Set<String> permissions) {
    users.put(username, new SimUser(id, username, password, List.of(orgCode), permissions));
  }

  private SimUser findUser(String username) {
    return username == null ? null : users.get(username);
  }
}
