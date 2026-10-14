package com.aismp.project.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 登录账号与令牌仓储（a_user / a_auth_token，表在 api_db）。
 *
 * <p>2026-10-06 批 A：从内核（aismp-digital-human）迁来 —— 登录令牌的签发/校验/吊销
 * 属于「平台用户账本」，与 AK/SK 同层，统一由 backend 负责；内核只保留只读校验
 * （跨库读这两张表，见其 AuthRepository 注释）。
 *
 * <p>令牌是不透明 UUID：签发即插一行，校验查一行，登出删一行；
 * 过期令牌靠登录时顺手清理（purgeExpired），不引定时任务。
 */
@Repository
public class AuthRepository {

    private static final Logger log = LoggerFactory.getLogger(AuthRepository.class);

    private static final RowMapper<UserPO> USER_MAPPER = (rs, i) -> {
        UserPO u = new UserPO();
        u.setId(rs.getLong("id"));
        u.setUserCode(rs.getString("user_code"));
        u.setUserName(rs.getString("user_name"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setRole(rs.getString("role"));
        u.setStatus(rs.getString("status"));
        u.setWarehouseCode(rs.getString("warehouse_code"));
        u.setWarehouseName(rs.getString("warehouse_name"));
        Timestamp t = rs.getTimestamp("created_at");
        u.setCreatedAt(t == null ? null : t.toLocalDateTime());
        return u;
    };

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 账号
    // ------------------------------------------------------------------

    public UserPO findUser(String userCode) {
        List<UserPO> list = jdbc.query(
                "SELECT id, user_code, user_name, password_hash, role, status, created_at, "
                        + "warehouse_code, warehouse_name "
                        + "FROM a_user WHERE user_code = ?",
                USER_MAPPER, userCode);
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------------
    // 令牌
    // ------------------------------------------------------------------

    /** 签发令牌：UUID 落库，默认 24 小时有效。 */
    public String issueToken(String userCode, String role, int hours) {
        String token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO a_auth_token (token, user_code, role, expires_at) VALUES (?, ?, ?, ?)",
                token, userCode, role, LocalDateTime.now().plusHours(hours));
        return token;
    }

    /** 校验令牌：存在且未过期。返回 [user_code, role]，无则 null。 */
    public String[] findToken(String token) {
        List<String[]> list = jdbc.query(
                "SELECT user_code, role FROM a_auth_token WHERE token = ? AND expires_at > NOW()",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                token);
        return list.isEmpty() ? null : list.get(0);
    }

    public int deleteToken(String token) {
        return jdbc.update("DELETE FROM a_auth_token WHERE token = ?", token);
    }

    /** 登录时顺手清理该用户的过期令牌（不引定时任务）。 */
    public void purgeExpired(String userCode) {
        jdbc.update("DELETE FROM a_auth_token WHERE user_code = ? AND expires_at <= NOW()", userCode);
    }

    // ------------------------------------------------------------------
    // 请求级守卫（backup for /admin/token/**：登录态 + ADMIN/SERVICE 角色）
    // ------------------------------------------------------------------

    /**
     * 校验 Authorization: Bearer 令牌并检查角色。
     *
     * @return null = 放行；否则返回给前端的错误说明
     */
    public String checkAdmin(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return "未登录（缺少 Bearer token）";
        }
        String[] who = findToken(authorizationHeader.substring("Bearer ".length()).trim());
        if (who == null) {
            return "登录已过期或 token 无效，请重新登录";
        }
        if (!"ADMIN".equals(who[1]) && !"SERVICE".equals(who[1])) {
            return "需要管理员权限";
        }
        return null;
    }

    /** 管理面兜底日志：记录一次守卫拒绝，便于排查（不外抛）。 */
    public void warnDenied(String path, String reason) {
        log.warn("管理面守卫拒绝 path={} reason={}", path, reason);
    }
}