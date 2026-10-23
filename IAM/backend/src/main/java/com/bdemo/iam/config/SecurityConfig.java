package com.bdemo.iam.config;

import com.bdemo.iam.common.R;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.JwtService;
import com.bdemo.iam.security.LoginUserLoader;
import com.bdemo.iam.security.PermInterceptor;
import com.bdemo.iam.security.ReadonlyHardVetoInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

  private final JwtService jwtService;
  private final LoginUserLoader loginUserLoader;
  private final PermInterceptor permInterceptor;
  private final ReadonlyHardVetoInterceptor readonlyHardVetoInterceptor;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public SecurityConfig(
      JwtService jwtService,
      LoginUserLoader loginUserLoader,
      PermInterceptor permInterceptor,
      ReadonlyHardVetoInterceptor readonlyHardVetoInterceptor) {
    this.jwtService = jwtService;
    this.loginUserLoader = loginUserLoader;
    this.permInterceptor = permInterceptor;
    this.readonlyHardVetoInterceptor = readonlyHardVetoInterceptor;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, com.bdemo.iam.sso.SsoAuthorizationService nativeAuthorization) throws Exception {
    http.csrf(csrf -> csrf.disable())
        // JWT 无状态 API：显式关闭表单登录、HTTP Basic 与默认登出，避免 Spring Security 默认认证入口残留
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .cors(cors -> {})
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers("/auth/login", "/health", "/error", "/actuator/health")
                    .permitAll()
                    // SSO 服务间校验端点：鉴权由票据签名 + 一次性消费完成，
                    // 不依赖 IAM 登录态（调用方是目标业务系统的后端）。
                    .requestMatchers("/auth/sso/verify-and-consume", "/auth/sso/session/introspect", "/auth/sso/session/logout", "/auth/sso/exchange", "/auth/sso/native/revoke")
                    .permitAll()
                    // 诊断端点：只返回契约版本、受众列表与是否已配置服务身份，
                    // 不含任何密钥或凭据。
                    .requestMatchers("/auth/sso/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            eh ->
                eh.authenticationEntryPoint(
                        (request, response, ex) ->
                            writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已过期"))
                    .accessDeniedHandler(
                        (request, response, ex) ->
                            writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "没有访问权限")))
        .addFilterBefore(
            new JwtAuthFilter(jwtService, loginUserLoader).withNativeAuthorization(nativeAuthorization),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(readonlyHardVetoInterceptor).addPathPatterns("/**");
    registry.addInterceptor(permInterceptor).addPathPatterns("/**");
  }

  @Bean
  public org.springframework.boot.web.servlet.ServletListenerRegistrationBean<com.bdemo.iam.sso.IamNativeSessions>
      nativeSessionLifecycle(com.bdemo.iam.sso.IamNativeSessions sessions) {
    return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(sessions);
  }

  private void writeError(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response, int status, String message)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    int businessCode = status;
    String uri = request != null ? request.getRequestURI() : "";
    if (uri != null && uri.contains("/navigation/")) {
      if (status == HttpServletResponse.SC_UNAUTHORIZED) {
        businessCode = 40101;
      } else if (status == HttpServletResponse.SC_FORBIDDEN) {
        businessCode = 40301;
      }
    }
    response.getWriter().write(objectMapper.writeValueAsString(R.fail(businessCode, message)));
  }
}
