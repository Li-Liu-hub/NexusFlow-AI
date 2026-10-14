package com.aismp.digitalhuman.controller;

import com.aismp.digitalhuman.auth.AuthInterceptor;
import com.aismp.digitalhuman.auth.UserPrincipal;
import com.aismp.digitalhuman.repository.ConversationRepository;
import com.aismp.digitalhuman.service.AgentRegistry;
import com.aismp.digitalhuman.service.AssembledAgent;
import com.aismp.digitalhuman.tool.ModelUsageContext;
import com.aismp.digitalhuman.tool.ToolCallContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数字员工对话接口（运行面）。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@code POST /{code}/chat} —— 同步，一次返回完整回答（编排层用）</li>
 *   <li>{@code GET /{code}/chat/stream} —— <b>SSE 流式</b>（前端用）：
 *       事件 meta → delta×N（回答逐段）→ tool（工具回执）→ done（元数据）</li>
 * </ul>
 *
 * <p>会话按数字员工隔离：a_chat_conversation / a_chat_message 都带 agent_code，
 * 列表接口按它过滤 —— 查数助手的历史不出现在运维助手里。
 */
@RestController
@RequestMapping("/agent")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    /** 回放的历史条数上限（20 条 ≈ 10 轮对话） */
    private static final int HISTORY_LIMIT = 20;

    /** SSE 推送线程池（每个连接占用一个线程直到流结束） */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    private final AgentRegistry registry;
    private final ConversationRepository conversations;
    private final com.aismp.digitalhuman.service.TokenUsageService tokenUsage;
    private final com.aismp.digitalhuman.service.RouterService router;

    public AgentChatController(AgentRegistry registry, ConversationRepository conversations,
                               com.aismp.digitalhuman.service.TokenUsageService tokenUsage,
                               com.aismp.digitalhuman.service.RouterService router) {
        this.registry = registry;
        this.conversations = conversations;
        this.tokenUsage = tokenUsage;
        this.router = router;
    }

    public record ChatRequest(String message, String conversationId, String userId,
                              java.util.List<String> dataScope) {
    }

    /** 智能路由入口的请求体：多带一个 role（角色圈定候选集）。 */
    public record AutoChatRequest(String message, String conversationId, String userId, String role) {
    }

    // ------------------------------------------------------------------
    // 登录身份策略
    // ------------------------------------------------------------------

    /**
     * 对话链路的 userId 以谁为准：
     * <ul>
     *   <li>真人 token（USER/ADMIN）→ 一律用登录身份，忽略请求里传的 userId
     *       （防止 A 冒充 B 计量、看 B 的会话）；</li>
     *   <li>SERVICE token（编排层等）→ 允许代传 userId（把终端用户身份透传下去），
     *       没传则落服务账号自己。</li>
     * </ul>
     */
    private static String resolveUserId(HttpServletRequest request) {
        UserPrincipal principal = AuthInterceptor.current(request);
        if (principal == null) {
            return null;
        }
        if (principal.isService()) {
            return principal.userCode();
        }
        return principal.userCode();
    }

    /** SERVICE 账号可代传；真人忽略入参。 */
    private static String resolveUserId(HttpServletRequest request, String claimedUserId) {
        UserPrincipal principal = AuthInterceptor.current(request);
        if (principal != null && principal.isService()
                && claimedUserId != null && !claimedUserId.isBlank()) {
            return claimedUserId;
        }
        return resolveUserId(request);
    }

    /**
     * 数据可见范围（2026-10-07）：同样只认 SERVICE 的代传（编排层已按调用方算好「本仓+下级仓」）。
     * 真人 token 暂不支持（门户链路已由编排层过滤；管理台/直连内核是不受限场景）。
     */
    private static java.util.Set<String> resolveDataScope(HttpServletRequest request,
                                                          java.util.List<String> claimed) {
        UserPrincipal principal = AuthInterceptor.current(request);
        if (principal != null && principal.isService() && claimed != null && !claimed.isEmpty()) {
            java.util.Set<String> scope = new java.util.LinkedHashSet<>();
            for (String code : claimed) {
                if (code != null && !code.isBlank()) {
                    scope.add(code.trim());
                }
            }
            return scope;
        }
        return java.util.Set.of();
    }

    /**
     * 把【数据权限】片段拼到本轮提问前面（让模型自己按可见范围取数）。
     *
     * <p>为什么拼在消息里而不是改系统提示词：数字员工按 version 缓存装配结果
     * （见 AgentRegistry），系统提示词是缓存的一部分；而可见范围是**请求级**的，
     * 拼消息不会污染缓存，也不必为每个用户各建一份装配。持久化仍存原始提问。
     */
    private static String withDataScope(String message, java.util.Set<String> scope) {
        if (scope.isEmpty()) {
            return message;
        }
        return "【数据权限】当前用户只能访问以下仓位的数据：" + String.join("、", scope)
                + "。涉及库存数字/报表/分布的问题，只能在这个范围内取数回答；"
                + "调用库存查询工具时把范围作为筛选条件（单仓直接用它），"
                + "范围外仓位的任何库存数据不得出现在回答中。\n\n" + message;
    }

    // ------------------------------------------------------------------
    // 同步对话（编排层用）
    // ------------------------------------------------------------------

    @PostMapping("/{agentCode}/chat")
    public Map<String, Object> chat(@PathVariable String agentCode, @RequestBody ChatRequest request,
                                    HttpServletRequest httpRequest) {
        long start = System.currentTimeMillis();
        AssembledAgent agent = registry.get(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不可用（配置不存在或已停用）: " + agentCode);
        }
        if (request == null || request.message() == null || request.message().isBlank()) {
            return Map.of("error", "message 不能为空");
        }

        // 身份：SERVICE 代传优先，真人以登录身份为准
        String userId = resolveUserId(httpRequest, request == null ? null : request.userId());
        // 数据可见范围：编排层代终端用户调用时透传（本仓 + 全部下级仓）
        java.util.Set<String> dataScope = resolveDataScope(httpRequest,
                request == null ? null : request.dataScope());

        String traceId = UUID.randomUUID().toString();
        String conversationId = orNew(request.conversationId());

        // 配额闸门：本月用量 >= 配额时直接拒绝（M4 token 分配的执行点）
        String quotaDenied = tokenUsage.checkQuota(userId);
        if (quotaDenied != null) {
            return Map.of("error", quotaDenied, "quotaDenied", true);
        }

        ToolCallContext.set(new ToolCallContext.Ctx(traceId, conversationId, userId, null, agentCode,
                dataScope));
        ModelUsageContext.install();
        try {
            List<AssembledAgent.ChatTurn> history = loadHistory(agentCode, conversationId);
            safePersist(() -> {
                conversations.ensureConversation(agentCode, conversationId, null,
                        userId, "auto", request.message());
                conversations.appendMessage(agentCode, conversationId, null, userId,
                        "user", request.message(), "auto", agent.modelName(), null, "success", null);
            });

            // 提问带上【数据权限】片段（持久化存的是原始提问，见上面的 appendMessage）
            String answer = agent.chat(withDataScope(request.message(), dataScope), history);
            long elapsed = System.currentTimeMillis() - start;
            safePersist(() -> conversations.appendMessage(agentCode, conversationId, null, userId,
                    "assistant", answer, "auto", agent.modelName(), (int) elapsed, "success", null));

            LinkedHashMap<String, Object> ok = baseResult(agent, traceId, conversationId, history.size());
            ok.put("answer", answer);
            ok.put("elapsedMs", elapsed);
            ok.put("tokenUsage", tokenUsage.flush(userId, agentCode, agent.modelName(), traceId));
            return ok;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            safePersist(() -> conversations.appendMessage(agentCode, conversationId, null, userId,
                    "assistant", null, "auto", agent.modelName(), (int) elapsed, "error", e.getMessage()));
            LinkedHashMap<String, Object> err = baseResult(agent, traceId, conversationId, 0);
            err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            err.put("elapsedMs", elapsed);
            return err;
        } finally {
            tokenUsage.flush(userId, agentCode,
                    agent == null ? "unknown" : agent.modelName(), traceId);
            ToolCallContext.clear();
        }
    }

    // ------------------------------------------------------------------
    // SSE 流式对话（前端用）
    // ------------------------------------------------------------------

    /**
     * SSE 事件序列：
     * <pre>
     *   event: meta   data: {version, conversationId, historyTurns}
     *   event: tool   data: queryStockByLocation → {...}   （每次工具回执）
     *   event: delta  data: 回答文本片段（逐段追加渲染）
     *   event: done   data: {elapsedMs}
     * </pre>
     */
    @GetMapping(value = "/{agentCode}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String agentCode,
                                 @RequestParam String message,
                                 @RequestParam(required = false) String conversationId,
                                 @RequestParam(required = false) String userId,
                                 HttpServletRequest httpRequest) {
        String effUserId = resolveUserId(httpRequest, userId);
        return startStream(agentCode, message, conversationId, effUserId, null);
    }

    /**
     * 流式对话的公共实现。
     *
     * @param routeFirst 非空时先做智能路由并推送 route 事件（auto 入口用），否则直接按 agentCode 执行
     */
    private SseEmitter startStream(String agentCode, String message, String conversationId,
                                   String userId, String routeRole) {
        SseEmitter emitter = new SseEmitter(180_000L);
        String traceId = UUID.randomUUID().toString();
        String convId = orNew(conversationId);

        sseExecutor.execute(() -> {
            long start = System.currentTimeMillis();
            ToolCallContext.set(ToolCallContext.Ctx.of(traceId, convId, userId, null, agentCode));
            ModelUsageContext.install();
            String effectiveAgent = agentCode;
            try {
                // 配额闸门（M4 token 分配的执行点）
                String quotaDenied = tokenUsage.checkQuota(userId);
                if (quotaDenied != null) {
                    emitter.send(SseEmitter.event().name("error").data(quotaDenied));
                    emitter.complete();
                    return;
                }

                // 智能路由：角色圈定 + 意图分流，route 事件让前端透出「为什么是他」
                if (routeRole != null) {
                    com.aismp.digitalhuman.service.RouterService.RouteResult route =
                            router.route(message, routeRole);
                    effectiveAgent = route.agentCode();
                    ToolCallContext.set(ToolCallContext.Ctx.of(traceId, convId, userId, null, effectiveAgent));
                    emitter.send(SseEmitter.event().name("route").data(json(
                            "agentCode", route.agentCode(),
                            "agentName", route.agentName(),
                            "reason", route.reason())));
                }

                AssembledAgent agent = registry.get(effectiveAgent);
                if (agent == null) {
                    emitter.send(SseEmitter.event().name("error").data("数字员工不可用: " + effectiveAgent));
                    emitter.complete();
                    return;
                }
                // 路由已定稿：后续 lambda 捕获用 final 局部变量
                final String agCode = effectiveAgent;
                List<AssembledAgent.ChatTurn> history = loadHistory(agCode, convId);

                emitter.send(SseEmitter.event().name("meta").data(json(
                        "version", agent.version(),
                        "conversationId", convId,
                        "historyTurns", history.size(),
                        "modelName", agent.modelName())));

                safePersist(() -> {
                    conversations.ensureConversation(agCode, convId, null, userId, "auto", message);
                    conversations.appendMessage(agCode, convId, null, userId,
                            "user", message, "auto", agent.modelName(), null, "success", null);
                });

                StringBuilder full = new StringBuilder();
                agent.streamChat(message, history, (phase, text) -> {
                    try {
                        if ("delta".equals(phase)) {
                            full.append(text);
                            emitter.send(SseEmitter.event().name("delta").data(text));
                        } else {
                            emitter.send(SseEmitter.event().name("tool").data(text));
                        }
                    } catch (java.io.IOException e) {
                        // 客户端断开等 IO 失败：向上抛，由外层统一记录并结束
                        throw new RuntimeException(e);
                    }
                });

                long elapsed = System.currentTimeMillis() - start;
                String answer = full.toString();
                safePersist(() -> conversations.appendMessage(agCode, convId, null, userId,
                        "assistant", answer, "auto", agent.modelName(), (int) elapsed, "success", null));

                Map<String, Object> usage = tokenUsage.flush(userId, agCode, agent.modelName(), traceId);
                emitter.send(SseEmitter.event().name("done").data(json(
                        "elapsedMs", elapsed, "traceId", traceId,
                        "agentCode", agCode,
                        "totalTokens", usage.get("totalTokens"))));
                emitter.complete();

            } catch (Exception e) {
                log.warn("SSE 对话失败: {}", e.getMessage());
                final String agCode = effectiveAgent;
                safePersist(() -> conversations.appendMessage(agCode, convId, null, userId,
                        "assistant", null, "auto", null, (int) (System.currentTimeMillis() - start),
                        "error", e.getMessage()));
                tokenUsage.flush(userId, effectiveAgent, "unknown", traceId);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                    emitter.complete();
                } catch (Exception ignored) {
                    // 连接已断开
                }
            } finally {
                ToolCallContext.clear();
            }
        });
        return emitter;
    }

    // ------------------------------------------------------------------
    // 智能路由入口（agentCode=auto）
    // ------------------------------------------------------------------

    /** 某角色可用的数字员工列表（用户端员工卡片按它渲染）。 */
    @GetMapping("/agents-for-role")
    public List<Map<String, Object>> agentsForRole(@RequestParam(required = false) String role) {
        return router.agentsForRole(role).stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentCode", a.getAgentCode());
            m.put("agentName", a.getAgentName());
            m.put("description", a.getDescription());
            return m;
        }).toList();
    }

    /** 同步智能路由对话：路由一次 + 执行，回答里带 route 依据。 */
    @PostMapping("/auto/chat")
    public Map<String, Object> autoChat(@RequestBody AutoChatRequest request,
                                        HttpServletRequest httpRequest) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return Map.of("error", "message 不能为空");
        }
        String effUserId = resolveUserId(httpRequest, request.userId());
        String quotaDenied = tokenUsage.checkQuota(effUserId);
        if (quotaDenied != null) {
            return Map.of("error", quotaDenied, "quotaDenied", true);
        }
        try {
            com.aismp.digitalhuman.service.RouterService.RouteResult route =
                    router.route(request.message(), request.role());
            Map<String, Object> result = chat(route.agentCode(),
                    new ChatRequest(request.message(), request.conversationId(), effUserId, null),
                    httpRequest);
            result.put("route", Map.of("agentCode", route.agentCode(),
                    "agentName", route.agentName(), "reason", route.reason()));
            return result;
        } catch (IllegalStateException e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** 流式智能路由对话：SSE 多一个 route 事件（meta 之前）。 */
    @GetMapping(value = "/auto/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter autoChatStream(@RequestParam String message,
                                     @RequestParam(required = false) String conversationId,
                                     @RequestParam(required = false) String userId,
                                     @RequestParam(required = false) String role,
                                     HttpServletRequest httpRequest) {
        String effUserId = resolveUserId(httpRequest, userId);
        return startStream("auto", message, conversationId, effUserId, role == null ? "" : role);
    }

    /** 运行时自省。 */
    @GetMapping("/{agentCode}/introspect")
    public Map<String, Object> introspect(@PathVariable String agentCode) {
        AssembledAgent agent = registry.get(agentCode);
        if (agent == null) {
            return Map.of("error", "数字员工不可用: " + agentCode);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentCode", agent.agentCode());
        result.put("version", agent.version());
        result.put("skills", agent.skillNames());
        result.put("tools", agent.toolNames());
        result.put("systemPrompt", agent.systemPrompt());
        result.put("assembledAt", agent.assembledAt());
        return result;
    }

    // ------------------------------------------------------------------
    // 会话管理（按数字员工隔离）
    // ------------------------------------------------------------------

    @GetMapping("/conversation/list")
    public List<Map<String, Object>> listConversations(
            @RequestParam String agentCode,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {
        // 真人只看自己的会话；SERVICE 账号可代传
        String effUserId = resolveUserId(httpRequest, userId);
        return conversations.listConversations(agentCode, effUserId, Math.min(Math.max(limit, 1), 100));
    }

    @GetMapping("/conversation/{conversationId}")
    public Map<String, Object> conversationDetail(@PathVariable String conversationId,
                                                  @RequestParam(defaultValue = "100") int messageLimit) {
        Map<String, Object> detail = conversations.conversationDetail(conversationId, messageLimit);
        return detail == null ? Map.of("error", "会话不存在: " + conversationId) : detail;
    }

    @PostMapping("/conversation/{conversationId}/archive")
    public Map<String, Object> archive(@PathVariable String conversationId) {
        return Map.of("archived", conversations.archive(conversationId));
    }

    @GetMapping("/conversation/stats")
    public Map<String, Object> conversationStats() {
        return conversations.stats();
    }

    @GetMapping("/conversation/stats/by-agent")
    public List<Map<String, Object>> statsByAgent() {
        return conversations.statsByAgent();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private List<AssembledAgent.ChatTurn> loadHistory(String agentCode, String conversationId) {
        try {
            return conversations.loadHistory(agentCode, conversationId, HISTORY_LIMIT).stream()
                    .map(t -> new AssembledAgent.ChatTurn(t.role(), t.content()))
                    .toList();
        } catch (Exception e) {
            log.warn("加载会话历史失败，本轮按无上下文处理: {}", e.getMessage());
            return List.of();
        }
    }

    private void safePersist(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("会话落库失败（不影响本次回答）: {}", e.getMessage());
        }
    }

    private static String orNew(String id) {
        return (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
    }

    private static LinkedHashMap<String, Object> baseResult(AssembledAgent agent,
                                                            String traceId, String conversationId,
                                                            int historyTurns) {
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        r.put("agentCode", agent.agentCode());
        r.put("version", agent.version());
        r.put("traceId", traceId);
        r.put("conversationId", conversationId);
        r.put("historyTurns", historyTurns);
        return r;
    }

    private static String json(Object... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(kv[i]).append("\":");
            Object v = kv[i + 1];
            sb.append(v instanceof Number ? v : "\"" + v + "\"");
        }
        return sb.append('}').toString();
    }
}
