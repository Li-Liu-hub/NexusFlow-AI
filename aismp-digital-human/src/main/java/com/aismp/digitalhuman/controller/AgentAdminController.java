package com.aismp.digitalhuman.controller;

import com.aismp.digitalhuman.model.AgentPO;
import com.aismp.digitalhuman.model.SkillPO;
import com.aismp.digitalhuman.model.ToolDefPO;
import com.aismp.digitalhuman.repository.AgentRepository;
import com.aismp.digitalhuman.service.AgentRegistry;
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
 * 数字员工运维接口（P1：配置即数据）。
 *
 * <p>这组接口是「创建数字员工 = 填表单 → 写库」里的「写库」部分。
 * 后续 P5 的运维界面直接消费这些接口。
 *
 * <p>注意：任何挂载关系变更都会 bump version，运行时据此热更新，无需重启。
 */
@RestController
@RequestMapping("/admin/agent")
public class AgentAdminController {

    private final AgentRepository repository;
    private final AgentRegistry registry;

    public AgentAdminController(AgentRepository repository, AgentRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    // ------------------------------------------------------------------
    // 运行时热更新
    // ------------------------------------------------------------------

    /**
     * 手动逐出缓存，下次对话即按最新配置重建。
     *
     * <p>正常流程不需要调这个 —— {@link AgentRegistry} 会自己比对 version。
     * 留着它是因为演示时「点一下立刻生效」比解释版本机制更直观。
     */
    @PostMapping("/{agentCode}/reload")
    public Map<String, Object> reload(@PathVariable String agentCode) {
        boolean evicted = registry.reload(agentCode);
        return Map.of("agentCode", agentCode, "evicted", evicted,
                "cached", registry.cachedVersions());
    }

    /** 当前运行时已缓存的数字员工及版本，用于观察热更新是否生效。 */
    @GetMapping("/runtime/cache")
    public Map<String, Integer> runtimeCache() {
        return registry.cachedVersions();
    }

    // ------------------------------------------------------------------
    // 数字员工 CRUD
    // ------------------------------------------------------------------

    @GetMapping("/list")
    public List<AgentPO> list() {
        return repository.listAgents();
    }

    @GetMapping("/{agentCode}")
    public ResponseEntity<AgentPO> get(@PathVariable String agentCode) {
        AgentPO po = repository.findByCode(agentCode);
        return po == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(po);
    }

    /**
     * 数字员工完整配置：人设 + 挂载的技能 + 挂载的工具。
     * 这就是「一条配置记录」的全貌，也是运行时装配的输入。
     */
    @GetMapping("/{agentCode}/detail")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String agentCode) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent);
        result.put("skills", repository.listSkillsOfAgent(agent.getId()));
        result.put("tools", repository.listToolsOfAgent(agent.getId()));
        result.put("roles", repository.listRolesOfAgent(agent.getId()));
        if (agent.getKnowledgeBaseId() != null) {
            result.put("knowledgeBase", repository.findKbById(agent.getKnowledgeBaseId()));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody AgentPO po) {
        Long id = repository.insertAgent(po);
        return Map.of("id", id, "agentCode", po.getAgentCode(), "version", 1);
    }

    @PutMapping("/{agentCode}")
    public Map<String, Object> update(@PathVariable String agentCode, @RequestBody AgentPO po) {
        po.setAgentCode(agentCode);
        int rows = repository.updateAgent(po);
        Integer version = repository.findVersionByCode(agentCode);
        return Map.of("updated", rows, "version", version == null ? 0 : version);
    }

    @DeleteMapping("/{agentCode}")
    public Map<String, Object> delete(@PathVariable String agentCode) {
        return Map.of("deleted", repository.deleteAgent(agentCode));
    }

    // ------------------------------------------------------------------
    // 挂载关系
    // ------------------------------------------------------------------

    @PostMapping("/{agentCode}/tool/{toolId}")
    public Map<String, Object> attachTool(@PathVariable String agentCode,
                                          @PathVariable Long toolId) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不存在: " + agentCode);
        }
        int rows = repository.attachTool(agent.getId(), toolId, 99);
        // 只在真正新增挂载时才 bump version。
        // 重复挂载是 INSERT IGNORE（影响 0 行），若也 bump 会让运行时做无谓的缓存重建。
        if (rows > 0) {
            repository.bumpVersion(agentCode);
        }
        return Map.of("attached", rows > 0, "changed", rows,
                "version", repository.findVersionByCode(agentCode));
    }

    @DeleteMapping("/{agentCode}/tool/{toolId}")
    public Map<String, Object> detachTool(@PathVariable String agentCode,
                                          @PathVariable Long toolId) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不存在: " + agentCode);
        }
        int rows = repository.detachTool(agent.getId(), toolId);
        if (rows > 0) {
            repository.bumpVersion(agentCode);
        }
        return Map.of("detached", rows > 0, "changed", rows,
                "version", repository.findVersionByCode(agentCode));
    }

    @PostMapping("/{agentCode}/skill/{skillId}")
    public Map<String, Object> attachSkill(@PathVariable String agentCode,
                                           @PathVariable Long skillId) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不存在: " + agentCode);
        }
        int rows = repository.attachSkill(agent.getId(), skillId, 99);
        if (rows > 0) {
            repository.bumpVersion(agentCode);
        }
        return Map.of("attached", rows > 0, "changed", rows,
                "version", repository.findVersionByCode(agentCode));
    }

    @DeleteMapping("/{agentCode}/skill/{skillId}")
    public Map<String, Object> detachSkill(@PathVariable String agentCode,
                                           @PathVariable Long skillId) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不存在: " + agentCode);
        }
        int rows = repository.detachSkill(agent.getId(), skillId);
        if (rows > 0) {
            repository.bumpVersion(agentCode);
        }
        return Map.of("detached", rows > 0, "changed", rows,
                "version", repository.findVersionByCode(agentCode));
    }

    // ------------------------------------------------------------------
    // 资源目录（供运维界面挑选）
    // ------------------------------------------------------------------

    @GetMapping("/catalog/skills")
    public List<SkillPO> catalogSkills() {
        return repository.listSkills();
    }

    @GetMapping("/catalog/tools")
    public List<ToolDefPO> catalogTools() {
        return repository.listAllTools();
    }

    @GetMapping("/catalog/roles")
    public List<com.aismp.digitalhuman.model.RolePO> catalogRoles() {
        return repository.listRoles();
    }

    @GetMapping("/catalog/kbs")
    public List<Map<String, Object>> catalogKbs() {
        return repository.listKbs();
    }

    /** 知识库下的文档清单（挂载前看内容，挂载后看覆盖面）。 */
    @GetMapping("/catalog/kbs/{kbId}/docs")
    public ResponseEntity<List<Map<String, Object>>> kbDocs(@PathVariable Long kbId) {
        Map<String, Object> kb = repository.findKbById(kbId);
        if (kb == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.listKbDocs(String.valueOf(kb.get("knowledge_base_id"))));
    }

    // ------------------------------------------------------------------
    // 知识库挂载（a_agent.knowledge_base_id，单挂载）
    // ------------------------------------------------------------------

    @PostMapping("/{agentCode}/kb/{kbId}")
    public Map<String, Object> attachKb(@PathVariable String agentCode, @PathVariable Long kbId) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不存在: " + agentCode);
        }
        Map<String, Object> kb = repository.findKbById(kbId);
        if (kb == null) {
            return Map.of("error", "知识库不存在: " + kbId);
        }
        repository.setAgentKnowledgeBase(agentCode, kbId);
        repository.bumpVersion(agentCode);
        return Map.of("attached", true, "kbName", kb.get("name"),
                "version", repository.findVersionByCode(agentCode));
    }

    @DeleteMapping("/{agentCode}/kb")
    public Map<String, Object> detachKb(@PathVariable String agentCode) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不存在: " + agentCode);
        }
        int rows = repository.setAgentKnowledgeBase(agentCode, null);
        if (rows > 0) {
            repository.bumpVersion(agentCode);
        }
        return Map.of("detached", rows > 0, "version", repository.findVersionByCode(agentCode));
    }

    // ------------------------------------------------------------------
    // 角色分配（a_agent_role_rel）
    // ------------------------------------------------------------------

    @PostMapping("/{agentCode}/role/{roleCode}")
    public Map<String, Object> attachRole(@PathVariable String agentCode,
                                          @PathVariable String roleCode) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不存在: " + agentCode);
        }
        int rows = repository.attachRole(agent.getId(), roleCode, 99);
        return Map.of("attached", rows > 0, "changed", rows);
    }

    @DeleteMapping("/{agentCode}/role/{roleCode}")
    public Map<String, Object> detachRole(@PathVariable String agentCode,
                                          @PathVariable String roleCode) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不存在: " + agentCode);
        }
        int rows = repository.detachRole(agent.getId(), roleCode);
        return Map.of("detached", rows > 0, "changed", rows);
    }
}
