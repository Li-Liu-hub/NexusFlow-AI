package com.aismp.orchestrator.api;

import com.aismp.orchestrator.graph.StateKeys;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 编排入口（迁移对照 {@code server.py:200 chat_stream}）。
 *
 * <p>SSE 帧契约**逐字保留**原版五种事件，前端不需要改：
 * <pre>
 *   event: start      {"conversation_id": "orch-xxxx"}
 *   event: progress   {"node": "supervise", "produced": ["route", "meta"]}
 *   event: agent_step {"seq": 1, "text": "…"}
 *   event: error      "编排异常：…"            （data 是字符串，不是对象）
 *   event: result     {"agent_response","cards","strategy","route","suggestion_id","conversation_id"}
 * </pre>
 *
 * <p>身份信任模型与原版一致：<b>网关注入的 X-User-* 头优先于 query 参数</b>。
 * 浏览器可以随便改 query，改不了网关写过的头 —— 这是数据可见范围不被放大的前提。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final CompiledGraph graph;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(CompiledGraph graph) {
        this.graph = graph;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "aismp-orchestrator",
                "impl", "spring-ai-alibaba-graph");
    }

    @PostMapping(path = "/orchestrator/v1/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestParam("query") String query,
            @RequestParam(name = "user_id", defaultValue = "anonymous") String userId,
            @RequestParam(name = "suggestion_id", defaultValue = "") String suggestionId,
            @RequestParam(name = "warehouse_code", defaultValue = "") String warehouseCode,
            @RequestParam(name = "conversation_id", defaultValue = "") String conversationId,
            @RequestHeader(name = "X-User-Id", required = false) String headerUser,
            @RequestHeader(name = "X-User-Warehouse", required = false) String headerWarehouse) {

        // 门户通道：网关注入的头优先（非空即代表是网关写过的权威身份）
        String effectiveUser = isBlank(headerUser) ? userId : headerUser.trim();
        String effectiveWarehouse = isBlank(headerUser) ? warehouseCode
                : (headerWarehouse == null ? "" : headerWarehouse.trim());

        SseEmitter emitter = new SseEmitter(0L);
        executor.submit(() -> run(emitter, query, effectiveUser, suggestionId,
                effectiveWarehouse, conversationId));
        return emitter;
    }

    private void run(SseEmitter emitter, String query, String userId, String suggestionId,
                     String warehouseCode, String conversationId) {
        String convId = isBlank(conversationId)
                ? "orch-" + UUID.randomUUID().toString().substring(0, 8)
                : conversationId;
        try {
            send(emitter, "start", Map.of("conversation_id", convId));

            Map<String, Object> meta = new HashMap<>();
            meta.put("conversation_id", convId);
            meta.put("user_id", userId);
            meta.put("warehouse_code", warehouseCode.isEmpty() ? null : warehouseCode);

            Map<String, Object> input = new HashMap<>();
            input.put(StateKeys.QUERY, query);
            input.put(StateKeys.REVISION_COUNT, 0);
            input.put(StateKeys.META, meta);
            if (!isBlank(suggestionId)) {
                input.put(StateKeys.SUGGESTION_ID, suggestionId);
                meta.put(StateKeys.SUGGESTION_ID, suggestionId);
            }

            RunnableConfig config = RunnableConfig.builder().threadId(convId).build();
            Map<String, Object> previous = new HashMap<>(input);
            Map<String, Object> finalState = new HashMap<>(input);

            for (NodeOutput output : graph.stream(input, config).toIterable()) {
                String node = output.node();
                // 图的哨兵节点名为 "__START__" / "__END__"，不是 "START" / "END"
                if (node == null || StateGraph.START.equals(node) || StateGraph.END.equals(node)) {
                    continue;
                }
                Map<String, Object> data = new HashMap<>(output.state().data());
                List<String> produced = diffKeys(previous, data);
                previous.putAll(data);
                finalState.putAll(data);

                send(emitter, "progress", Map.of("node", node, "produced", produced));
                // 原版节点内部用 stream_writer 推 agent_step；Java 版按「产出即推送」等价实现
                if (produced.contains(StateKeys.AGENT_RESPONSE)) {
                    send(emitter, "agent_step", Map.of("seq", 1,
                            "text", String.valueOf(data.getOrDefault(StateKeys.AGENT_RESPONSE, ""))));
                }
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(StateKeys.AGENT_RESPONSE,
                    finalState.getOrDefault(StateKeys.AGENT_RESPONSE, ""));
            payload.put(StateKeys.CARDS, finalState.getOrDefault(StateKeys.CARDS, List.of()));
            payload.put(StateKeys.STRATEGY, finalState.getOrDefault(StateKeys.STRATEGY, ""));
            payload.put(StateKeys.ROUTE, finalState.getOrDefault(StateKeys.ROUTE, ""));
            payload.put(StateKeys.SUGGESTION_ID, finalState.getOrDefault(StateKeys.SUGGESTION_ID, ""));
            payload.put("conversation_id", convId);
            send(emitter, "result", payload);
            emitter.complete();
        } catch (Exception e) {
            log.error("编排失败（conversation={}）", convId, e);
            try {
                send(emitter, "error", "编排异常：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception ignored) {
                // 客户端可能已断开：忽略
            }
            emitter.complete();
        }
    }

    /** 对比前后状态，得到这个节点真正写了的 key（等价于原版 updates 的 keys）。 */
    private static List<String> diffKeys(Map<String, Object> before, Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                keys.add(entry.getKey());
            }
        }
        return new ArrayList<>(keys);
    }

    private static void send(SseEmitter emitter, String event, Object data) throws Exception {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
