package com.aismp.digitalhuman.model;

import java.time.LocalDateTime;

/**
 * 登录账号（a_user）。
 *
 * <p>role 分两级：
 * <ul>
 *   <li><b>平台级</b>（决定能进哪一面）：
 *     <ul>
 *       <li>ADMIN —— 管理台（配置面 /admin/**）</li>
 *       <li>USER —— 门户（运行面 /agent/**）</li>
 *       <li>SERVICE —— 服务间调用（编排层）。允许在请求里代传 userId，
 *           把终端用户身份透传下去；真人 token 一律以登录身份为准。</li>
 *     </ul>
 *   </li>
 *   <li><b>业务级</b>（调拨工单闭环，2026-10-06 起）：
 *     <ul>
 *       <li>ACCOUNT_MANAGER —— 账管：管账面/发起与确认调拨工单</li>
 *       <li>WAREHOUSE_KEEPER —— 仓管：管实物/执行出库（确认工单后库存才动）</li>
 *     </ul>
 *     业务角色同样通过 /agent/** 运行面鉴权（拦截器只对 /admin/** 卡 ADMIN），
 *     再叠加 warehouseCode（所属仓位）——**每个仓库各有一个账管与一个仓管**。
 *   </li>
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
    /** 所属仓位编码（业务角色用；平台账号为 null） */
    private String warehouseCode;
    /** 所属仓位名（冗余，前端直接显示） */
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

    /**
     * 业务角色的中文标签（前端头像旁显示「东宁市分公司账管」这种）。
     * 平台级角色返回空串 —— 它们没有仓库身份。
     */
    public String roleLabel() {
        if (role == null) return "";
        return switch (role) {
            case "ACCOUNT_MANAGER" -> "账管";
            case "WAREHOUSE_KEEPER" -> "仓管";
            default -> "";
        };
    }
}
