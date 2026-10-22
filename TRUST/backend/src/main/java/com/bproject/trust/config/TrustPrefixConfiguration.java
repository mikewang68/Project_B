package com.bproject.trust.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

/** Fixed same-application alias, normalized before security and auditing, never from proxy headers. */
@Configuration
public class TrustPrefixConfiguration {
  @Bean
  FilterRegistrationBean<PrefixFilter> trustPrefix() {
    var registration = new FilterRegistrationBean<>(new PrefixFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
    registration.setDispatcherTypes(DispatcherType.REQUEST);
    return registration;
  }

  public static final class PrefixFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      var req = (HttpServletRequest) request;
      var res = (HttpServletResponse) response;
      String uri = req.getRequestURI();
      if (!req.getContextPath().isEmpty() || !(uri.equals("/trust") || uri.startsWith("/trust/"))) {
        chain.doFilter(req, res);
        return;
      }
      if (uri.equals("/trust")) {
        res.setStatus(308);
        res.setHeader("Location", "/trust/" + (req.getQueryString() == null ? "" : "?" + req.getQueryString()));
        return;
      }
      // Serve the entry document directly; a container welcome-page forward can
      // reintroduce the original prefix after the REQUEST-only filter has run.
      String normalized = uri.equals("/trust/") ? "/index.html" : uri.substring(6);
      chain.doFilter(new HttpServletRequestWrapper(req) {
        @Override public String getRequestURI() { return normalized; }
        @Override public String getServletPath() {
          String path = super.getServletPath();
          return path.equals("/trust/") ? "/index.html"
              : path.startsWith("/trust/") ? path.substring(6) : path;
        }
        @Override public String getPathInfo() {
          String path = super.getPathInfo();
          return "/trust/".equals(path) ? "/index.html"
              : path != null && path.startsWith("/trust/") ? path.substring(6) : path;
        }
        @Override public StringBuffer getRequestURL() {
          String original = super.getRequestURL().toString();
          return new StringBuffer(original.substring(0, original.length() - uri.length()) + getRequestURI());
        }
      }, res);
    }
  }
}
