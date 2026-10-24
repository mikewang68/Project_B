package com.mt.wms.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.ActiveAccountFilter;
import com.mt.wms.auth.AuthRepository;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                            SecurityContextRepository securityContextRepository, AuthRepository authRepository,
                                            com.mt.wms.auth.sso.WmsSsoExchangeService ssoAuthorization) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        // 可信 SSO 是服务端到服务的票据交换，不使用浏览器 Cookie 会话，
                        // 因此没有 CSRF 语义；其安全性由一次性票据 + 服务身份保证。
                        .ignoringRequestMatchers("/open/qimen/**",
                                "/api/v1/auth/sso/exchange",
                                "/api/v1/auth/sso/revoke"))
                .securityContext(context -> context.securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/health/**", "/api/v1/auth/csrf", "/api/v1/auth/login", "/open/qimen/**",
                                "/", "/assets/**", "/index.html", "/favicon.svg",
                                "/api/v1/auth/sso/exchange",
                                "/api/v1/auth/sso/revoke",
                                "/api/v1/auth/sso/health").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                objectMapper, response, HttpServletResponse.SC_UNAUTHORIZED,
                                "AUTH_UNAUTHENTICATED", "请先登录", request.getAttribute(RequestIdFilter.ATTRIBUTE)))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                objectMapper, response, HttpServletResponse.SC_FORBIDDEN,
                                "AUTH_FORBIDDEN", "无权执行此操作", request.getAttribute(RequestIdFilter.ATTRIBUTE))))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .addLogoutHandler((request,response,authentication)->{
                            var session=request.getSession(false);
                            var binding=session==null?null:com.mt.wms.auth.sso.WmsSsoLogoutRegistry.get(session.getId());
                            if(binding!=null)ssoAuthorization.globalLogout(binding.iamSubject(),binding.iamSessionId());
                        })
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpServletResponse.SC_OK);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), ApiResponse.ok(null,
                                    String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE))));
                        }))
                .sessionManagement(Customizer.withDefaults());
        http.addFilterBefore(new ActiveAccountFilter(authRepository,objectMapper),org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        http.addFilterBefore(new com.mt.wms.auth.sso.WmsReadOnlyFilter(objectMapper),org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        // 可信 SSO：在既有账号校验之前确认 IAM sid 未被撤销。
        // 只影响通过 SSO 建立的会话，原生密码登录不受影响。
        // 必须在认证解析之前执行：一旦 AuthorizationFilter 已放行，再拒绝就晚了。
        http.addFilterBefore(new com.mt.wms.auth.sso.WmsSsoSessionGuardFilter(objectMapper).withAuthorization(ssoAuthorization),
                org.springframework.security.web.context.SecurityContextHolderFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    private static void writeError(ObjectMapper objectMapper, HttpServletResponse response, int status,
                                   String code, String message, Object requestId) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message, String.valueOf(requestId)));
    }
}
