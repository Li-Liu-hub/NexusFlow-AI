package com.aismp.digitalhuman.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 登录鉴权拦截器。
 *
 * <p>规则（详见 {@link com.aismp.digitalhuman.config.WebMvcConfig} 的注册路径）：
 * <ul>
 *   <li>Bearer token → 查 a_auth_token（未过期）→ {@link UserPrincipal} 放进 request attribute</li>
 *   <li>/admin/** 只许 ADMIN；/agent/** 登录即可（USER/ADMIN/SERVICE 都行）</li>
 *   <li>失败响应 401（无 token / token 失效）或 403（角色不够），body 是 JSON</li>
 * </ul>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 登录身份在 request attribute 里的 key。 */
    public static final String ATTR_USER = "auth.user";

    /** 从请求里取登录身份（未走拦截器的路径返回 null）。 */
    public static UserPrincipal current(HttpServletRequest request) {
        Object v = request.getAttribute(ATTR_USER);
        return v instanceof UserPrincipal u ? u : null;
    }

    private final com.aismp.digitalhuman.repository.AuthRepository auth;

    public AuthInterceptor(com.aismp.digitalhuman.repository.AuthRepository auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 预检请求无 token 可言，直接放行（同源部署下通常不会出现，稳妥起见）
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return deny(response, 401, "未登录（缺少 Bearer token）");
        }
        String token = header.substring(7).trim();
        String[] who = auth.findToken(token);
        if (who == null) {
            return deny(response, 401, "登录已过期或 token 无效，请重新登录");
        }

        UserPrincipal principal = new UserPrincipal(who[0], who[1]);

        // 配置面只对 ADMIN 开放；SERVICE 账号例外 —— 它是 backend 管理代理的转发身份，
        // 代理上游已经校验过真人管理员登录态（双重鉴权的第一道），这里放行第二道。
        String path = request.getRequestURI();
        if (path.startsWith("/admin/") && !(principal.isAdmin() || principal.isService())) {
            return deny(response, 403, "需要管理员权限");
        }

        request.setAttribute(ATTR_USER, principal);
        return true;
    }

    private static boolean deny(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"error\":\"" + message.replace("\"", "'") + "\",\"status\":" + status + "}";
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return false;
    }
}
