package com.aismp.aismpiclientsdk.client;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;

import java.util.HashMap;
import java.util.Map;

import static com.aismp.aismpiclientsdk.utils.SignUtils.getSign;

/**
 * 开放平台客户端：以 accessKey / secretKey 对请求签名，经统一网关调用后端接口。
 *
 * <p>所有流量都走网关，由网关完成身份校验、限流与计量；客户端不直连业务服务。
 */
public class OpenApiClient {

    /** 默认网关地址（本机开发） */
    public static final String DEFAULT_GATEWAY_HOST = "http://localhost:8090";

    private final String gatewayHost;

    private final String accessKey;

    private final String secretKey;

    public OpenApiClient(String accessKey, String secretKey) {
        this(accessKey, secretKey, DEFAULT_GATEWAY_HOST);
    }

    public OpenApiClient(String accessKey, String secretKey, String gatewayHost) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.gatewayHost = gatewayHost;
    }

    /**
     * 通用接口调用方法
     *
     * @param path   请求路径（含网关侧前缀），如 /api/terminal/inventory/health
     * @param method 请求方法，GET/POST
     * @param body   请求体参数（JSON 字符串）；GET 请求会转为 query 参数
     * @return 响应结果
     */
    public String invokeApi(String path, String method, String body) {
        if (body != null) {
            body = body.trim();
        }
        HttpRequest httpRequest;
        if ("GET".equalsIgnoreCase(method)) {
            httpRequest = HttpRequest.get(gatewayHost + path);
            if (body != null && !body.isEmpty()) {
                Map<String, Object> paramMap = JSONUtil.toBean(body, Map.class);
                httpRequest.form(paramMap);
            }
        } else {
            httpRequest = HttpRequest.post(gatewayHost + path);
            if (body != null) {
                httpRequest.body(body);
            }
        }
        httpRequest.addHeaders(getHashMap(body));
        HttpResponse httpResponse = httpRequest.execute();
        return httpResponse.body();
    }

    /**
     * 构造网关要求的签名请求头：accessKey + 随机串 + 时间戳 + 请求体 + 签名。
     * secretKey 只参与签名计算，绝不放请求头。
     */
    private Map<String, String> getHashMap(String body) {
        HashMap<String, String> hashMap = new HashMap<>();
        hashMap.put("accessKey", accessKey);
        // 高熵随机串（32 位 hex），替换原 5 位数字方案，避免高并发下随机数撞车被误判为重放
        hashMap.put("nonce", java.util.UUID.randomUUID().toString().replace("-", ""));
        hashMap.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        hashMap.put("body", body);
        hashMap.put("sign", getSign(body, secretKey));
        return hashMap;
    }
}
