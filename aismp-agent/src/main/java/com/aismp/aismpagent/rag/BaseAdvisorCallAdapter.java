package com.aismp.aismpagent.rag;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;

import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

/**
 * 把 BaseAdvisor（只有 before/after）适配成 CallAdvisor。
 *
 * <p><b>背景（2026-10-01 破案）</b>：Spring AI 1.0.3 的
 * {@code ChatClient.defaultAdvisors()} 按 advisor 的<strong>实际接口</strong>分发：
 * CallAdvisor 进同步链、StreamAdvisor 进流式链。而
 * {@code RetrievalAugmentationAdvisor} 只实现了 {@code BaseAdvisor}（javap 验证：
 * {@code implements BaseAdvisor}，仅有 before/after），两个链都进不去 ——
 * <b>被静默忽略</b>：不报错、无任何日志、检索永远不发生，
 * LLM 按系统提示词兜底拒答（表现为"知识库无相关内容"）。
 *
 * <p>这个 bug 的迷惑性在于：管道组装日志正常（create() 被调用）、
 * 应用正常启动、LLM 依然回答（走的是无检索的兜底话术），唯一线索是
 * transformer 的业务日志从未出现。
 *
 * <p>本适配器在同步链里手动执行 before → 继续链 → after，
 * 让原 RAG 管道（查询重写+分类 → 向量检索 → 空上下文兜底）真正跑起来。
 * 若走流式（SSE），需再写一个 StreamAdvisor 版本。
 *
 * @author agent-platform
 */
public final class BaseAdvisorCallAdapter implements CallAdvisor {

    private final BaseAdvisor delegate;
    private final String name;
    private final int order;

    public BaseAdvisorCallAdapter(BaseAdvisor delegate, String name, int order) {
        this.delegate = delegate;
        this.name = name;
        this.order = order;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // before：执行 RAG 管道（查询重写+分类 → 向量检索 → 上下文增强）
        ChatClientRequest augmented = delegate.before(request, chain);
        // 继续链（模型调用等），拿到响应后走 after（本管道的 after 是直通）
        ChatClientResponse response = chain.nextCall(augmented);
        return delegate.after(response, chain);
    }
}
