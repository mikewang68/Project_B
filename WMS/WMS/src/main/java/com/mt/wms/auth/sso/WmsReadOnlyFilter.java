package com.mt.wms.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.auth.WmsReadOnlyPolicy;
import com.mt.wms.common.api.ApiResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** A local read-only business account stays read-only even after an accidental permission grant. */
public final class WmsReadOnlyFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    public WmsReadOnlyFilter(ObjectMapper mapper){this.mapper=mapper;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        String path=WmsReadOnlyPolicy.requestPath(request);
        if(path.startsWith("/api/")&&WmsReadOnlyPolicy.denies(request.getMethod(),path)
            &&authentication!=null&&authentication.getPrincipal() instanceof WmsPrincipal principal
            &&principal.readOnly()) {
            response.setStatus(403);response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getOutputStream(),ApiResponse.error("AUTH_READONLY","只读账号不允许写操作",null));return;
        }
        chain.doFilter(request,response);
    }
}
