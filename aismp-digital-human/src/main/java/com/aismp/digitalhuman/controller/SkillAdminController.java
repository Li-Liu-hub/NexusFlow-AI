package com.aismp.digitalhuman.controller;

import com.aismp.digitalhuman.model.SkillPO;
import com.aismp.digitalhuman.repository.AgentRepository;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能包管理（管理面）：a_agent_skill 的 CRUD。
 *
 * <p>技能 = 一段可复用的能力描述（{@code description} 常驻提示词 +
 * {@code promptFragment} 按需展开 + {@code examples} Few-shot 示例），
 * 挂载关系在 {@link AgentAdminController} 的 {@code /{agentCode}/skill/{id}}。
 *
 * <p><b>为什么删改技能要 bump 数字员工 version</b>：技能片段会拼进装配后的系统提示词，
 * 只改 a_agent_skill 表格不会让 {@code AgentRegistry} 察觉 —— 必须把引用它的
 * 数字员工 version 逐个自增，运行时才会自动重建缓存（与 AgentAdminController 的
 * 「保存即热更新」语义一致）。
 */
@RestController
@RequestMapping("/admin/skill")
public class SkillAdminController {

    @Resource
    private AgentRepository repository;

    @GetMapping("/list")
    public List<SkillPO> list() {
        return repository.listSkills();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SkillPO> get(@PathVariable Long id) {
        SkillPO po = repository.findSkillById(id);
        return po == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(po);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody SkillPO po) {
        if (po.getSkillCode() == null || po.getSkillCode().isBlank()) {
            return Map.of("error", "技能编码（skillCode）必填");
        }
        if (po.getSkillName() == null || po.getSkillName().isBlank()) {
            return Map.of("error", "技能名（skillName）必填");
        }
        try {
            Long id = repository.insertSkill(po);
            return Map.of("id", id, "skillCode", po.getSkillCode(), "created", 1);
        } catch (DuplicateKeyException e) {
            // a_agent_skill.skill_code 是唯一键：重复创建给明确提示，而不是 500
            return Map.of("error", "技能编码已存在：" + po.getSkillCode());
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody SkillPO po) {
        SkillPO existing = repository.findSkillById(id);
        if (existing == null) {
            return Map.of("error", "技能不存在: " + id);
        }
        po.setId(id);
        if (po.getStatus() == null) {
            po.setStatus(existing.getStatus());
        }
        int rows = repository.updateSkill(po);
        List<String> affected = repository.findAgentsUsingSkill(id);
        affected.forEach(repository::bumpVersion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", rows);
        result.put("affectedAgents", affected);
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        SkillPO existing = repository.findSkillById(id);
        if (existing == null) {
            return Map.of("error", "技能不存在: " + id);
        }
        // 先记录影响面（detach 之后就查不到了），再摘挂载、删技能、逐个数 bump
        List<String> affected = repository.findAgentsUsingSkill(id);
        int detached = repository.detachSkillEverywhere(id);
        int deleted = repository.deleteSkill(id);
        affected.forEach(repository::bumpVersion);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("detachedRelations", detached);
        result.put("affectedAgents", affected);
        return result;
    }
}