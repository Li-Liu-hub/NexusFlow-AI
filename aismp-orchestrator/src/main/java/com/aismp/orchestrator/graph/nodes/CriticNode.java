package com.aismp.orchestrator.graph.nodes;

import com.aismp.orchestrator.graph.StateKeys;
import com.aismp.orchestrator.graph.StateSupport;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质检闸门（数字红线的执行者）。
 *
 * <p>与原 Python 版的差别：原版用 LLM 给建议打分再决定是否回炉，本版用**确定性规则**：
 * <ul>
 *   <li>verify_numbers 报违规 → 回炉一次重算（回炉上限 1 次，防止来回振荡）</li>
 *   <li>取数失败 → 直接放行（报告节点已写明"数据未取到"，此时回炉没有意义）</li>
 *   <li>其余情况 → 放行</li>
 * </ul>
 *
 * <p>为什么不用 LLM 打分：数字对不对是**可判定**的（diff 已经给出答案），
 * 再让模型判断一次只是把一个确定结论变成不确定结论；而"建议好不好"这种主观维度
 * 在演示里也不该阻断主流程。
 */
public class CriticNode implements NodeAction {

    static final int MAX_REVISIONS = 1;

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> meta = StateSupport.asMap(state.data().get(StateKeys.META));
        boolean passed = !Boolean.FALSE.equals(meta.get("verify_passed"));
        boolean fetchOk = !Boolean.FALSE.equals(meta.get("fetch_ok"));
        int revisions = state.data().get(StateKeys.REVISION_COUNT) instanceof Number n ? n.intValue() : 0;

        String decision;
        String reason;
        int nextRevisions = revisions;
        List<String> violations = meta.get("verify_violations") instanceof List<?> l
                ? (List<String>) l : List.of();

        if (!fetchOk) {
            decision = "approve";
            reason = "取数失败，报告已说明原因，回炉无意义";
        } else if (!passed && revisions < MAX_REVISIONS) {
            decision = "rework";
            reason = "数字自检未通过（%d 项），回炉重算".formatted(violations.size());
            nextRevisions = revisions + 1;
        } else if (!passed) {
            decision = "approve";
            reason = "已回炉 %d 次仍未通过，放行并留痕（%s）"
                    .formatted(revisions, String.join("；", violations));
        } else {
            decision = "approve";
            reason = "数字自检通过";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(StateKeys.REVISION_COUNT, nextRevisions);
        Map<String, Object> metaOut = new LinkedHashMap<>();
        metaOut.put("critic_decision", decision);
        metaOut.put("critic_reason", reason);
        metaOut.put("critic_revisions", nextRevisions);
        out.put(StateKeys.META, metaOut);
        return out;
    }
}
