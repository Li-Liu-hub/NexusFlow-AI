package com.aismp.project.token;

import java.time.LocalDateTime;

/**
 * token 月度配额（a_token_quota，表在 api_db）。
 *
 * <p>scope 决定 target_code 的解释：USER → user_id；AGENT → agent_code；TENANT → ent_code。
 * monthly_tokens = 0 表示封禁；本期配额校验落地的是 USER 维度（用量表的落账粒度）。
 */
public class QuotaPO {

    private Long id;
    private String scope;
    private String targetCode;
    private Long monthlyTokens;
    private String status;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getTargetCode() { return targetCode; }
    public void setTargetCode(String targetCode) { this.targetCode = targetCode; }
    public Long getMonthlyTokens() { return monthlyTokens; }
    public void setMonthlyTokens(Long monthlyTokens) { this.monthlyTokens = monthlyTokens; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}