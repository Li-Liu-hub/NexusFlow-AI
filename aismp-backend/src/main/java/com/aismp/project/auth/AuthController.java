package com.aismp.project.auth;

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
 * 门户登录鉴权（2026-10-06 批 A：从内核迁至 backend —— 登录令牌属平台用户账本）。
 *
 * <pre>
 *   POST /api/auth/login   {userCode, password} → {token, userCode, userName, role, roleLabel, warehouse…}
 *   GET  /api/auth/me      （带 token）→ 当前登录身份
 *   POST /api/auth/logout  （带 token）→ 吊销
 * </pre>
 *
 * <p>网关侧：/api/auth/** 是匿名放行的登录握手，登录成功时网关把
 * {@code portal:token:<token> → ak} 写进 Redis（计量归属），后续门户请求据此还原。
 * 令牌 24 小时有效；登录时顺手清掉该用户已过期令牌。
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
        String[] who = resolve(request);
        if (who == null) {
            return Map.of("error", "未登录");
        }
        UserPO user = auth.findUser(who[0]);
        LinkedHashMap<String, Object> ok = new LinkedHashMap<>();
        ok.put("userCode", who[0]);
        ok.put("role", who[1]);
        ok.put("userName", user == null ? who[0] : user.getUserName());
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

    private String[] resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return auth.findToken(header.substring(7).trim());
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