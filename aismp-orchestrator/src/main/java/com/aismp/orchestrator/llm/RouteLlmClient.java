package com.aismp.orchestrator.llm;

/**
 * 「问一句、拿一个词」的最小 LLM 通道。
 *
 * <p>为什么要这层接口：路由的正确性有两半 ——
 * <b>HTTP 怎么发</b>（易变、依赖网络）和 <b>模型输出怎么解析 + 怎么降级</b>（业务规则、必须可测）。
 * 把前者抽成接口，后者就能用桩子覆盖全部分支，测试不需要网络也不需要 API Key。
 */
@FunctionalInterface
public interface RouteLlmClient {

    /**
     * 发一次分类请求，返回模型原文（未做任何清洗）。
     *
     * @param query 用户问题
     * @throws RuntimeException 网络异常 / 响应结构不符合预期
     */
    String complete(String query);
}
