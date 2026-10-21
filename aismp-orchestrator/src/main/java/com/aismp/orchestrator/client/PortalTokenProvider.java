package com.aismp.orchestrator.client;

import com.aismp.orchestrator.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 门户令牌提供者：编排层用<b>服务账号</b>登录网关，拿一个 Bearer 令牌去查业务接口。
 *
 * <p>为什么不能直连 aismp-agent：业务接口在网关上做了身份注入与接口登记，
 * 绕过网关直接打 agent 会返回 {@code code:50000 系统错误}（实测）。
 * 换句话说，网关的鉴权不是可选项，它是这些接口能用的前提。
 *
 * <p>为什么用服务账号而不是转发用户令牌：编排层拿不到用户的原始令牌
 * （网关在入口就把 Bearer 换成了注入头），而建议生成要跨仓看数据，
 * 本来也不该受发起人可见范围限制 —— 范围收口在算法产出侧做（见 AlgorithmNode）。
 */
@Component
public class PortalTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(PortalTokenProvider.class);
    private static final long REFRESH_AHEAD_MILLIS = 5 * 60 * 1000L;

    private final RestClient restClient;
    private final OrchestratorProperties properties;

    private String cachedToken;
    private long expireAt;

    public PortalTokenProvider(OrchestratorProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    /** 取令牌；缓存有效则直接返回，否则重新登录 */
    public synchronized String token() {
        if (cachedToken != null && System.currentTimeMillis() < expireAt) {
            return cachedToken;
        }
        return login();
    }

    /** 强制刷新（网关返回 401 时调用） */
    public synchronized String refresh() {
        cachedToken = null;
        return login();
    }

    @SuppressWarnings("unchecked")
    private String login() {
        String user = properties.getDigitalHuman().getServiceUser();
        String password = properties.getDigitalHuman().getServicePassword();
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            throw new TerminalApiClient.TerminalApiException("未配置服务账号（ORCH_SVC_USER / ORCH_SVC_PASSWORD）", null);
        }
        String base = properties.getTerminal().getBaseUrl();
        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri(base + "/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("userCode", user, "password", password))
                    .retrieve()
                    .body(Map.class);
        } catch (RuntimeException e) {
            throw new TerminalApiClient.TerminalApiException(
                    "服务账号登录失败（" + base + "）：" + e.getMessage(), e);
        }
        if (body == null || body.get("token") == null) {
            throw new TerminalApiClient.TerminalApiException("服务账号登录无令牌返回：" + body, null);
        }
        cachedToken = String.valueOf(body.get("token"));
        int hours = body.get("expiresInHours") instanceof Number n ? n.intValue() : 12;
        expireAt = System.currentTimeMillis() + hours * 3600_000L - REFRESH_AHEAD_MILLIS;
        log.info("编排层服务账号已登录网关（user={}，令牌有效期 {}h）", user, hours);
        return cachedToken;
    }
}
