package com.bdemo.iam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Ai ai = new Ai();

    public Jwt getJwt() {
        return jwt;
    }

    public Cors getCors() {
        return cors;
    }

    public Ai getAi() {
        return ai;
    }

    public static class Jwt {
        /** HS256 密钥；IAM/SYS 必须相同，生产由环境变量 JWT_SECRET 注入 */
        private String secret = "dev-only-iam-sys-jwt-secret-please-change-in-production-0123456789";
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
