package com.aismp.orchestrator.client;

import com.aismp.orchestrator.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 库存数据取数客户端（aismp-agent 的业务接口）。
 *
 * <p><b>走直连而不是网关</b>：这些接口都是内部只读查询，编排层与 agent 在同一个 docker 网络里。
 * 走网关需要 AK/SK 验签 + interface_info 登记，多一层故障点；而计量本来就按网关入口算，
 * 编排层内部取数属于同一笔请求的展开，不重复计量。
 *
 * <p><b>失败必须抛异常，不能返回空集合</b>：空集合会被算法算成「0 条建议」，
 * 再交给模型措辞就会写成「库存均衡、暂无需操作」——把「数据没取到」误报成「库存健康」，
 * 这是最危险的一类误导。上层据此区分 500 与「真的没建议」。
 */
@Component
public class TerminalApiClient {

    private static final Logger log = LoggerFactory.getLogger(TerminalApiClient.class);

    public static final String PATH_SNAPSHOT = "/api/terminal/inventory/snapshot";
    public static final String PATH_AGGREGATE = "/api/terminal/inventory/aggregate";
    public static final String PATH_AGING = "/api/terminal/inventory/aging";
    public static final String PATH_BY_MODEL = "/api/terminal/inventory/byModel";
    public static final String PATH_INBOUND_TREND = "/api/terminal/inventory/inboundTrend";

    private final RestClient restClient;
    private final String baseUrl;
    private final PortalTokenProvider tokens;

    public TerminalApiClient(OrchestratorProperties properties, RestClient.Builder builder,
                             PortalTokenProvider tokens) {
        this.baseUrl = properties.getTerminal().getBaseUrl();
        this.restClient = builder.build();
        this.tokens = tokens;
    }

    /** 库存快照：四个总数 + 库龄分桶 + 型号汇总 + 明细行（同一时刻的一致视图） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot() {
        return (Map<String, Object>) get(PATH_SNAPSHOT);
    }

    /** 「仓位 × 物料」粒度的聚合明细 —— 补货算法与分布类图表的取数源 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> aggregate() {
        Object data = get(PATH_AGGREGATE);
        return data instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** 库龄分桶（band/cnt/sortNo） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> aging() {
        Object data = get(PATH_AGING);
        return data instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** 型号汇总（无仓位维度，只用于全量口径的图表） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> byModel() {
        Object data = get(PATH_BY_MODEL);
        return data instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /** 入库趋势：按首次入库时间归桶 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inboundTrend(String granularity, String from, String to, String groupBy) {
        StringBuilder url = new StringBuilder(PATH_INBOUND_TREND).append("?granularity=").append(granularity);
        if (from != null && !from.isBlank()) {
            url.append("&from=").append(from);
        }
        if (to != null && !to.isBlank()) {
            url.append("&to=").append(to);
        }
        if (groupBy != null && !groupBy.isBlank()) {
            url.append("&groupBy=").append(groupBy);
        }
        Object data = get(url.toString());
        return data instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /**
     * GET 并解包 {@code {code,data}} 信封。
     * 网络异常与业务错误都转成 {@link TerminalApiException}，由节点统一降级成人话。
     */
    private Object get(String path) {
        Map<String, Object> body;
        try {
            body = call(path, tokens.token());
        } catch (HttpClientErrorException.Unauthorized e) {
            // 令牌过期：刷新一次再重试（网关的令牌有效期 12h，长跑进程会碰到）
            log.info("库存接口返回 401，刷新服务令牌后重试：{}", path);
            body = call(path, tokens.refresh());
        } catch (RuntimeException e) {
            log.warn("终端业务接口不可用：{}", path, e);
            throw new TerminalApiException("库存服务不可达（" + path + "）：" + e.getMessage(), e);
        }
        if (body == null) {
            throw new TerminalApiException("库存服务返回空响应（" + path + "）", null);
        }
        Object code = body.get("code");
        if (code != null && !"0".equals(String.valueOf(code))) {
            throw new TerminalApiException("库存服务返回错误（code=" + code + "）：" + body.get("message"), null);
        }
        return body.containsKey("data") ? body.get("data") : body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String path, String token) {
        return restClient.get()
                .uri(baseUrl + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(Map.class);
    }

    /** 取数失败（网络或业务错误）—— 与「查到了但结果为空」严格区分 */
    public static class TerminalApiException extends RuntimeException {
        public TerminalApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
