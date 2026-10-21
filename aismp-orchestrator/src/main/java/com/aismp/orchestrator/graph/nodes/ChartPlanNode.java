package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.graph.StateSupport;
import com.aismp.orchestrator.llm.RouteLlmClient;
import com.aismp.orchestrator.service.ChartBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图表路线第 1 步：选型（维度 × 样式）。
 *
 * <p><b>规则优先，LLM 兜底</b>。原 Python 版曾经是 LLM 优先 + 静默重试 3 次，
 * 实测一次提供方抖动把整条图表链路拖到 301.8 秒，而重试异常被吞掉、日志零痕迹。
 * 选型本质是「5 维度 × 4 样式」的分类，规则能命中绝大多数问法（&lt;1ms），
 * 所以现在的顺序是：规则命中就返回；没命中才问模型（2 次），仍不行降级统计卡
 * （统计卡只调一次快照接口，最稳）。
 */
public class ChartPlanNode implements NodeAction {

    private static final String PROMPT = """
            你是图表选型器，只做分类，不要解释。
            从下面两个维度里各选一个，用一行输出，格式：维度 样式
            维度：warehouse(各仓分布) aging(库龄分布) model(型号分布) stat(整体统计) time(入库趋势)
            样式：bar(柱) pie(饼) line(折线) table(表格)
            用户问题：%s
            """;

    private final RouteLlmClient llm;

    public ChartPlanNode(RouteLlmClient llm) {
        this.llm = llm;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        long start = System.currentTimeMillis();
        String query = StateSupport.text(state.data().get(StateKeys.QUERY));

        String kind = ChartBuilder.kindByRule(query);
        String style;
        String reason;
        if (kind != null) {
            style = "stat".equals(kind) ? "metric" : defaultIfNull(ChartBuilder.styleByKeyword(query), "bar");
            reason = "规则命中";
        } else {
            kind = "stat";
            style = "metric";
            reason = "选型连续失败，降级整体统计";
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    String raw = llm.complete(PROMPT.formatted(query)).toLowerCase();
                    String matchedKind = ChartBuilder.KINDS.stream().filter(raw::contains).findFirst().orElse(null);
                    if (matchedKind != null) {
                        kind = matchedKind;
                        String matchedStyle = ChartBuilder.STYLES.stream().filter(raw::contains).findFirst().orElse(null);
                        style = "stat".equals(kind) ? "metric"
                                : defaultIfNull(matchedStyle, defaultIfNull(ChartBuilder.styleByKeyword(query), "bar"));
                        reason = raw.length() > 60 ? raw.substring(0, 60) : raw;
                        break;
                    }
                } catch (RuntimeException e) {
                    // 选型失败不阻塞主流程：走降级，但保留原因便于排障
                    reason = "第" + attempt + "次选型异常：" + e.getClass().getSimpleName();
                }
            }
        }

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("kind", kind);
        plan.put("style", style);
        plan.put("reason", reason);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(StateKeys.CHART_PLAN, plan);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chart_kind", kind);
        meta.put("chart_style", style);
        meta.put("chart_plan_duration_ms", System.currentTimeMillis() - start);
        meta.put("channel", "chart");
        out.put(StateKeys.META, meta);
        return out;
    }

    private static String defaultIfNull(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
