package com.aismp.digitalhuman.model;

import java.time.LocalDateTime;

/**
 * 技能包（→ a_agent_skill）。
 *
 * <p>「创建 skill」的落点：一段可复用的能力描述（提示词片段 + Few-shot 示例），
 * 挂给任意数字员工。对应 SAA 的 Agent Skills 渐进披露思路——
 * {@code description} 常驻提示词，{@code promptFragment} 按需展开。
 */
public class SkillPO {

    private Long id;
    private String skillCode;
    private String skillName;
    private String description;
    private String promptFragment;
    private String examples;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSkillCode() {
        return skillCode;
    }

    public void setSkillCode(String skillCode) {
        this.skillCode = skillCode;
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPromptFragment() {
        return promptFragment;
    }

    public void setPromptFragment(String promptFragment) {
        this.promptFragment = promptFragment;
    }

    public String getExamples() {
        return examples;
    }

    public void setExamples(String examples) {
        this.examples = examples;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
