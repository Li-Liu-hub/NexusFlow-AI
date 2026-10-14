package com.aismp.digitalhuman.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.aismp.digitalhuman.model.AgentPO;
import com.aismp.digitalhuman.model.SkillPO;
import com.aismp.digitalhuman.model.ToolDefPO;
import com.aismp.digitalhuman.repository.AgentRepository;
import com.aismp.digitalhuman.tool.DigitalToolBuilder;
import com.aismp.digitalhuman.tool.ToolCallbackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数字员工装配器：把「一条配置记录」装配成可运行的 ReactAgent。
 *
 * <p>装配三步：
 * <ol>
 *   <li>系统提示词 = a_agent.system_prompt + 各技能包的 prompt_fragment / 示例（按 sort_order 拼接）</li>
 *   <li>工具集 = a_agent_tool_rel ⋈ a_llm_tool → 交给 {@link ToolCallbackFactory} 按类型构造</li>
 *   <li>组装 ReactAgent（模型 + 模型参数 + 提示词 + 工具）</li>
 * </ol>
 *
 * <p><b>架构要点</b>：本类<b>不认识任何具体的工具实现</b> —— 只有工厂按 tool_type 分发。
 * 新增工具类型（HTTP / MCP / 知识库检索）不需要修改本类任何一行。
 * 这是「装配器只认配置」的落地。
 */
@Service
public class AgentAssembler {

    private static final Logger log = LoggerFactory.getLogger(AgentAssembler.class);

    private final AgentRepository repository;
    private final ChatModel chatModel;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ToolCallbackFactory toolCallbackFactory;
    /** 工具调用审计出口：把 tool 包与 repository 包解耦，装配时才接上 */
    private final com.aismp.digitalhuman.tool.ToolCallAuditor auditor;
    /** 审批单出口：approval_required=1 的工具被调用时，拦截器靠它落单 */
    private final com.aismp.digitalhuman.tool.ToolApprovalService approvalService;

    @Value("${gateway.base-url:http://127.0.0.1:8090}")
    private String gatewayBaseUrl;
    @Value("${gateway.access-key:}")
    private String accessKey;
    @Value("${gateway.secret-key:}")
    private String secretKey;

    public AgentAssembler(AgentRepository repository,
                          ChatModel chatModel,
                          NamedParameterJdbcTemplate jdbc,
                          ObjectMapper json,
                          ToolCallbackFactory toolCallbackFactory,
                          com.aismp.digitalhuman.tool.ToolApprovalService approvalService) {
        this.repository = repository;
        this.chatModel = chatModel;
        this.jdbc = jdbc;
        this.json = json;
        this.toolCallbackFactory = toolCallbackFactory;
        this.approvalService = approvalService;
        this.auditor = entry -> repository.logToolCall(
                entry.traceId(), entry.conversationId(), entry.userId(), entry.entCode(),
                entry.toolName(), entry.argumentsJson(), entry.resultCount(),
                entry.durationMs(), entry.status(), entry.errorMessage());
    }

