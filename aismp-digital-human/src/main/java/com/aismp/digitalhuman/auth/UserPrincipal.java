package com.aismp.digitalhuman.auth;

/**
 * 请求级登录身份（由 {@link AuthInterceptor} 解析 token 后放进 request attribute）。
 *
 * @param userCode 登录名，同时是 token 计量 / 配额 / 会话隔离的 userId
 * @param role     ADMIN / USER / SERVICE（签发时的角色快照）
 */
public record UserPrincipal(String userCode, String role) {

    /** SERVICE 账号是编排层等服务：允许在请求里代传 userId。 */
    public boolean isService() {
        return "SERVICE".equals(role);
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
