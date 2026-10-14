package com.aismp.digitalhuman.controller;

import com.aismp.digitalhuman.model.McpServerPO;
import com.aismp.digitalhuman.repository.McpServerRepository;
import com.aismp.digitalhuman.service.McpClientService;
import jakarta.annotation.Resource;
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
 * MCP 服务管理（管理面，批 E 2026-10-06）：a_mcp_server 的 CRUD + 探测。
 *
 * <p><b>探测（probe）是这里的关键动作</b>：连接远端 MCP server、完成 initialize 握手、
 * 列出工具清单（含 inputSchema），并把结果落账到 last_sync_time / last_error。
 * 管理台拿到清单后可「一键生成工具定义」—— 生成的 a_llm_tool（tool_type=MCP）
 * 再到「数字员工配置」页挂载即可被模型调用。
 *
 * <p>删除保护：被工具定义引用（config_json 里出现 serverCode）时拒绝删除，
 * 避免留下悬空引用（装配时才报错就更难排查）。
 */
@RestController
@RequestMapping("/admin/mcp")
public class McpAdminController {

    @Resource
    private McpServerRepository repository;

    @Resource
    private McpClientService clientService;

    @GetMapping("/list")
    public List<McpServerPO> list() {
        return repository.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<McpServerPO> get(@PathVariable Long id) {
        McpServerPO po = repository.findById(id);
        return po == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(po);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody McpServerPO po) {
        String err = validatePo(po);
        if (err != null) {
            return Map.of("error", err);
        }
        if (repository.findByCode(po.getServerCode()) != null) {
            return Map.of("error", "服务编码已存在：" + po.getServerCode());
        }
        normalize(po);
        repository.insert(po);
        McpServerPO saved = repository.findByCode(po.getServerCode());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", saved.getId());
        result.put("serverCode", saved.getServerCode());
        result.put("created", 1);
        return result;
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody McpServerPO po) {
        McpServerPO existing = repository.findById(id);
        if (existing == null) {
            return Map.of("error", "MCP 服务不存在: " + id);
        }
        po.setId(id);
        po.setServerCode(existing.getServerCode());  // 编码不可改：工具定义按它引用
        String err = validatePo(po);
        if (err != null) {
            return Map.of("error", err);
        }
        normalize(po);
        int rows = repository.update(po);
        return Map.of("updated", rows);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        McpServerPO existing = repository.findById(id);
        if (existing == null) {
            return Map.of("error", "MCP 服务不存在: " + id);
        }
        int refs = repository.countToolsReferencing(existing.getServerCode());
        if (refs > 0) {
            return Map.of("error", "还有 " + refs + " 个工具定义引用该服务（config_json），请先删除工具");
        }
        int rows = repository.delete(id);
        return Map.of("deleted", rows);
    }

    /**
     * 探测远端工具清单。成功落 last_sync_time，失败落 last_error ——
     * 两种结果都返回 200 + ok 字段，管理台据此渲染（HTTP 语义上是「探测动作完成」，
     * 远端不可达是业务结果而不是接口错误）。
     */
    @PostMapping("/{id}/probe")
    public Map<String, Object> probe(@PathVariable Long id) {
        McpServerPO server = repository.findById(id);
        if (server == null) {
            return Map.of("error", "MCP 服务不存在: " + id);
        }
        try {
            Map<String, Object> result = clientService.probe(server);
            repository.markProbe(id, null);
            return result;
        } catch (Exception e) {
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            repository.markProbe(id, message);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("error", "探测失败：" + message);
            return result;
        }
    }

    // ------------------------------------------------------------------
    // 私有辅助
    // ------------------------------------------------------------------

    private static String validatePo(McpServerPO po) {
        if (po.getServerCode() == null || !po.getServerCode().matches("[a-z][a-z0-9_\\-]{1,63}")) {
            return "服务编码（serverCode）必填，小写字母开头，仅含小写字母/数字/下划线/中划线（2-64 位）";
        }
        if (po.getServerName() == null || po.getServerName().isBlank()) {
            return "服务名称（serverName）必填";
        }
        String transport = po.getTransport() == null ? "" : po.getTransport().toUpperCase();
        if (!transport.equals("SSE") && !transport.equals("HTTP")) {
            return "传输类型（transport）只能是 SSE 或 HTTP";
        }
        if (po.getBaseUrl() == null || !po.getBaseUrl().startsWith("http")) {
            return "地址（baseUrl）必填，须以 http/https 开头（SSE 填 http://host:port，端点默认 /sse）";
        }
        if (po.getHeadersJson() != null && !po.getHeadersJson().isBlank()) {
            try {
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(po.getHeadersJson(), Map.class);
            } catch (Exception e) {
                return "headers_json 不是合法 JSON 对象: " + e.getMessage();
            }
        }
        return null;
    }

    private static void normalize(McpServerPO po) {
        po.setTransport(po.getTransport().toUpperCase());
        po.setAuthType(po.getAuthType() == null || po.getAuthType().isBlank()
                ? "NONE" : po.getAuthType().toUpperCase());
        po.setStatus(po.getStatus() == null || po.getStatus().isBlank() ? "active" : po.getStatus());
        if (po.getHeadersJson() != null && po.getHeadersJson().isBlank()) {
            po.setHeadersJson(null);
        }
    }
}