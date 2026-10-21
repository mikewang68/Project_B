package com.bproject.audit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

@Component
public class ServiceAuthFilter extends OncePerRequestFilter {
    public static final String IDENTITY = ServiceAuthFilter.class.getName();
    private final List<ServiceIdentity> clients;
    public ServiceAuthFilter(ObjectMapper mapper, @Value("${audit.clients-file}") String file) throws IOException {
        clients = mapper.readValue(Files.readString(Path.of(file)), new TypeReference<>() {});
        if (clients.isEmpty() || clients.stream().anyMatch(c -> c.id() == null || c.token() == null || c.token().length() < 32 || c.permissions() == null
                || c.appCode() == null || c.domainId() == null || c.zoneId() == null || (c.appCode().equals("*") && c.can("WRITE"))))
            throw new IllegalArgumentException("Invalid private audit clients configuration");
        if (clients.stream().map(ServiceIdentity::token).distinct().count() != clients.size()) throw new IllegalArgumentException("Duplicate client credential");
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/logs/v1/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > 32768) { response.sendError(413); return; }
        String auth = request.getHeader("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
        var client = clients.stream().filter(c -> MessageDigest.isEqual(c.token().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))).findFirst();
        if (client.isEmpty()) { response.setStatus(401); response.setContentType("application/json"); response.getWriter().write("{\"code\":\"UNAUTHENTICATED\"}"); return; }
        request.setAttribute(IDENTITY, client.get());
        response.setHeader("Cache-Control", "no-store");
        if (request.getMethod().equals("POST") && request.getRequestURI().endsWith("/events")) {
            byte[] bytes = request.getInputStream().readNBytes(32769);
            if (bytes.length > 32768) { response.sendError(413); return; }
            request = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                @Override public jakarta.servlet.ServletInputStream getInputStream() {
                    var stream = new java.io.ByteArrayInputStream(bytes);
                    return new jakarta.servlet.ServletInputStream() {
                        @Override public int read() { return stream.read(); }
                        @Override public boolean isFinished() { return stream.available()==0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(jakarta.servlet.ReadListener listener) { throw new UnsupportedOperationException("Synchronous log API"); }
                    };
                }
                @Override public java.io.BufferedReader getReader() { return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),StandardCharsets.UTF_8)); }
            };
        }
        chain.doFilter(request, response);
    }
}
