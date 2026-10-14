package com.aismp.aismpagent.rag;

// 日志注解，提供 log 对象
import lombok.extern.slf4j.Slf4j;
// Spring AI ChatClient，用于调用大模型
import org.springframework.ai.chat.client.ChatClient;
// Spring AI ChatModel，大模型抽象接口
import org.springframework.ai.chat.model.ChatModel;
// Spring AI OpenAI 聊天选项，用于指定模型名
import org.springframework.ai.openai.OpenAiChatOptions;
// Spring AI QueryTransformer 接口，查询转换器规范
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
// Spring AI Query 记录类，封装查询文本、历史、上下文
import org.springframework.ai.rag.Query;
// Spring AI VectorStoreDocumentRetriever，用于获取 FILTER_EXPRESSION 常量
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
// Spring 组件注解，标记为 Spring Bean
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Java 工具类
import java.util.*;

/**
 * OA 智能客服自定义查询转换器
 * 一次 LLM 调用同时完成：查询重写 + 分类
 * 将分类结果转为 filter 表达式塞到 Query 的 context 里，供后续检索器使用
 * 支持多轮对话：把对话历史拼入 prompt，让 LLM 能理解指代关系（如"第二种方式"）
 */
// 注册为 Spring Bean
@Component
// 启用日志
@Slf4j
public class OaRagQueryTransformer implements QueryTransformer {

    // 合法的分类列表，与 faq/ 目录下文档 frontmatter 的 category 字段**严格对齐**。
    //
    // 2026-10-01 从 OA 客服域（account/workflow/operation/electricity/error）
    // 切换到运营商终端管理域。这份常量必须与 local-init/gen-handbook-md.py 的
    // CATEGORY_OF 保持一致 —— 漏改的后果是检索 filter 匹配不到任何文档，
    // 表现为"知识库明明灌了却什么都答不出来"。
    private static final List<String> CATEGORIES = List.of(
            // 引言与背景（原四系统各管一段的历史问题）
            "intro",
            // 系统流程（串码级全生命周期的整体流程）
            "process",
            // 角色职责（终端主管 / 账管员 / 库管员 / 终端运营）
            "role",
            // 业务操作（库存管理 / 领用 / 装机出库 / 回收 / 利旧 / 盘点 / 报表）
            "operation",
            // 常见问题（登录问题 / 功能问题）
            "trouble"
    );

    /**
     * 查询重写/分类用的小模型名。走配置注入而不是写死在代码里 ——
     * 换模型（如切回 GLM 或换更便宜的 flash 型号）只改 application.yml，不用重新编译。
     */
    @Value("${spring.ai.openai.chat.options.model:mimo-v2.6-flash}")
    private String rewriteModel;

    // 专用 ChatClient，用于一次调用同时输出改写后的问题和分类
    private final ChatClient chatClient;

