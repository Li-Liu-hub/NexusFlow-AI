package com.aismp.digitalhuman.tool;

import com.aismp.digitalhuman.model.ToolDefPO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 接口调用型工具：把「调一个业务 HTTP 接口」变成一个可挂载的工具。
 *
 * <p><b>为什么要有这个类型</b>：内核原先只有 {@code SQL_TEMPLATE}（直连数据库），
 * 于是同一份终端业务存在<b>两个数据出口</b> —— 内核查 {@code terminal_db}，
 * 而业务实现（aismp-agent）查 {@code api_db}，口径必然漂移。
 * 架构调整后业务实现归 aismp-agent，内核只提供 agent 能力，
 * 所以工具也应该改成**调 agent 的接口**而不是自己查库。
 *
 * <p><b>调用目标怎么定（两种，优先服务发现）</b>：
 * <ol>
 *   <li>配了 {@code serviceName}（如 {@code aismp-agent}）→ 通过 {@link DiscoveryClient}
 *       从 Nacos 取实例地址。这是「服务注册后发现」的正路。</li>
 *   <li>否则用 {@code baseUrl} 直连（本机调试的兜底）。</li>
 * </ol>
 *
 * <p><b>config_json 结构</b>：
 * <pre>
 * {
 *   "serviceName": "aismp-agent",                      // 可选，优先
 *   "baseUrl":     "http://127.0.0.1:8124",            // 可选，兜底
 *   "endpoint":    "/terminal/inventory/aggregate",    // 必填
 *   "method":      "GET",                              // 可选，默认 GET
 *   "timeoutSeconds": 30,                              // 可选，默认 30
 *   "params": {                                        // 可选：LLM 入参 → 接口参数 的映射
 *     "warehouseCode": "${warehouseCode}",
 *     "fixed": "always-this"
 *   }
 * }
 * </pre>
 * 参数值里写 {@code ${xxx}} 表示「取 LLM 本次调用入参里的 xxx」，
 * 其余字面量原样传（用于固定筛选条件）。
 */
