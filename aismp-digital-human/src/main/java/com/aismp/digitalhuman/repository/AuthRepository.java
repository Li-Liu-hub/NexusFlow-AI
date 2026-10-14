package com.aismp.digitalhuman.repository;

import com.aismp.digitalhuman.model.UserPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 登录账号与令牌仓储（a_user / a_auth_token）。
 *
 * <p>2026-10-06 批 A：这两张表已迁到 <b>api_db</b>（平台账本库）——签发/管理归 backend，
 * 内核只保留「令牌校验」的只读依赖（跨库限定名查询，内核用 root 连接本机 MySQL），
 * 这样网关直连内核的 svc_orchestrator 代传链路（/digitalHuman/admin/**）不受影响。
 *
 * <p>令牌是不透明 UUID：签发即插一行，校验查一行，登出删一行。
 * 过期令牌靠登录时顺手清理（purgeExpired），不引定时任务。
 */
@Repository
public class AuthRepository {

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
                        + "FROM api_db.a_user WHERE user_code = ?",
                USER_MAPPER, userCode);
        return list.isEmpty() ? null : list.get(0);
    }

    // ------------------------------------------------------------------
    // 令牌
    // ------------------------------------------------------------------

    /** 签发令牌：UUID 落库，默认 24 小时有效。 */
    public String issueToken(String userCode, String role, int hours) {
        String token = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO api_db.a_auth_token (token, user_code, role, expires_at) VALUES (?, ?, ?, ?)",
                token, userCode, role, LocalDateTime.now().plusHours(hours));
        return token;
    }

    /** 校验令牌：存在且未过期。返回 [user_code, role]，无则 null。 */
    public String[] findToken(String token) {
        List<String[]> list = jdbc.query(
                "SELECT user_code, role FROM api_db.a_auth_token WHERE token = ? AND expires_at > NOW()",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                token);
        return list.isEmpty() ? null : list.get(0);
    }

    public int deleteToken(String token) {
        return jdbc.update("DELETE FROM api_db.a_auth_token WHERE token = ?", token);
    }

    /** 登录时顺手清理该用户的过期令牌（不引定时任务）。 */
    public void purgeExpired(String userCode) {
        jdbc.update("DELETE FROM api_db.a_auth_token WHERE user_code = ? AND expires_at <= NOW()", userCode);
    }
}
