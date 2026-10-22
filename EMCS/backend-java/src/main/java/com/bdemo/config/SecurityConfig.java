package com.bdemo.config;

import com.bdemo.auth.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                            SecurityAuditAccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                // The application logout controller revokes opaque SSO tokens; do not intercept it with a redirect.
                .logout(logout -> logout.disable())
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/captchaImage", "/error", "/actuator/health/**",
                                "/transport/crypto/frontend-config", "/transport/crypto/public-key").permitAll()
                        // 可信 SSO：exchange 必须匿名可达（主体尚未建立），
                        // 安全性由服务端IAM验票、本地主体/范围绑定及独立服务身份保证；
                        // health 是诊断端点，不含凭据。
                        .requestMatchers(HttpMethod.POST, "/api/auth/sso/exchange", "/api/auth/sso/logout-notify").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/sso/health").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/system/**").hasRole("admin")
                        .requestMatchers("/monitor/**").hasRole("admin")
                        .requestMatchers(HttpMethod.POST, "/cost/allocation-rules", "/cost/recomputations/*/review").hasRole("finance")
                        .requestMatchers(HttpMethod.POST, "/cost/tariffs", "/cost/recomputations")
                                .hasAnyRole("energy_mgr", "finance")
                        .requestMatchers("/cost/**").hasAnyRole("admin", "energy_mgr", "finance")
                        .requestMatchers("/reports/**").hasAnyRole("energy_mgr", "finance")
                        .requestMatchers(HttpMethod.POST, "/raw-quality/**").hasAnyRole("admin", "energy_mgr")
                        .requestMatchers(HttpMethod.POST, "/suggestions/**", "/alerts/*/transition")
                                .hasAnyRole("admin", "energy_mgr", "ops")
                        .requestMatchers("/agent/**").hasAnyRole("admin", "energy_mgr")
                        .requestMatchers("/pipeline/**").hasAnyRole("admin", "energy_mgr")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .accessDeniedHandler(accessDeniedHandler)
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"code\":401,\"msg\":\"未登录或登录状态已过期\"}");
                        }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
