package com.bdemo.iam.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

  private final Jwt jwt = new Jwt();
  private final Cors cors = new Cors();
  private final Ai ai = new Ai();
  private final Sso sso = new Sso();

  public Ai getAi() { return ai; }

  public Sso getSso() {
    return sso;
  }

  public Jwt getJwt() {
    return jwt;
  }

  public Cors getCors() {
    return cors;
  }

  public static class Jwt {
    /** IAM 独立 HS256 密钥，由环境变量 JWT_SECRET 注入 */
    private String secret = "";

    private int expireHours = 12;

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }

    public int getExpireHours() {
      return expireHours;
    }

    public void setExpireHours(int expireHours) {
      this.expireHours = expireHours;
    }
  }

  public static class Cors {
    private List<String> allowedOrigins = new ArrayList<>();

    public List<String> getAllowedOrigins() {
      return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
      this.allowedOrigins = allowedOrigins;
    }
  }
  public static class Sso {
    /**
     * SSO V1 票据签名密钥（HS256）。独立于 app.jwt.secret；由环境变量 SSO_TICKET_SECRET 注入。
     * 未配置或长度不足 32 时，票据服务 fail-closed（不签发、不校验）。
     */
    private String ticketSecret = "";

    /** 票据签发者标识，目标系统必须校验一致。 */
    private String issuer = "project-b-iam";

    /** 票据有效期（秒），上限 60。 */
    private int ticketTtlSeconds = 45;

    /** 允许通过 BFF 换票的目标系统。 */
    private List<String> allowedAudience =
        new ArrayList<>(List.of("iam", "sys", "wms", "psms", "scs", "ehms", "emcs", "trust"));

    /**
     * 目标后端的服务身份凭据（V1.1）：逗号分隔的 {@code audience=token}。
     * 未配置时集中式消费端点整体拒绝。凭据不得入 Git、不得打印。
     */
    private String serviceTokens = "";

    public String getTicketSecret() {
      return ticketSecret;
    }

    public void setTicketSecret(String ticketSecret) {
      this.ticketSecret = ticketSecret;
    }

    public String getIssuer() {
      return issuer;
    }

    public void setIssuer(String issuer) {
      this.issuer = issuer;
    }

    public int getTicketTtlSeconds() {
      return ticketTtlSeconds;
    }

    public void setTicketTtlSeconds(int ticketTtlSeconds) {
      this.ticketTtlSeconds = ticketTtlSeconds;
    }

    public List<String> getAllowedAudience() {
      return allowedAudience;
    }

    public String getServiceTokens() {
      return serviceTokens;
    }

    public void setServiceTokens(String serviceTokens) {
      this.serviceTokens = serviceTokens;
    }

    /**
     * 各业务系统的注销通知地址，格式 {@code aud=url;aud=url}。
     *
     * <p>不假设所有系统共用同一路径——WMS/SCS/EHMS 的接口可能不同名，
     * 因此按受众逐个登记。未登记的受众在注销时会被记为"投递失败"并可重试，
     * 而不是静默跳过。
     */
    private String logoutEndpoints = "";

    /** 注销通知的 HTTP 超时（毫秒）。 */
    private long logoutTimeoutMs = 3000;

    /** 后台重试间隔（秒）；0 表示只在前台投递一次。 */
    private long logoutRetrySeconds = 60;

    public String getLogoutEndpoints() {
      return logoutEndpoints;
    }

    public void setLogoutEndpoints(String logoutEndpoints) {
      this.logoutEndpoints = logoutEndpoints;
    }

    public long getLogoutTimeoutMs() {
      return logoutTimeoutMs;
    }

    public void setLogoutTimeoutMs(long logoutTimeoutMs) {
      this.logoutTimeoutMs = logoutTimeoutMs;
    }

    public long getLogoutRetrySeconds() {
      return logoutRetrySeconds;
    }

    public void setLogoutRetrySeconds(long logoutRetrySeconds) {
      this.logoutRetrySeconds = logoutRetrySeconds;
    }

    public void setAllowedAudience(List<String> allowedAudience) {
      this.allowedAudience = allowedAudience;
    }
  }

  /**
     * AI 能力开关。当前仅支持 disabled（规则驱动），未来可无侵入增加 deepseek。
     * 不配置任何 AI key 也能正常启动。
     */
    public static class Ai {
        private boolean enabled = false;
        private String provider = "disabled";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }
}
