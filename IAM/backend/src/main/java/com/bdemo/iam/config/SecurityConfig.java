package com.bdemo.iam.config;

import com.bdemo.iam.common.R;
import com.bdemo.iam.security.JwtAuthFilter;
import com.bdemo.iam.security.JwtService;
import com.bdemo.iam.security.LoginUserLoader;
import com.bdemo.iam.security.PermInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    private final JwtService jwtService;
    private final LoginUserLoader loginUserLoader;
    private final PermInterceptor permInterceptor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SecurityConfig(JwtService jwtService, LoginUserLoader loginUserLoader,
                          PermInterceptor permInterceptor) {
        this.jwtService = jwtService;
        this.loginUserLoader = loginUserLoader;
        this.permInterceptor = permInterceptor;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("OPTIONS", "/**").permitAll()
                        .requestMatchers("/auth/login", "/error", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已过期"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "没有访问权限")))
                .addFilterBefore(new JwtAuthFilter(jwtService, loginUserLoader),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permInterceptor).addPathPatterns("/**");
    }

    private void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(status, message)));
    }
}
