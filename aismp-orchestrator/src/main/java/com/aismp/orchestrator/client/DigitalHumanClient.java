package com.aismp.orchestrator.client;

import com.aismp.orchestrator.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数字员工内核（aismp-digital-human，:8126）客户端。
 *
 * <p>迁移对照：{@code agent-orchestrator/tools/digital_human_api.py}
 * <ul>
 *   <li>直连路径 {@code /agent/{agentCode}/chat}（不带 {@code /digitalHuman} 前缀）</li>
 *   <li>Bearer 令牌由服务账号 {@code /auth/login} 换取，进程内缓存，401 时重登一次</li>
 * </ul>
 *
 * <p>TODO(阶段 2)：网关形态（{@code DIGITAL_HUMAN_VIA_GATEWAY=true}）还没迁 ——
 * 需要移植 AK/SK 签名 + nonce 防重放（{@code gateway_sign.py}）。
 * 直连形态已经够本机与 compose 内网使用，且不改变调用语义。
 */
@Component
public class DigitalHumanClient {

    private static final Logger log = LoggerFactory.getLogger(DigitalHumanClient.class);

    private final OrchestratorProperties.DigitalHuman cfg;
    private final RestClient restClient;
    private volatile String cachedToken;

    public DigitalHumanClient(OrchestratorProperties properties, RestClient.Builder builder) {
        this.cfg = properties.getDigitalHuman();
        this.restClient = builder.build();
    }

    /**
     * 让指定数字员工回答一个问题。
     *
     * @param agentCode      数字员工编码（a_agent.agent_code），决定人设与工具集
     * @param message        用户问题原文
     * @param dataScope      数据可见范围（本仓 + 下级仓）；空表示不限制
     * @param conversationId 会话 id，内核据此做选择性记忆（追问能接上下文）
     */
    @SuppressWarnings("unchecked")
    public ChatResult chat(String agentCode, String message,
                           List<String> dataScope, String conversationId) {
        String token = serviceToken(false);
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);
        if (dataScope != null && !dataScope.isEmpty()) {
            payload.put("dataScope", dataScope);
        }
        if (conversationId != null && !conversationId.isBlank()) {
            payload.put("conversationId", conversationId);
        }

        Map<String, Object> body = postChat(agentCode, payload, token);
        if (body == null) {
            // 令牌过期/被清：重登一次再试，与 Python 版 401 重试分支对齐
            body = postChat(agentCode, payload, serviceToken(true));
            if (body == null) {
                throw new DigitalHumanException("数字员工返回空响应");
            }
        }

        Object answer = body.get("answer");
        Object version = body.get("version");
        return new ChatResult(answer == null ? "" : String.valueOf(answer),
                version instanceof Number n ? n.intValue() : null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postChat(String agentCode, Map<String, Object> payload, String token) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri(cfg.getDirectUrl() + "/agent/" + agentCode + "/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            if (response == null) {
                return null;
            }
            // 内核统一响应体 {code,data,message}：非 0 视为失败
            if (response.get("code") instanceof Number code && code.intValue() != 0) {
                throw new DigitalHumanException("业务错误 code=" + code
                        + " message=" + response.get("message"));
            }
            if (response.get("data") instanceof Map<?, ?> data) {
                response = (Map<String, Object>) data;
            }
            if (response.get("error") != null) {
                throw new DigitalHumanException("数字员工执行异常: " + response.get("error"));
            }
            return response;
        } catch (DigitalHumanException e) {
            throw e;
        } catch (RuntimeException e) {
            // 连不上时会抛资源访问异常，这里转成领域异常，错误信息带上实际目标地址
            log.warn("调用数字员工失败（{}）", cfg.getDirectUrl(), e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String serviceToken(boolean forceRefresh) {
        String token = cachedToken;
        if (token != null && !forceRefresh) {
            return token;
        }
        synchronized (this) {
            if (cachedToken != null && !forceRefresh) {
                return cachedToken;
            }
            if (cfg.getServiceUser() == null || cfg.getServiceUser().isBlank()) {
                throw new DigitalHumanException(
                        "未配置服务账号（ORCH_SVC_USER / ORCH_SVC_PASSWORD），无法换取内核令牌");
            }
            Map<String, Object> body = restClient.post()
                    .uri(cfg.getDirectUrl() + "/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userCode", cfg.getServiceUser(),
                            "password", cfg.getServicePassword()))
                    .retrieve()
                    .body(Map.class);
            String t = null;
            if (body != null) {
                if (body.get("token") instanceof String s) {
                    t = s;
                } else if (body.get("data") instanceof Map<?, ?> data && data.get("token") instanceof String s) {
                    t = s;
                }
            }
            if (t == null || t.isBlank()) {
                throw new DigitalHumanException("服务账号登录失败：响应中没有 token");
            }
            cachedToken = t;
            return t;
        }
    }

    /** 内核回答结果。 */
    public record ChatResult(String answer, Integer version) {
    }

    /** 内核调用失败（不可达 / 业务错误 / 登录失败）。 */
    public static class DigitalHumanException extends RuntimeException {
        public DigitalHumanException(String message) {
            super(message);
        }
    }
}
