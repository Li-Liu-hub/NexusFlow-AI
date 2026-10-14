package com.aismp.digitalhuman.controller;

import com.aismp.digitalhuman.auth.AuthInterceptor;
import com.aismp.digitalhuman.auth.UserPrincipal;
import com.aismp.digitalhuman.model.UserPO;
import com.aismp.digitalhuman.repository.AuthRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录鉴权接口（唯一无需 token 的业务面）。
 *
 * <pre>
 *   POST /auth/login   {userCode, password} → {token, userCode, userName, role}
 *   GET  /auth/me      （带 token）→ 当前登录身份
 *   POST /auth/logout  （带 token）→ 吊销
 * </pre>
 *
 * <p>令牌 24 小时有效；登录时顺手清掉该用户已过期令牌。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final int TOKEN_HOURS = 24;

    private final AuthRepository auth;

    public AuthController(AuthRepository auth) {
        this.auth = auth;
    }

    public record LoginRequest(String userCode, String password) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        if (request == null || request.userCode() == null || request.userCode().isBlank()
                || request.password() == null || request.password().isBlank()) {
            return Map.of("error", "userCode 和 password 不能为空");
        }
        UserPO user = auth.findUser(request.userCode().trim());
        if (user == null || !sha256Hex(request.password()).equalsIgnoreCase(user.getPasswordHash())) {
            return Map.of("error", "账号或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            return Map.of("error", "账号已停用");
        }
        auth.purgeExpired(user.getUserCode());
        String token = auth.issueToken(user.getUserCode(), user.getRole(), TOKEN_HOURS);

        LinkedHashMap<String, Object> ok = new LinkedHashMap<>();
        ok.put("token", token);
        ok.put("userCode", user.getUserCode());
        ok.put("userName", user.getUserName());
        ok.put("role", user.getRole());
        // 业务角色（账管/仓管）+ 所属仓库：门户用它显示「东宁市分公司账管」并决定能看哪些工单
        ok.put("roleLabel", user.roleLabel());
        ok.put("warehouseCode", user.getWarehouseCode());
        ok.put("warehouseName", user.getWarehouseName());
        ok.put("expiresInHours", TOKEN_HOURS);
        return ok;
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest request) {
        UserPrincipal principal = AuthInterceptor.current(request);
        if (principal == null) {
            return Map.of("error", "未登录");
        }
        UserPO user = auth.findUser(principal.userCode());
        LinkedHashMap<String, Object> ok = new LinkedHashMap<>();
        ok.put("userCode", principal.userCode());
        ok.put("role", principal.role());
        ok.put("userName", user == null ? principal.userCode() : user.getUserName());
        ok.put("roleLabel", user == null ? "" : user.roleLabel());
        ok.put("warehouseCode", user == null ? null : user.getWarehouseCode());
        ok.put("warehouseName", user == null ? null : user.getWarehouseName());
        return ok;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            int removed = auth.deleteToken(header.substring(7).trim());
            return Map.of("loggedOut", removed > 0);
        }
        return Map.of("loggedOut", false);
    }

    /** SHA-256 hex（与迁移脚本里的 SHA2(password, 256) 对齐）。 */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
