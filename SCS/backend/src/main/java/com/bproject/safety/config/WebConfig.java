package com.bproject.safety.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：CORS 与安全拦截。
 *
 * <p>允许来源通过环境变量 CORS_ALLOWED_ORIGINS（配置键 app.cors.allowed-origins）注入，
 * 逗号分隔；生产环境经 node6 Nginx / Easegress 同源访问，开发环境默认放行本地 Vite 端口。
 * 不使用 allowedOrigins("*")。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String[] allowedOrigins;
    private final SecurityInterceptor securityInterceptor;

    public WebConfig(
            @Value("${app.cors.allowed-origins}") String allowedOrigins,
            SecurityInterceptor securityInterceptor) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        this.securityInterceptor = securityInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error");
    }
}
