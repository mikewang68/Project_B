package com.mt.wms.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                                            SecurityContextRepository securityContextRepository) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers("/open/qimen/**"))
                .securityContext(context -> context.securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/health/**", "/api/v1/auth/csrf", "/api/v1/auth/login", "/open/qimen/**",
                                "/", "/assets/**", "/index.html", "/favicon.svg").permitAll()
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
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpServletResponse.SC_OK);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), ApiResponse.ok(null,
                                    String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE))));
                        }))
                .sessionManagement(Customizer.withDefaults());
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
