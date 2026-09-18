package com.bdemo.iam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();

    public Jwt getJwt() {
        return jwt;
    }

    public Cors getCors() {
        return cors;
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
}
