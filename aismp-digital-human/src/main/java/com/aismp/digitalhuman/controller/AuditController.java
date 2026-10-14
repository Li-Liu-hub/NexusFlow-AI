package com.aismp.digitalhuman.controller;

import com.aismp.digitalhuman.repository.AgentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工具调用审计接口。
 *
 * <p>回答运维最常问的三个问题：
 * <ul>
 *   <li>数字员工刚才到底查了什么？（recent —— 带完整入参）</li>
 *   <li>哪个工具用得最多、有没有报错、平均多慢？（stats）</li>
 *   <li>出问题时，根据返回体里的 traceId 能不能定位到具体调用？</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/audit")
public class AuditController {

    private final AgentRepository repository;

    public AuditController(AgentRepository repository) {
        this.repository = repository;
    }

    /** 最近的工具调用明细（倒序）。 */
    @GetMapping("/recent")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "20") int limit) {
        return repository.recentToolCalls(Math.min(Math.max(limit, 1), 200));
    }

    /** 按工具聚合的调用统计：次数、错误数、平均耗时、累计返回行数。 */
    @GetMapping("/stats")
    public List<Map<String, Object>> stats() {
        return repository.toolCallStats();
    }
}