    /**
     * 按工具名单独构造一个可调用的 ToolCallback（审批放行后的重放执行用）。
     *
     * <p>放行链路不经过 ReactAgent：控制器拿到审批单里存住的入参原样调用，
     * 结果直接回给前端和会话，不需要模型再参与一轮。
     *
     * @throws IllegalArgumentException 数字员工不存在 / 工具未挂载到它 / 已停用
     */
    public ToolCallback buildToolCallback(String agentCode, String toolName) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            throw new IllegalArgumentException("数字员工不存在: " + agentCode);
        }
        return repository.listToolsOfAgent(agent.getId()).stream()
                .filter(t -> toolName.equals(t.getToolName()))
                .findFirst()
                .map(def -> toolCallbackFactory.create(def, buildContext(agent)))
                .orElseThrow(() -> new IllegalArgumentException(
                        "工具 [" + toolName + "] 未挂载到数字员工 " + agentCode + "（或已停用）"));
    }

    /** 构造上下文（装配与单工具重放共用一个出口，避免两处配置漂移）。 */
    private DigitalToolBuilder.ToolBuildContext buildContext(AgentPO agent) {
        return new DigitalToolBuilder.ToolBuildContext(
                jdbc, json, agent.getTenantCode(), auditor,
                gatewayBaseUrl, accessKey, secretKey, approvalService);
    }

    /**
     * 按 agent_code 装配数字员工。
     *
     * @return 装配结果；配置不存在或已停用时返回 null
     */
    public AssembledAgent assemble(String agentCode) {
        AgentPO agent = repository.findByCode(agentCode);
        if (agent == null) {
            log.warn("数字员工配置不存在: {}", agentCode);
            return null;
        }
        if (!"active".equals(agent.getStatus())) {
            log.warn("数字员工已停用: {}", agentCode);
            return null;
        }

        List<SkillPO> skills = repository.listSkillsOfAgent(agent.getId());
        String systemPrompt = buildSystemPrompt(agent, skills);
        // 知识库挂载的运行时效果：挂了 KB 的数字员工，提示词里明确「先查手册再回答」，
        // 并带上库的规模（文档数/分片数），让模型知道检索是有依据的、不是摆设。
        if (agent.getKnowledgeBaseId() != null) {
            try {
                Map<String, Object> kb = repository.findKbById(agent.getKnowledgeBaseId());
                if (kb != null) {
                    String kbUuid = String.valueOf(kb.get("knowledge_base_id"));
                    List<Map<String, Object>> docs = repository.listKbDocs(kbUuid);
                    long chunks = docs.stream()
                            .filter(d -> d.get("chunk_count") != null)
                            .mapToLong(d -> ((Number) d.get("chunk_count")).longValue())
                            .sum();
                    systemPrompt += "\n\n【已挂载知识库】《" + kb.get("name") + "》（"
                            + docs.size() + " 篇文档 / " + chunks + " 个分片）。"
                            + "凡涉及业务流程、操作规范、管理办法类的问题，先调用知识检索工具核对手册原文再回答，"
                            + "手册没有的内容如实说明，不要编造。";
                }
            } catch (Exception e) {
                log.warn("知识库挂载信息注入失败（跳过）: {}", e.getMessage());
            }
        }

        DigitalToolBuilder.ToolBuildContext ctx = buildContext(agent);

        List<ToolDefPO> toolDefs = repository.listToolsOfAgent(agent.getId());
        List<ToolCallback> toolCallbacks = new ArrayList<>();
        List<String> toolNames = new ArrayList<>();
        for (ToolDefPO def : toolDefs) {
            // 单个工具定义有问题（类型不支持 / 定义不合法）时跳过它，而不是让整个数字员工装配失败 ——
            // 一个工具配错不该导致数字员工完全不可用
            try {
                toolCallbacks.add(toolCallbackFactory.create(def, ctx));
                toolNames.add(def.getToolName());
            } catch (Exception e) {
                log.error("工具 [{}] 构造失败，已跳过：{}", def.getToolName(), e.getMessage());
            }
        }

        // 让「配置驱动」真正生效：模型与温度必须从 a_agent 读取并传给模型
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(agent.getModelName())
                .temperature(agent.getTemperature() == null
                        ? 0.7 : agent.getTemperature().doubleValue())
                .build();

        ReactAgent reactAgent = ReactAgent.builder()
                .name(agent.getAgentCode())
                .description(agent.getDescription())
                .instruction(systemPrompt)
                .model(chatModel)
                .chatOptions(chatOptions)
                .tools(toolCallbacks)
                .build();

        List<String> skillNames = skills.stream().map(SkillPO::getSkillName).toList();

        log.info("装配数字员工 {} v{}：模型={} 温度={} | {} 个技能、{} 个工具",
                agentCode, agent.getVersion(), agent.getModelName(),
                agent.getTemperature(), skillNames.size(), toolNames.size());

        return new AssembledAgent(
                agent.getAgentCode(),
                agent.getVersion(),
                reactAgent,
                systemPrompt,
                agent.getModelName(),
                skillNames,
                toolNames,
                System.currentTimeMillis());
    }

    /**
     * 提示词 = 人设基底 + 技能片段。
     *
     * <p>技能按 sort_order 顺序拼接，顺序会影响模型的注意力分布，所以是可配置的。
     */
    private String buildSystemPrompt(AgentPO agent, List<SkillPO> skills) {
        StringBuilder sb = new StringBuilder(agent.getSystemPrompt() == null ? "" : agent.getSystemPrompt());
        if (skills.isEmpty()) {
            return sb.toString();
        }
        for (SkillPO skill : skills) {
            if (skill.getPromptFragment() != null && !skill.getPromptFragment().isBlank()) {
                sb.append("\n\n").append(skill.getPromptFragment());
            }
            if (skill.getExamples() != null && !skill.getExamples().isBlank()) {
                sb.append("\n参考示例：").append(skill.getExamples());
            }
        }
        return sb.toString();
    }
}
