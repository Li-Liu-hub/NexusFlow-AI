package com.aismp.project.auth;

import java.time.LocalDateTime;

/**
 * 门户登录账号（a_user，2026-10-06 起表在 api_db —— 平台账本与 AI 内核库分离）。
 *
 * <p>role 分两级：
 * <ul>
 *   <li><b>平台级</b>：ADMIN（管理台 /admin/**）、USER（门户运行面）、SERVICE（服务间调用）</li>
 *   <li><b>业务级</b>：ACCOUNT_MANAGER（账管）/ WAREHOUSE_KEEPER（仓管），叠加 warehouseCode</li>
 * </ul>
 */
public class UserPO {

    private Long id;
    private String userCode;
    private String userName;
    private String passwordHash;
    private String role;
    private String status;
    private LocalDateTime createdAt;
    private String warehouseCode;
    private String warehouseName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserCode() { return userCode; }
    public void setUserCode(String userCode) { this.userCode = userCode; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getWarehouseCode() { return warehouseCode; }
    public void setWarehouseCode(String warehouseCode) { this.warehouseCode = warehouseCode; }
    public String getWarehouseName() { return warehouseName; }
    public void setWarehouseName(String warehouseName) { this.warehouseName = warehouseName; }

    /** 业务角色的中文标签（前端头像旁显示「东宁市分公司账管」）。平台级角色返回空串。 */
    public String roleLabel() {
        if (role == null) return "";
        return switch (role) {
            case "ACCOUNT_MANAGER" -> "账管";
            case "WAREHOUSE_KEEPER" -> "仓管";
            default -> "";
        };
    }
}