package com.aismp.digitalhuman.controller;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 骨架自检接口：验证 Spring AI 2.0 ChatModel 与 terminal_db 数据源是否就绪。
 *
 * <p>这个接口的用途是「可观测的启动证据」——不用翻日志就能确认依赖装配成功。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final JdbcTemplate jdbcTemplate;

    public HealthController(ObjectProvider<ChatModel> chatModelProvider, JdbcTemplate jdbcTemplate) {
        this.chatModelProvider = chatModelProvider;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("module", "aismp-digital-human");

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        result.put("chatModel", chatModel == null ? "MISSING" : chatModel.getClass().getName());

        try {
            Integer toolCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM a_llm_tool WHERE status = 'active'", Integer.class);
            Integer terminalCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_terminal", Integer.class);
            result.put("activeTools", toolCount);
            result.put("terminals", terminalCount);
            result.put("database", "CONNECTED");
        } catch (Exception e) {
            result.put("database", "ERROR: " + e.getMessage());
        }
        return result;
    }
}
