package com.aismp.digitalhuman.model;

import java.time.LocalDateTime;

/**
 * 业务角色字典（a_role_def）。
 *
 * <p>角色在这里是「 portal 用户切换器」里的一等公民：管理员把数字员工分配给角色，
 * 用户端按角色拉取可用数字员工列表，智能路由在角色过滤后的候选集里做意图分流。
 */
public class RolePO {

    private Long id;
    private String roleCode;
    private String roleName;
    private String description;
    private Integer sortOrder;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
