package com.aismp.project.controller;

import com.aismp.project.common.ErrorCode;
import com.aismp.project.exception.BusinessException;
import com.aismp.aismpcommon.model.entity.User;
import com.aismp.project.service.UserService;
import com.aismp.aismpiclientsdk.utils.SignUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * 管理端数字员工配置的代理出口：管理端前端 → backend（登录态鉴权）→ 网关 → 数字员工内核。
 *
 * <p><b>为什么需要这层代理</b>：
 * 数字员工内核的管理接口（/admin/**）已在网关登记、走 AK/SK 鉴权。但 AK/SK 是**发给第三方开发者**的凭证，
 * 如果让管理端前端直接持有并调用网关，等于把长期凭证交到浏览器里 —— 任何人打开控制台都能拿走。
 * 而管理端正确的鉴权模型是**登录态 + 角色**：用户登录后由 backend 校验其是否为管理员。
 *
 * <p>所以这里做两件事：
 * <ol>
 *   <li>{@code @AuthCheck(mustRole = "admin")} —— 校验登录态与管理员角色，前端只需带 session</li>
 *   <li>转发时在**服务端**补上 AK/SK 签名 —— 凭证不出服务端</li>
 * </ol>
 *
 * <p><b>路径映射</b>：前端调 {@code /admin/digitalHuman/agent/list}
 * → 转发到网关 {@code /digitalHuman/admin/agent/list}。
 * 这样前端路径（/admin 开头）与网关登记路径（/digitalHuman 开头）各自独立、互不耦合。
 */
@RestController
@RequestMapping("/admin/digitalHuman")
public class DigitalHumanAdminProxyController {

    /** 前端路径前缀（本类映射的） */
    private static final String LOCAL_PREFIX = "/admin/digitalHuman";

    /**
     * 用方法内显式校验而不是 {@code @AuthCheck} 注解：
     * 该注解是 {@code @Target(METHOD)}（见 annotation/AuthCheck.java），无法标在类上；
     * 而本类只有一个通配入口方法，把校验写在方法首行反而更直白，也能给出更明确的失败原因。
     */
    @Resource
    private UserService userService;

    @Value("${aismp.client.access-key}")
    private String accessKey;

    @Value("${aismp.client.secret-key}")
    private String secretKey;

    @Value("${aismp.gateway.base-url}")
    private String gatewayBaseUrl;

    @Value("${aismp.gateway.digital-human-prefix}")
    private String gatewayPrefix;

    /** 数字员工内核直连地址（仅用于服务账号登录 /auth/login，业务请求仍走网关） */
    @Value("${aismp.digital-human.base-url:http://127.0.0.1:8126}")
    private String digitalHumanBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 内核登录鉴权（69 号迁移起）：编排代理用 SERVICE 账号换 Bearer 令牌再转发。
     * token 缓存在实例字段（24h 有效）；收到 401 时清缓存重登一次。
     */
    private volatile String svcToken;

    private String serviceToken() {
        if (svcToken != null) {
            return svcToken;
        }
        synchronized (this) {
            if (svcToken != null) {
                return svcToken;
            }
            try {
                org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
                h.setContentType(MediaType.APPLICATION_JSON);
                String loginBody = "{\"userCode\":\"svc_orchestrator\",\"password\":\"svc-orch-2026\"}";
                ResponseEntity<String> resp = restTemplate.postForEntity(
                        digitalHumanBaseUrl + "/auth/login", new HttpEntity<>(loginBody, h), String.class);
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.getBody());
                String token = node.path("token").asText(null);
                if (token == null || token.isEmpty()) {
                    return null;   // 登录失败不阻塞转发 —— 内核侧给出 401，问题在下一次调用时暴露
                }
                svcToken = token;
                return svcToken;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * 统一转发入口：GET / POST / PUT / DELETE 全部走这里。
     *
     * <p>不按方法拆多个方法，是因为转发逻辑（拼路径、签名、透传响应）与 HTTP 方法无关，
     * 拆开只会变成四份重复代码。
     */
    @RequestMapping("/**")
    public ResponseEntity<String> proxy(@RequestBody(required = false) String body,
                                        HttpServletRequest request) {
        // 登录态 + 管理员角色校验：前端只需带 session，AK/SK 始终留在服务端
        User loginUser = userService.getLoginUser(request);
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (!userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        String method = request.getMethod();

        // 拼目标地址：/admin/digitalHuman + /agent/list  →  /digitalHuman/admin/agent/list
        //
        // 注意用 getServletPath() 而不是 getRequestURI()：后者**包含 context-path**（本服务是 /api），
        // 用它做前缀截取会算错位置（首版就踩了：截出 "talHuman/agent/list"）。
        // getServletPath() 返回的是去掉 context-path 后的路径，与之无关。
        String uri = request.getServletPath();
        String subPath = uri.length() > LOCAL_PREFIX.length()
                ? uri.substring(LOCAL_PREFIX.length())
                : "";
        String query = request.getQueryString();
        String target = gatewayBaseUrl + gatewayPrefix + subPath
                + (query == null || query.isEmpty() ? "" : "?" + query);

        // body 要同时作为请求体与 header（供网关验签），必须先转成纯 ASCII：
        // HTTP header 值规范上只允许 ASCII，中文直接塞进 header 会乱码 → 签名不匹配 → 403。
        // Unicode 转义形式（形如反斜杠加 u 加四位十六进制）在 JSON 中合法，服务端解析后内容不变（与编排层 Python 侧 ensure_ascii=True 一致）。
        // 注意：阶段3 已升到 JDK 21，这里保留 trim().isEmpty() 写法没有功能问题（语义等价），不必改。
        String payload = (body == null || body.trim().isEmpty()) ? null : toAsciiJson(body);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("accessKey", accessKey);
        headers.add("nonce", UUID.randomUUID().toString().replace("-", ""));
        headers.add("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        // body 为 null 时 SignUtils 会拼出 "null." + secretKey —— 与网关对 GET 的验签约定一致
        headers.add("sign", SignUtils.getSign(payload, secretKey));
        if (payload != null) {
            headers.add("body", payload);
        }
        // 内核登录鉴权：转发时带上服务账号令牌（网关透传到内核）
        String token = serviceToken();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    target,
                    HttpMethod.valueOf(method),
                    new HttpEntity<>(payload, headers),
                    String.class);
            if (resp.getStatusCode().value() != 401) {
                return resp;
            }
            // 服务令牌过期：重登一次再转发（只重试一轮，避免死循环）
            synchronized (this) {
                svcToken = null;
            }
            String fresh = serviceToken();
            if (fresh != null) {
                headers.set("Authorization", "Bearer " + fresh);
            }
            return restTemplate.exchange(
                    target, HttpMethod.valueOf(method), new HttpEntity<>(payload, headers), String.class);
        } catch (HttpStatusCodeException e) {
            // 透传网关的状态码与响应体，前端不必为「经代理」适配另一套格式
            return ResponseEntity.status(e.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"网关不可达\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * 把 JSON 里的非 ASCII 字符转成 Unicode 转义形式。
     *
     * <p>只处理字符本身，不动 JSON 结构 —— JSON 的结构字符（引号、括号、冒号）都是 ASCII，
     * 所以这个转换不会破坏语法。
     */
    private static String toAsciiJson(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 32);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x80) {
                sb.append(c);
            } else {
                sb.append(String.format("\\" + "u%04x", (int) c));
            }
        }
        return sb.toString();
    }
}