public class ApiCallToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ApiCallToolCallback.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final ToolDefPO def;
    private final ObjectMapper json;
    private final DiscoveryClient discoveryClient;

    private final String serviceName;
    private final String baseUrl;
    private final String endpoint;
    private final String method;
    private final Map<String, String> paramMapping;
    private final Duration timeout;
    private final HttpClient httpClient;

    public ApiCallToolCallback(ToolDefPO def, ObjectMapper json, DiscoveryClient discoveryClient) {
        this.def = def;
        this.json = json;
        this.discoveryClient = discoveryClient;

        String svc = null;
        String base = null;
        String ep = null;
        String m = "GET";
        int ts = DEFAULT_TIMEOUT_SECONDS;
        Map<String, String> mapping = new LinkedHashMap<>();

        String cfg = def.getConfigJson();
        if (cfg != null && !cfg.isBlank()) {
            try {
                Map<?, ?> c = json.readValue(cfg, Map.class);
                if (c.get("serviceName") != null) svc = String.valueOf(c.get("serviceName"));
                if (c.get("baseUrl") != null) base = String.valueOf(c.get("baseUrl"));
                if (c.get("endpoint") != null) ep = String.valueOf(c.get("endpoint"));
                if (c.get("method") != null) m = String.valueOf(c.get("method"));
                if (c.get("timeoutSeconds") != null) ts = Integer.parseInt(String.valueOf(c.get("timeoutSeconds")));
                if (c.get("params") instanceof Map<?, ?> p) {
                    for (Map.Entry<?, ?> e : p.entrySet()) {
                        mapping.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
            } catch (Exception e) {
                log.warn("工具 [{}] 的 config_json 解析失败：{}", def.getToolName(), e.getMessage());
            }
        }
        this.serviceName = svc;
        this.baseUrl = base;
        this.endpoint = ep;
        this.method = m.toUpperCase();
        this.paramMapping = mapping;
        this.timeout = Duration.ofSeconds(ts);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(def.getToolName())
                .description(def.getToolDesc())
                .inputSchema(def.getInputSchema())
                .build();
    }

    @Override
    public String call(String toolInput) {
        long start = System.currentTimeMillis();
        try {
            if (endpoint == null || endpoint.isBlank()) {
                return errorJson("工具未配置 endpoint");
            }
            String root = resolveRoot();
            if (root == null) {
                return errorJson("找不到目标服务（serviceName=" + serviceName
                        + " 未在注册中心发现可用实例，且未配置 baseUrl）");
            }

            Map<String, Object> in = parseInput(toolInput);
            Map<String, String> query = buildQuery(in);

            // 数据可见范围收口（2026-10-07）：只对「编排层代终端用户」的链路生效
            //   · 模型显式查了范围外的仓位 → 直接拒绝，让它改用可见仓位；
            //   · 单仓用户没限定仓位 → 自动补上（"全部"对他就是"本仓"，聚合/快照都能真收口）。
            Set<String> scope = currentScope();
            if (!scope.isEmpty()) {
                String asked = query.get("warehouseCode");
                if (asked != null && !asked.isBlank() && !scope.contains(asked)) {
                    log.info("工具 [{}] 越权仓位被拒：{}（可见范围 {}）",
                            def.getToolName(), asked, scope);
                    return errorJson("无权限访问仓位 " + asked
                            + "（当前用户的可见范围：" + String.join("、", scope) + "）");
                }
                if ((asked == null || asked.isBlank()) && scope.size() == 1) {
                    query.put("warehouseCode", scope.iterator().next());
                }
            }

            String url = root + endpoint + (query.isEmpty() ? "" : "?" + toQueryString(query));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            long cost = System.currentTimeMillis() - start;
            if (resp.statusCode() != 200) {
                // 不把 HTML 错误页塞给模型（占 token 且会诱导它编造原因），只回状态码
                log.warn("接口工具 [{}] 下游异常 status={} cost={}ms url={}",
                        def.getToolName(), resp.statusCode(), cost, url);
                return errorJson("业务服务暂时不可用（status=" + resp.statusCode() + "）");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result", parseBody(resp.body()));
            result.put("costMs", cost);
            return json.writeValueAsString(result);

        } catch (Exception e) {
            log.warn("接口工具 [{}] 执行失败：{}", def.getToolName(), e.getMessage());
            return errorJson(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 当前请求的数据可见范围（无上下文/未限制 → 空集）。 */
    private static Set<String> currentScope() {
        ToolCallContext.Ctx ctx = ToolCallContext.get();
        return ctx == null ? Set.of() : ctx.scope();
    }

    /** 服务发现优先，直连兜底。 */
    private String resolveRoot() {
        if (serviceName != null && !serviceName.isBlank() && discoveryClient != null) {
            try {
                List<ServiceInstance> instances = discoveryClient.getInstances(serviceName);
                if (instances != null && !instances.isEmpty()) {
                    ServiceInstance i = instances.get(0);
                    return "http://" + i.getHost() + ":" + i.getPort();
                }
                log.warn("服务 [{}] 在注册中心无可用实例，回退直连", serviceName);
            } catch (Exception e) {
                log.warn("服务发现查询 [{}] 失败，回退直连：{}", serviceName, e.getMessage());
            }
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        return null;
    }

    /** 模型有时不按 JSON 传参（直接把原文塞进来），这里做容错。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            Object o = json.readValue(toolInput, Map.class);
            return o instanceof Map ? (Map<String, Object>) o : Map.of();
        } catch (Exception e) {
            // 不是 JSON：把整串当作唯一入参（与 KnowledgeRetrievalToolCallback 同样的容错）
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("query", toolInput.trim());
            return single;
        }
    }

    /** 按映射表把 LLM 入参转成接口 query 参数。 */
    private Map<String, String> buildQuery(Map<String, Object> in) {
        Map<String, String> query = new LinkedHashMap<>();
        if (paramMapping.isEmpty()) {
            // 没配映射：把入参里所有非空值直接透传（键名保持一致）
            in.forEach((k, v) -> {
                if (v != null && !String.valueOf(v).isBlank()) {
                    query.put(k, String.valueOf(v));
                }
            });
            return query;
        }
        for (Map.Entry<String, String> e : paramMapping.entrySet()) {
            String target = e.getKey();
            String expr = e.getValue();
            if (expr != null && expr.startsWith("${") && expr.endsWith("}")) {
                String sourceKey = expr.substring(2, expr.length() - 1);
                Object v = in.get(sourceKey);
                if (v != null && !String.valueOf(v).isBlank()) {
                    query.put(target, String.valueOf(v));
                }
            } else if (expr != null && !expr.isBlank()) {
                query.put(target, expr);
            }
        }
        return query;
    }

    private static String toQueryString(Map<String, String> q) {
        StringBuilder sb = new StringBuilder();
        q.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    /** 解析下游响应：能解析成 JSON 就转成对象（模型读结构化数据更准），否则原样返回文本。 */
    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> m = json.readValue(body, Map.class);
            // 业务统一返回 {code,data,message}，只把 data 交给模型，省 token
            if (m.containsKey("data")) {
                return m.get("data");
            }
            return m;
        } catch (Exception e) {
            return body;
        }
    }

    private String errorJson(String message) {
        try {
            return json.writeValueAsString(Map.of("error", message));
        } catch (Exception ignored) {
            return "{\"error\":\"接口调用失败\"}";
        }
    }
}
