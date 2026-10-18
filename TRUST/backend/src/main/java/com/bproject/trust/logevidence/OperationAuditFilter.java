package com.bproject.trust.logevidence;

import com.bproject.trust.identity.PlatformPrincipal;
import com.bproject.trust.shared.json.Json;
import com.bproject.trust.shared.web.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Persist request intent before controller execution; HTTP outcome is not a ledger outcome. */
public class OperationAuditFilter extends OncePerRequestFilter {
  private final LogOperations ops;

  public OperationAuditFilter(LogOperations ops) {
    this.ops = ops;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    String path = req.getRequestURI();
    String pageId = req.getHeader("X-TRUST-Page-Action-Id");
    if (!(auth != null && auth.getPrincipal() instanceof PlatformPrincipal)
        || !path.startsWith("/api/v1/")
        || path.startsWith("/api/v1/integrations/")
        || path.startsWith("/api/v1/audit/page-actions")
        || path.equals("/api/v1/csrf")
        || path.equals("/api/v1/identity-mode")) {
      chain.doFilter(req, res);
      return;
    }
    PlatformPrincipal p = (PlatformPrincipal) auth.getPrincipal();
    String trace = pageId == null ? UUID.randomUUID().toString() : pageId;
    String action =
        "GET".equals(req.getMethod()) ? "READ" : path.endsWith("/logout") ? "LOGOUT" : "EXECUTE";
    try {
      if (pageId != null) {
        UUID.fromString(pageId);
        var rows = ops.db.queryForList("SELECT * FROM audit_page_action WHERE id=?", pageId);
        if (rows.isEmpty()) throw LogEvidenceService.missing();
        LogOperations.pageOwner(rows.get(0), p);
        req.setAttribute("TRUST_VERIFIED_PAGE_ACTION", pageId);
      }
      ops.enqueue(
          p.orgId(),
          p.userId(),
          action,
          "",
          "STARTED",
          "REQUEST",
          "",
          req.getMethod(),
          path,
          "",
          trace);
    } catch (Exception e) {
      try {
        ops.rejected(auth, req.getMethod(), path);
      } catch (Exception persistenceFailure) {
        error(res, persistenceFailure);
        return;
      }
      error(res, e);
      return;
    }
    var wrapped = new ContentCachingResponseWrapper(res);
    try {
      chain.doFilter(req, wrapped);
      String outcome =
          wrapped.getStatus() == 401 || wrapped.getStatus() == 403
              ? "DENIED"
              : wrapped.getStatus() >= 400 ? "FAILED" : "SUCCESS";
      ops.enqueue(
          p.orgId(),
          p.userId(),
          action,
          "",
          outcome,
          "REQUEST",
          "",
          req.getMethod(),
          path,
          "HTTP outcome only",
          trace);
      wrapped.copyBodyToResponse();
    } catch (Exception e) {
      // STARTED remains durable if outcome persistence fails: never manufacture a successful
      // result.
      if (!res.isCommitted()) {
        res.resetBuffer();
        error(res, e);
      } else if (e instanceof ServletException se) throw se;
      else throw new ServletException(e);
    }
  }

  private static void error(HttpServletResponse res, Exception e) throws IOException {
    res.setStatus(e instanceof ApiError a ? a.status : 503);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter()
        .write(
            Json.write(
                Map.of(
                    "code",
                    "AUDIT_OPERATION_UNCONFIRMED",
                    "message",
                    "操作留痕未确认，请先查询实际业务状态，勿换号重复提交")));
  }
}
