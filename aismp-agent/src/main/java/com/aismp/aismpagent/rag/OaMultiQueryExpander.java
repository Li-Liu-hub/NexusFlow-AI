package com.aismp.aismpagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 多查询扩展器
 * 用 LLM 把一个问题扩展为多个不同角度的子查询，扩大检索覆盖面
 */
@Component
@Slf4j
public class OaMultiQueryExpander {

    // 多查询扩展专用的 ChatClient
    private final ChatClient chatClient;

    public OaMultiQueryExpander(ChatModel chatModel) {
        // 构建 ChatClient，System Prompt 指导 LLM 生成多个子查询
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一个查询扩展助手。将用户的问题扩展为 3 个语义不同但相关的子查询。\n" +
                        "要求：\n" +
                        "1. 每个子查询从不同角度表达原始意图\n" +
                        "2. 使用 OA 系统常见术语（工单、审批、权限、报表、附件、电费等）\n" +
                        "3. 每行一个子查询，不要编号，不要解释\n" +
                        "示例：\n" +
                        "问题：电费异常怎么提工单\n" +
                        "电费数据异常如何排查处理\n" +
                        "在 OA 系统中提交工单的流程\n" +
                        "电费异常问题如何通过工单反馈")
                .build();
    }

    /**
     * 将原始问题扩展为多个子查询
     * 调一次 LLM，返回结果按换行分割，始终包含原始问题
     */
    public List<String> expand(String question) {
        // 调 LLM 生成子查询
        String result = chatClient.prompt()
                .user(question)
                .call()
                .content();

        // 如果 LLM 返回为空，退化为只用原始问题
        if (result == null || result.trim().isEmpty()) {
            return List.of(question);
        }

        // 按换行分割 LLM 返回的内容，每行是一个子查询
        List<String> queries = new ArrayList<>();
        for (String line : result.trim().split("\n")) {
            // 去掉每行前后空白和可能的编号前缀（如 "1." "2."）
            String trimmed = line.trim().replaceAll("^\\d+[.、）)]\\s*", "");
            if (!trimmed.isEmpty()) {
                queries.add(trimmed);
            }
        }

        // 始终把原始问题加入列表（保底）
        if (!queries.contains(question)) {
            queries.add(question);
        }

        log.info("多查询扩展：'{}' → {}", question, queries);
        return queries;
    }
}
