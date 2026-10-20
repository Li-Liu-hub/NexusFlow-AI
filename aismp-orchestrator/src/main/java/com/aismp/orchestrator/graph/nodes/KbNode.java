package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.client.DigitalHumanClient;
import com.aismp.orchestrator.config.OrchestratorProperties;
import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.graph.StateSupport;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识问答节点：转发给数字员工内核，不在编排层重建检索。
 *
 * <p>迁移对照 {@code graph.py:499 digital_human_node()}。为什么不重写一遍 RAG：
 * Java 侧已有五轮迭代验证过的全管线（加载 → embedding → pgvector HNSW →
 * 查询重写 + 分类 filter → 增强问答），重写既浪费又更差；编排层只做协议适配 + 失败降级。
 *
 * <p>失败降级是硬要求：内核挂了也要让用户收到一句人话，而不是一个 error 帧。
 */
public class KbNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(KbNode.class);

    private final DigitalHumanClient client;
    private final OrchestratorProperties.DigitalHuman cfg;

    public KbNode(DigitalHumanClient client, OrchestratorProperties properties) {
        this.client = client;
        this.cfg = properties.getDigitalHuman();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        long start = System.currentTimeMillis();
        Map<String, Object> data = state.data();
        String query = StateSupport.text(data.get(StateKeys.QUERY));
        Map<String, Object> meta = StateSupport.asMap(data.get(StateKeys.META));

        String agentCode = StateSupport.text(meta.get("agent_code"));
        if (agentCode.isEmpty()) {
            agentCode = cfg.getAgentCode();
        }
        List<String> scope = visibleScope(StateSupport.text(meta.get("warehouse_code")));

        String answer;
        Integer version = null;
        try {
            DigitalHumanClient.ChatResult result = client.chat(
                    agentCode, query, scope, StateSupport.text(meta.get("conversation_id")));
            answer = result.answer();
            version = result.version();
        } catch (RuntimeException e) {
            log.warn("数字员工不可用（agent={}）", agentCode, e);
            answer = "数字员工暂时不可用（" + e.getMessage() + "）";
        }

        Map<String, Object> out = new HashMap<>();
        out.put(StateKeys.REWRITTEN_QUERY, query);
        out.put(StateKeys.AGENT_RESPONSE, answer == null ? "" : answer);
        Map<String, Object> newMeta = new HashMap<>();
        newMeta.put("digital_human_duration_ms", System.currentTimeMillis() - start);
        newMeta.put("channel", "digital_human");
        newMeta.put("agent_code", agentCode);
        newMeta.put("agent_version", version);
        newMeta.put("kb_scope", scope);
        out.put(StateKeys.META, newMeta);
        return out;
    }

    /**
     * 数据可见范围：本仓 + 全部下级仓；空仓位表示不限（原版 visible_scope 的简化）。
     *
     * <p>真实层级关系在 MySQL 的 warehouse 表里，阶段 2 接数据源后替换这里；
     * 现在返回 {@code null} 表示「不限制」，与原版对无仓库归属账号的行为一致。
     */
    private static List<String> visibleScope(String warehouseCode) {
        if (warehouseCode == null || warehouseCode.isBlank()) {
            return null;
        }
        // TODO(阶段 2)：查 warehouse 表展开下级仓。当前先按「本仓」单点收口，不放大小范围。
        return List.of(warehouseCode);
    }


}
