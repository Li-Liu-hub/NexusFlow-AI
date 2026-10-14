package com.aismp.digitalhuman.service;

import com.aismp.digitalhuman.model.AgentPO;
import com.aismp.digitalhuman.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能路由器：一条消息进来，先按角色圈定候选数字员工，再挑最合适的一个。
 *
 * <p><b>两级路由</b>：
 * <ol>
 *   <li><b>角色圈定</b>（硬边界）：管理员把数字员工分配给角色（a_agent_role_rel），
 *       用户角色决定候选集 —— 库管员永远路由不到补仓推荐师之外越权的配置；</li>
 *   <li><b>意图分流</b>（软选择）：关键词快路命中直接选（0 延迟），
 *       未命中再让模型做一次单选分类（~1s），解析失败兜底取第一个候选。</li>
 * </ol>
 *
 * <p>候选只有一个时跳过分流直接返回 —— 常见角色（如只配了一个数字员工）零开销。
 */
@Service
public class RouterService {

    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    private final AgentRepository repository;
    private final ChatModel chatModel;
    private final ObjectMapper json = new ObjectMapper();

    /** 关键词快路表：命中即选，省一次模型调用。键与既有数字员工对应，未列出的靠 LLM 分流。 */
    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("query_assistant", List.of(
                "库存", "多少台", "串码", "查一下", "查询", "分布", "统计", "还剩", "在库", "入库", "调拨单", "0元"));
        KEYWORDS.put("replenish_advisor", List.of(
                "补货", "补仓", "水位", "缺货", "采购建议", "该进", "备货"));
        KEYWORDS.put("ops_assistant", List.of(
                "流程", "怎么办", "怎么操作", "故障", "返厂", "维修", "报废", "盘点", "领用", "手册", "规范", "登录不上"));
    }

    public RouterService(AgentRepository repository, ChatModel chatModel) {
        this.repository = repository;
        this.chatModel = chatModel;
    }

    /** 路由结果：选中的数字员工 + 决策依据（给前端透出，让「为什么是他」可解释）。 */
    public record RouteResult(String agentCode, String agentName, String reason) {
    }

    /** 某角色可用的数字员工列表（用户端员工卡片的数据源）。 */
    public List<AgentPO> agentsForRole(String role) {
        List<AgentPO> agents = (role == null || role.isBlank())
                ? repository.listActiveAgents()
                : repository.listAgentsForRole(role);
        return agents == null ? List.of() : agents;
    }

    /**
     * 路由一条消息。
     *
     * @throws IllegalStateException 角色下没有可用数字员工
     */
    public RouteResult route(String message, String role) {
        List<AgentPO> candidates = agentsForRole(role);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("角色 [" + (role == null ? "(any)" : role)
                    + "] 下没有可用数字员工，请管理员在配置面分配");
        }
        if (candidates.size() == 1) {
            AgentPO only = candidates.get(0);
            return new RouteResult(only.getAgentCode(), only.getAgentName(),
                    "该角色仅开放「" + only.getAgentName() + "」");
        }

        // 1) 关键词快路：计分制 —— 命中关键词最多者胜，同分按角色分配顺序。
        //    不能用「先命中先赢」：候选顺序里查数助手在前，而「库存水位该补货了」
        //    同时含两家的关键词，数意图显然是补仓 —— 先命中会把它错分给查数助手。
        String bestCode = null;
        String bestName = null;
        int bestScore = 0;
        for (AgentPO candidate : candidates) {
            List<String> words = KEYWORDS.get(candidate.getAgentCode());
            if (words == null) {
                continue;
            }
            int score = (int) words.stream().filter(message::contains).count();
            if (score > bestScore) {
                bestScore = score;
                bestCode = candidate.getAgentCode();
                bestName = candidate.getAgentName();
            }
        }
        if (bestScore > 0) {
            return new RouteResult(bestCode, bestName, "关键词计分=" + bestScore + " → " + bestName);
        }

        // 2) LLM 单选分类（只在关键词未命中时发生）
        try {
            String picked = classify(message, candidates);
            AgentPO hit = candidates.stream()
                    .filter(c -> c.getAgentCode().equals(picked))
                    .findFirst().orElse(null);
            if (hit != null) {
                return new RouteResult(hit.getAgentCode(), hit.getAgentName(), "意图分类 → " + hit.getAgentName());
            }
        } catch (Exception e) {
            log.warn("路由分类失败，兜底取首个候选：{}", e.getMessage());
        }

        // 3) 兜底：候选第一个（角色分配时 sort_order 靠前的优先）
        AgentPO first = candidates.get(0);
        return new RouteResult(first.getAgentCode(), first.getAgentName(),
                "未命中规则，默认 → " + first.getAgentName());
    }

    /** 让模型在候选里做单选，只回 JSON。 */
    private String classify(String message, List<AgentPO> candidates) {
        StringBuilder sb = new StringBuilder("你是终端管理系统的意图路由器。根据用户消息选择最合适的数字员工。\n候选：\n");
        for (AgentPO c : candidates) {
            sb.append("- ").append(c.getAgentCode())
                    .append("（").append(c.getAgentName()).append("）：")
                    .append(c.getDescription() == null ? "" : c.getDescription()).append('\n');
        }
        sb.append("\n用户消息：").append(message)
          .append("\n只输出 JSON：{\"agentCode\":\"候选中的编码\"}");

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("mimo-v2.6-flash")
                .temperature(0.1)
                .maxTokens(50)
                .build();
        String answer = chatModel.call(new Prompt(sb.toString(), options))
                .getResult().getOutput().getText();
        // 宽松解析：模型偶尔会包一层代码块或前后缀
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"agentCode\"\\s*:\\s*\"([a-zA-Z0-9_]+)\"").matcher(answer);
        return m.find() ? m.group(1) : "";
    }
}
