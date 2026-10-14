package com.aismp.digitalhuman.service;

import com.aismp.digitalhuman.repository.TokenUsageRepository;
import com.aismp.digitalhuman.tool.ModelUsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * token 用量服务：落账上报与配额闸门（内核侧的两个钩子）。
 *
 * <p><b>2026-10-06 批 A 后的边界</b>：账本（a_token_quota / a_token_usage_* 表与管理面
 * CRUD）已迁到 backend —— 内核只在对话入口经 {@code /api/inner/token/check} 查配额、
 * 出口经 {@code /api/inner/token/charge} 上报用量（见 {@link BackendTokenClient}）。
 * 本机仅保留「工具调用次数」统计（a_tool_call_log 在内核库），随用量一并上报。
 *
 * <p><b>计量链路</b>：ChatModel 装饰器把每一跳的用量累计进 {@link ModelUsageContext}（ThreadLocal）
 * → 控制器在请求出口调用 {@link #flush} 上报 backend。
 *
 * <p><b>失败语义</b>：落账失败只打日志不抛 —— 计量是旁路，不能因为记账失败让用户的对话报错。
 */
@Service
public class TokenUsageService {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    private final TokenUsageRepository repository;

    /** 平台账本出口（2026-10-06 批 A：配额闸门与落账都在 backend）。 */
    private final BackendTokenClient backend;

    public TokenUsageService(TokenUsageRepository repository, BackendTokenClient backend) {
        this.repository = repository;
        this.backend = backend;
    }

    /**
     * 把本次请求累计的模型用量上报 backend 落账（批 A：账本在 backend）。
     *
     * @return 落账结果摘要（never null），供 done 事件与日志使用
     */
    public Map<String, Object> flush(String userId, String agentCode, String model, String traceId) {
        ModelUsageContext.Totals usage = ModelUsageContext.collectAndClear();
        long total = usage.promptTokens() + usage.completionTokens();
        try {
            if (usage.modelCalls() > 0) {
                int toolCalls = repository.countToolCalls(traceId);
                backend.charge(userId, agentCode, model, usage.modelCalls(),
                        usage.promptTokens(), usage.completionTokens(), total, toolCalls);
            }
        } catch (Exception e) {
            log.warn("token 用量上报 backend 失败（忽略）: {}", e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promptTokens", usage.promptTokens());
        result.put("completionTokens", usage.completionTokens());
        result.put("totalTokens", total);
        result.put("modelCalls", usage.modelCalls());
        return result;
    }

    /**
     * 对话入口的配额闸门（批 A：配额在 backend，经内部接口查询）。
     *
     * @return null = 放行；否则返回给前端的拒绝原因
     */
    public String checkQuota(String userId) {
        try {
            return backend.checkQuota(userId);
        } catch (Exception e) {
            log.warn("配额校验失败（默认放行）: {}", e.getMessage());
            return null;
        }
    }
}