    // 构造函数注入 ChatModel，构建专用 ChatClient
    public OaRagQueryTransformer(ChatModel chatModel) {
        // System Prompt 指导 LLM 同时做重写和分类
        this.chatClient = ChatClient.builder(chatModel)
                // 指定小模型做查询重写 + 分类（本机部署统一用 MiMo flash；原为智谱 glm-4-flash）
                .defaultOptions(OpenAiChatOptions.builder().model(rewriteModel).build())
                .defaultSystem("你是「运营商固网终端管理」系统的查询分析助手。对用户问题做两件事：\n" +
                        // 第一步：查询重写，把口语化问题改写为规范表达
                        "1. 查询重写：把口语化问题改写为规范、完整的表达，保留原始意图，使用终端管理术语（光猫、机顶盒、FTTR、串码、仓位、调拨、领用、回收、利旧、盘点、库龄等）\n" +
                        // 如果有多轮对话历史，先判断当前问题是否需要依赖历史才能理解
                        "如果提供了对话历史，先判断当前问题是否需要依赖历史才能理解：\n" +
                        // 包含指代词的问题需要结合历史
                        "- 如果当前问题包含指代词（如'第三种''刚才''那个''继续'等），无法独立理解，请结合上下文重写\n" +
                        // 完整的问题直接重写，忽略历史
                        "- 如果当前问题是完整的、可以独立理解的（即使话题和历史相关），直接重写，忽略历史\n" +
                        // 第二步：分类判断，判断问题属于哪些分类（可多选）
                        "2. 分类判断：判断问题属于哪些分类（可多选）：intro(引言与背景), process(系统流程), role(角色职责), operation(业务操作：库存管理/领用管理/装机出库/回收管理/利旧/终端查询/盘点/报表), trouble(常见问题与故障排查)\n" +
                        // 如果与五个分类都无关，返回 none
                        "如果与五个分类都无关，返回 none。\n\n" +
                        // 输出格式要求（严格遵守）
                        "输出格式（严格遵守，不要加其他内容）：\n" +
                        // 第一行输出重写后的问题
                        "QUERY: 改写后的问题\n" +
                        // 第二行输出分类
                        "CATEGORY: 分类1,分类2\n\n" +
                        // 示例 1：单分类
                        "示例：\n" +
                        "输入：光猫怎么调拨给别人\n" +
                        "QUERY: 光猫如何通过库存调拨流程转移到其他仓位\n" +
                        "CATEGORY: operation\n\n" +
                        // 示例 2：多分类
                        "输入：调拨要谁审批\n" +
                        "QUERY: 终端调拨需要哪一级主管审批\n" +
                        "CATEGORY: operation,role\n\n" +
                        "输入：登录提示SSO获取用户信息失败怎么办\n" +
                        "QUERY: 登录时提示 SSO 单点调用获取用户信息失败如何排查\n" +
                        "CATEGORY: trouble\n\n" +
                        // 示例 3：无关分类
                        "输入：今天天气怎么样\n" +
                        "QUERY: 今天天气怎么样\n" +
                        "CATEGORY: none")
                .build();
    }

    /**
     * 核心方法：接收原始 Query，返回增强后的 Query
     * 1. 调 LLM 做重写 + 分类（一次调用）
     * 2. 解析返回结果
     * 3. 把分类转为 filter 表达式塞到 context 里
     */
    @Override
    public Query transform(Query query) {
        // 获取用户原始问题
        String userQuestion = query.text();
        // 【诊断日志】2026-10-01 服务器上从未出现本类的任何日志，但拒答行为表明
        // RAG 管道"执行过"—— 用第一行日志证明 transform 是否真的被调用
        log.info(">>> [诊断] OaRagQueryTransformer.transform 被调用, question='{}'", userQuestion);

        // 构建发给 LLM 的完整 prompt：对话历史 + 当前问题
        // 这样 LLM 能理解指代关系（如"第二种方式"指的是上一轮密码重置的第二种）
        String promptText = buildPromptWithHistory(query);

        // 调 LLM：一次调用完成重写 + 分类
        String llmResult = chatClient.prompt()
                // 传入带历史上下文的完整 prompt
                .user(promptText)
                // 同步调用
                .call()
                // 获取文本内容
                .content();

        // 默认用原始问题（如果 LLM 解析失败则兜底）
        String rewrittenQuery = userQuestion;
        // 分类列表，默认为空
        List<String> categories = new ArrayList<>();

        // 检查 LLM 返回结果是否有效
        if (llmResult != null && !llmResult.trim().isEmpty()) {
            // 按行解析 QUERY 和 CATEGORY
            for (String line : llmResult.trim().split("\n")) {
                // 去除首尾空白
                String trimmed = line.trim();
                // 判断是否是 QUERY 行（大小写不敏感）
                if (trimmed.toUpperCase().startsWith("QUERY:")) {
                    // 截取 "QUERY:" 之后的内容作为重写后的问题
                    rewrittenQuery = trimmed.substring(6).trim();
                    // 判断是否是 CATEGORY 行（大小写不敏感）
                } else if (trimmed.toUpperCase().startsWith("CATEGORY:")) {
                    // 截取 "CATEGORY:" 之后的内容作为分类字符串
                    String catStr = trimmed.substring(9).trim();
                    // 如果分类不是 none，则解析每个分类
                    if (!catStr.equalsIgnoreCase("none")) {
                        // 按英文逗号或中文逗号分割
                        for (String cat : catStr.split("[,，]")) {
                            // 去除空白并转小写
                            String c = cat.trim().toLowerCase();
                            // 只保留合法分类
                            if (CATEGORIES.contains(c)) {
                                // 添加到分类列表
                                categories.add(c);
                            }
                        }
                    }
                }
            }
        }

        // 打印查询转换日志
        log.info("查询转换：'{}' → 重写='{}', 分类={}", userQuestion, rewrittenQuery, categories);

        // 构建新的 context，保留原有 context 并注入 filter
        Map<String, Object> newContext = new HashMap<>(query.context());

        // 如果有分类结果，构建 filter 表达式注入 context
        if (!categories.isEmpty()) {
            // 根据分类列表构建 filter 表达式字符串
            String filterExpr = buildFilterExpression(categories);
            // VectorStoreDocumentRetriever 会从 context 里取这个 key 做过滤
            newContext.put(VectorStoreDocumentRetriever.FILTER_EXPRESSION, filterExpr);
            // 打印 filter 注入日志
            log.info("注入 filter 表达式：{}", filterExpr);
        }

        // 返回增强后的 Query（重写后的文本 + 带 filter 的 context）
        return Query.builder()
                // 重写后的查询文本
                .text(rewrittenQuery)
                // 保留原有对话历史
                .history(query.history())
                // 新的 context（可能包含 filter 表达式）
                .context(newContext)
                .build();
    }

