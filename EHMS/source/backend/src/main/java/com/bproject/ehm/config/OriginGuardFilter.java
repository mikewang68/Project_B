package com.bproject.ehm.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OriginGuardFilter extends OncePerRequestFilter {
    private final Set<String> allowedOrigins;

    public OriginGuardFilter(@Value("${ehm.cors.allowed-origins:http://localhost:8088,http://127.0.0.1:8088}") String origins) {
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && !allowedOrigins.contains(origin)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin is not allowed");
            return;
        }
        chain.doFilter(request, response);
    }
}
