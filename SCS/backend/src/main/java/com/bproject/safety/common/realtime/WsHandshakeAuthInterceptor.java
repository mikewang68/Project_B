package com.bproject.safety.common.realtime;

import com.bproject.safety.config.SecurityInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * /ws/live 握手鉴权（review-#1 修复）。
 *
 * <p>背景：REST 链路由 {@link SecurityInterceptor} 保护，但 WebSocket 握手走独立的
 * handler mapping、不经过 MVC 拦截器——实时事件流（告警 / 人员位置等全量推送）此前
 * 对内网完全开放（Origin 限制仅约束浏览器）。
 *
 * <p>方案：握手时委托 {@link SecurityInterceptor#preHandle} 做同款校验——
 * <ul>
 *   <li>浏览器：HttpOnly 会话 Cookie（JSESSIONID）随握手自动携带，与 REST 同一身份源，
 *       前端无需改动；</li>
 *   <li>非浏览器客户端：X-API-Key / Authorization 头照常可用；无头环境（无法设自定义头）
 *       额外支持 {@code ?token=<JWT>} 查询参数（包装为 Bearer 头后走同一校验）；</li>
 *   <li>校验通过后把主体属性（sub/roles/permissions）拷贝进 WebSocket 会话 attributes，
 *       供后续按会话主体做细粒度控制。</li>
 * </ul>
 *
 * <p>灰度开关 {@code app.security.ws-auth-enabled}（server profile 缺省 true；
 * 关闭时握手放行，兼容尚未升级的部署）。</p>
 */
public class WsHandshakeAuthInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WsHandshakeAuthInterceptor.class);

    /** 无头客户端的令牌查询参数名（包装为 Authorization: Bearer 后统一校验）。 */
    public static final String TOKEN_PARAM = "token";

    private final SecurityInterceptor security;
    private final boolean enabled;

    public WsHandshakeAuthInterceptor(SecurityInterceptor security, boolean enabled) {
        this.security = security;
        this.enabled = enabled;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!enabled) {
            return true;
        }
        if (!(request instanceof ServletServerHttpRequest servletRequest)
                || !(response instanceof ServletServerHttpResponse servletResponse)) {
            log.warn("WS 握手拒绝：非 Servlet 请求/响应类型（无法执行鉴权）");
            return false;
        }
        HttpServletRequest httpRequest = withTokenAsBearer(servletRequest.getServletRequest());
        HttpServletResponse httpResponse = servletResponse.getServletResponse();
        try {
            boolean allowed = security.preHandle(httpRequest, httpResponse, wsHandler);
            if (allowed) {
                copyPrincipal(httpRequest, attributes);
            } else {
                log.warn("WS 握手鉴权失败：remote={}", httpRequest.getRemoteAddr());
            }
            return allowed;
        } catch (Exception e) {
            log.warn("WS 握手鉴权异常，拒绝: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, @Nullable Exception exception) {
        // 无需后置处理
    }

    /** ?token=xxx 包装为 Authorization: Bearer xxx（已有 Authorization 头则不覆盖）。 */
    private static HttpServletRequest withTokenAsBearer(HttpServletRequest request) {
        String token = request.getParameter(TOKEN_PARAM);
        if (token == null || token.isBlank() || request.getHeader("Authorization") != null) {
            return request;
        }
        String bearer = "Bearer " + token.trim();
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                if ("Authorization".equalsIgnoreCase(name)) {
                    return bearer;
                }
                return super.getHeader(name);
            }
        };
    }

    private static void copyPrincipal(HttpServletRequest request, Map<String, Object> attributes) {
        Object sub = request.getAttribute(SecurityInterceptor.ATTR_USER_SUB);
        if (sub != null) {
            attributes.put(SecurityInterceptor.ATTR_USER_SUB, sub);
        }
        Object roles = request.getAttribute(SecurityInterceptor.ATTR_USER_ROLES);
        if (roles != null) {
            attributes.put(SecurityInterceptor.ATTR_USER_ROLES, roles);
        }
        Object perms = request.getAttribute(SecurityInterceptor.ATTR_USER_PERMISSIONS);
        if (perms != null) {
            attributes.put(SecurityInterceptor.ATTR_USER_PERMISSIONS, perms);
        }
    }
}