    /**
     * 把对话历史 + 当前问题拼接成完整 prompt
     * 让 LLM 能理解指代关系（如"第二种方式"指的是上一轮回答中的第二种）
     */
    private String buildPromptWithHistory(Query query) {
        // 获取对话历史消息列表
        List<org.springframework.ai.chat.messages.Message> history = query.history();
        // 如果没有历史，直接返回当前问题
        if (history == null || history.isEmpty()) {
            return query.text();
        }

        // 构建 prompt：对话历史 + 当前问题
        StringBuilder sb = new StringBuilder();
        // 添加对话历史标题
        sb.append("对话历史：\n");

        // 遍历历史消息，最多取最近 6 条（3 轮对话，防止 prompt 过长）
        int start = Math.max(0, history.size() - 6);
        for (int i = start; i < history.size(); i++) {
            // 获取单条历史消息
            org.springframework.ai.chat.messages.Message msg = history.get(i);
            // 获取消息类型（USER / ASSISTANT / SYSTEM）
            String role = msg.getMessageType().name();
            // 获取消息文本内容
            // 注意：AssistantMessage 可能很长，截取前 200 字符防止 prompt 爆炸
            String text = msg.getText();
            if (text != null && text.length() > 200) {
                // 截取前 200 字符
                text = text.substring(0, 200) + "...";
            }
            // 拼接角色和内容
            sb.append(role).append(": ").append(text).append("\n");
        }

        // 添加分隔线
        sb.append("\n");
        // 添加当前问题标题
        sb.append("当前问题：\n");
        // 添加用户当前的问题
        sb.append(query.text());

        // 返回完整 prompt 字符串
        return sb.toString();
    }

    /**
     * 根据分类列表构建 filter 表达式字符串
     * 单分类：category == 'account'
     * 多分类：category in ['account', 'workflow']
     */
    private String buildFilterExpression(List<String> categories) {
        // 单分类用相等表达式
        if (categories.size() == 1) {
            // 格式：category == 'xxx'
            return String.format("category == '%s'", categories.get(0));
        } else {
            // 多分类用 IN 列表表达式
            StringBuilder sb = new StringBuilder("category in [");
            // 遍历分类列表
            for (int i = 0; i < categories.size(); i++) {
                // 非第一个元素前加逗号分隔
                if (i > 0) sb.append(", ");
                // 添加带引号的分类名
                sb.append("'").append(categories.get(i)).append("'");
            }
            // 闭合方括号
            sb.append("]");
            // 返回 filter 表达式
            return sb.toString();
        }
    }
}
