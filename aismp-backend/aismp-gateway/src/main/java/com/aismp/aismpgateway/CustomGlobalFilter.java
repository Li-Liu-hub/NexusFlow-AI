package com.aismp.aismpgateway;

import cn.hutool.json.JSONUtil;
import com.aismp.aismpiclientsdk.utils.SignUtils;
import com.aismp.aismpcommon.model.entity.InterfaceInfo;
import com.aismp.aismpcommon.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局过滤
 *
 * <p><b>阶段3 改造（Dubbo → HTTP）</b>：原先这里用 3 个 {@code @DubboReference}
 * 调用 backend 的 {@code InnerXxxService}。现在改成
 * {@code RestTemplate}（{@code @LoadBalanced}）走 {@code lb://aismp-backend}，
 * 与全链路的「HTTP + Nacos 服务发现」保持一致。
 *
 * <p><b>⚠️ 为什么整个 filter 要挪到 boundedElastic</b>：
 * Spring Cloud Gateway 建立在 WebFlux 之上，filter 运行在
 * {@code reactor-http-nio-*} 事件循环线程里。在那里做阻塞调用（RestTemplate 是阻塞的）
 * 会把事件循环卡死 —— 表现为网关整体失去响应，而不是单个请求变慢。
 * 所以用 {@code Mono.defer(...).subscribeOn(Schedulers.boundedElastic())}
 * 把整段同步鉴权逻辑移到弹性线程池执行。
 */
@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    /**
     * 服务间 HTTP 客户端（见 LoadBalancedClientConfig）。
     * 用 @Resource 按 bean 名注入 —— 项目里可能还有别的 RestTemplate（如 backend 的管理代理），
     * 按名注入避免歧义。
     */
    @Resource(name = "loadBalancedRestTemplate")
    private RestTemplate restTemplate;

    /** backend 在 Nacos 里注册的服务名 */
    private static final String BACKEND_SERVICE = "http://aismp-backend";

    /**
     * backend 内部接口前缀。
     *
     * <p>⚠️ 必须带 {@code /api}：backend 的 {@code server.servlet.context-path=/api}，
     * {@code InnerApiController} 的 {@code @RequestMapping("/inner")} 实际暴露在
     * {@code /api/inner/**}。少了这一段会 404 —— 而 404 被 catch 吞掉后表现为
     * invokeUser == null → 所有请求 403，很容易误判成鉴权问题。
     */
    private static final String BACKEND_INNER = BACKEND_SERVICE + "/api/inner";

    /** 门户令牌 → ak 的 Redis 前缀（登录成功时由本网关写入，见 handlePortalAuth） */
    private static final String PORTAL_TOKEN_PREFIX = "portal:token:";

    /**
     * 门户流量在平台账本里记在哪个 AK 名下。
     *
     * <p>门户是<b>平台自有应用</b>（不是第三方开发者），浏览器也不可能持有 AK/SK，
     * 所以它的调用量统一记到平台默认账号上（与 backend 的 aismp.client.access-key 同一个）。
     * 数字员工自己的 token 用量另有内核账本（/admin/token/usage/*），两者不冲突。
     */
    @Value("${aismp.portal.access-key:aismp-console}")
    private String portalAccessKey;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 允许的来源地址<b>前缀</b>白名单。
     *
     * <p>⚠️ 2026-10-10 容器化后修复（原为 {@code Arrays.asList("127.0.0.1")} 精确匹配）：
     * {@link ServerHttpRequest#getLocalAddress()} 返回的是<b>连接的本端地址</b> ——
     * 原生部署时网关跑在宿主机，本端地址恰好是 {@code 127.0.0.1}，精确匹配能过；
     * <b>容器化后网关监听在容器 IP（如 172.28.0.11），精确匹配必然失配，导致所有请求 403</b>
     * （现象：门户静态页 200，但任何 /api/** 全 403）。
     * 故改为前缀匹配：放行本机（127.）与容器网络（172.28.0.0/16）。
     */
    private static final List<String> IP_WHITE_PREFIXES = Arrays.asList("127.", "172.28.");

    /**
     * 把同步鉴权流程放到弹性线程池执行（见类注释里"为什么"）。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.defer(() -> doFilter(exchange, chain))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 真正的鉴权逻辑（原 filter 方法体），含阻塞 HTTP 调用，运行在 boundedElastic 上。 */
    private Mono<Void> doFilter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 请求日志
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        String method = request.getMethod().toString();
        log.info("请求唯一标识：" + request.getId());
        log.info("请求路径：" + path);
        log.info("请求方法：" + method);
        log.info("请求参数：" + request.getQueryParams());
        String sourceAddress = request.getLocalAddress().getHostString();
        log.info("请求来源地址：" + sourceAddress);
        log.info("请求来源地址：" + request.getRemoteAddress());
        ServerHttpResponse response = exchange.getResponse();
        // 2. 访问控制 - 来源地址前缀白名单（本机 + 容器网络，见 IP_WHITE_PREFIXES 注释）
        if (sourceAddress == null
                || IP_WHITE_PREFIXES.stream().noneMatch(sourceAddress::startsWith)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return response.setComplete();
        }
        // 3. 用户鉴权——三协议通道：
        //    REST（其余路径）→ AK/SK 签名（防篡改防重放，适合能实现签名逻辑的应用开发者）
        //    MCP（/sse,/mcp/**）→ Bearer token（MCP 客户端只会带标准 HTTP 头，算不了自定义签名；
        //      对齐 MCP 规范的 OAuth 2.1 resource server 语义，本实现为极简版：静态 token + Redis 查表）
        //    门户（/api/agent/**,/api/admin/**,/api/orchestrator/**,/api/terminal/**,/api/transferOrder/**）→ Bearer token
        //      （浏览器持有的是内核签发的登录令牌，**不可能持有 AK/SK**——那是发给第三方开发者的长期凭证，
        //       不能下发到浏览器控制台。令牌 → ak 的映射由网关在登录成功时写入 Redis，见 handlePortalAuth）
        //
        //    ⚠️ 业务接口（/api/terminal/**、/api/transferOrder/**）是**双通道**（2026-10-07 修）：
        //      带 Bearer → 门户通道（浏览器）；无 Bearer → 落到 AK/SK 签名通道（编排层等服务方）。
        //      这两条路径最初被整体划入门户通道，导致编排层用 AK/SK 调 /api/terminal 取数被 401
        //      （"缺少 Bearer token"）—— 门户化不能把服务方通道关死，两条都保留；
        //      两条通道共用同一套 interface_info 校验与调用计量。
        HttpHeaders headers = request.getHeaders();
        boolean isMcpPath = path.startsWith("/sse") || path.startsWith("/mcp");
        boolean isPortalAuthPath = path.startsWith("/api/auth");
        boolean isDualChannelBizPath = path.startsWith("/api/terminal")
                || path.startsWith("/api/transferOrder");
        boolean isPortalPath = isPortalAuthPath
                || path.startsWith("/api/agent") || path.startsWith("/api/admin")
                || path.startsWith("/api/orchestrator")
                || isDualChannelBizPath
                // 2026-10-07 [写入授权收口]：编排层的无前缀路径（/orchestrator/**）改为门户专用 ——
                // 此前它在 AK/SK 通道登记放行，任何 AK/SK 持有者（含第三方开发者）都能调编排层，
                // 带伪造 X-User-* 头或 query 自报身份即可冒充任意门户用户（已实测复现）。
                // 编排层的真实调用方只有：门户（Bearer）与本机脚本（直连 8125）—— 无需 AK/SK 通道。
                || path.startsWith("/orchestrator/");

        if (isPortalAuthPath) {
            // 登录/登出是鉴权握手：此刻浏览器还没有令牌，必须匿名放行（路由级限流仍生效）。
            // 登录成功时把 {token → ak} 写进 Redis，后续门户请求才能按 ak 链路解析用户与计量。
            return handlePortalAuth(exchange, chain);
        }

        String authorization = headers.getFirst("Authorization");
        boolean hasBearerToken = authorization != null && authorization.startsWith("Bearer ");
        String accessKey;
        // 门户通道解析出的登录身份（转发前注入 X-User-*）；非门户通道为 null，不注入
        PortalIdentity portalIdentity = null;
        // 是否走 token 类通道（MCP / 门户）—— 只有它们跳过签名三件套；
        // 双通道业务接口若走的 AK/SK 分支，tokenChannel=false，照常验签（见下方第 4 步）。
        boolean tokenChannel;
        if (isMcpPath) {
            if (!hasBearerToken) {
                return handleNoAuth(response);
            }
            tokenChannel = true;
            // token 是 ak 的"免签名皮肤"：Redis 查表映射回 ak，后续用户解析与计量归属全部复用 ak 链路
            String token = authorization.substring("Bearer ".length()).trim();
            accessKey = stringRedisTemplate.opsForValue().get("mcp:token:" + token);
            if (accessKey == null || accessKey.isEmpty()) {
                return handleNoAuth(response);
            }
            // 把 ak 存入 exchange 属性：限流键用它（KeyResolver 里 MCP 请求没有 accessKey 头可读）
            exchange.getAttributes().put("rateLimitAk", accessKey);
        } else if (isPortalPath && (!isDualChannelBizPath || hasBearerToken)) {
            if (!hasBearerToken) {
                // 门户缺令牌返回 401（不是 403）：前端 auth.js 只在 401 时清登录态跳登录页
                return handleUnauthorized(response, "未登录（缺少 Bearer token）");
            }
            String token = authorization.substring("Bearer ".length()).trim();
            // 令牌值 = 登录时网关登记的身份 JSON（ak + userCode + warehouseCode）；
            // 兼容旧格式（纯 ak 字符串）：旧格式只用于计量归属，不注入身份（用户重新登录后自动升级）
            portalIdentity = parsePortalIdentity(
                    stringRedisTemplate.opsForValue().get(PORTAL_TOKEN_PREFIX + token));
            if (portalIdentity == null) {
                // 令牌没在网关登记（直接找内核登录的、或 Redis 被清过）→ 让前端重新走一次网关登录
                return handleUnauthorized(response, "登录态未在网关登记或已过期，请重新登录");
            }
            accessKey = portalIdentity.ak();
            exchange.getAttributes().put("rateLimitAk", accessKey);
            tokenChannel = true;
        } else {
            // 无 Bearer 的业务接口（编排层 AK/SK）与其余 REST 路径共用签名通道
            accessKey = headers.getFirst("accessKey");
            tokenChannel = false;
        }
        String nonce = headers.getFirst("nonce");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");
        String body = headers.getFirst("body");
        // todo 实际情况应该是去数据库中查是否已分配给用户
        User invokeUser = null;
        try {
            // Redis 缓存优先查询用户信息
            String userKey = "user:ak:" + accessKey;
            String cachedUser = stringRedisTemplate.opsForValue().get(userKey);
            if (cachedUser != null) {
                invokeUser = JSONUtil.toBean(cachedUser, User.class);
            } else {
                invokeUser = restTemplate.getForObject(
                        BACKEND_INNER + "/user/byAccessKey?accessKey={ak}",
                        User.class, accessKey);
                if (invokeUser != null) {
                    stringRedisTemplate.opsForValue().set(userKey, JSONUtil.toJsonStr(invokeUser), 30, TimeUnit.MINUTES);
                }
            }
        } catch (Exception e) {
            log.error("getInvokeUser error", e);
        }
        if (invokeUser == null) {
            return handleNoAuth(response);
        }
        if (tokenChannel) {
            // MCP / 门户通道跳过 nonce/时间戳/签名三件套：
            //   ① token 本身就是可复用凭证（非挑战应答），没有"重算"环节自然没有重放问题；
            //      吊销 = 删 Redis key，TTL 兜底；
            //   ② 门户是浏览器，算不了自定义签名（这正是"AK/SK 不下发浏览器"的设计）。
            // 网关这层负责"令牌是不是本网关登记的"；登录身份在转发前经
            // injectPortalIdentity 注入下游（2026-10-07：[数据权限信任根] 修复）。
            // interface_info 校验与计量不跳过——门户流量与 REST 走同一套账本（见下方共用逻辑）。
            // 注意：判定依据是"实际走了哪条通道"（tokenChannel），不是路径前缀 ——
            // 双通道业务接口走 AK/SK 分支时必须照常验签，否则等于给这些路径开了签名后门。
        } else {
            // nonce 格式校验：只限长度防滥用，不卡数值范围（原 >100000 校验把 nonce 钉死在 9 万空间，高并发下同用户 5 分钟窗口撞车概率可达 5%+，已废弃）
            if (nonce == null || nonce.isEmpty() || nonce.length() > 64) {
                return handleNoAuth(response);
            }
            // nonce 防重放：SETNX 写入，5分钟过期（与时间戳校验窗口一致）。
            // key 带 accessKey 前缀：防重放粒度是"同一用户重复同一随机数"，全局命名空间会把不同用户的巧合撞车误判为重放
            Boolean isNewNonce = stringRedisTemplate.opsForValue()
                    .setIfAbsent("nonce:" + accessKey + ":" + nonce, "1", 5, TimeUnit.MINUTES);
            if (isNewNonce == null || !isNewNonce) {
                log.info("nonce 重复，疑似重放攻击：{}", nonce);
                return handleNoAuth(response);
            }
            // 时间和当前时间不能超过 5 分钟
            Long currentTime = System.currentTimeMillis() / 1000;
            final Long FIVE_MINUTES = 60 * 5L;
            if ((currentTime - Long.parseLong(timestamp)) >= FIVE_MINUTES) {
                return handleNoAuth(response);
            }
            // 实际情况中是从数据库中查出 secretKey
            String secretKey = invokeUser.getSecretKey();
            String serverSign = SignUtils.getSign(body, secretKey);
            if (sign == null || !sign.equals(serverSign)) {
                return handleNoAuth(response);
            }
        }
        // 4. 请求的模拟接口是否存在，以及请求方法是否匹配
        InterfaceInfo interfaceInfo = null;
        try {
            // Redis 缓存优先查询接口信息
            String interfaceKey = "interface:" + path + ":" + method;
            String cachedInterface = stringRedisTemplate.opsForValue().get(interfaceKey);
            if (cachedInterface != null) {
                interfaceInfo = JSONUtil.toBean(cachedInterface, InterfaceInfo.class);
            } else {
                interfaceInfo = restTemplate.getForObject(
                        BACKEND_INNER + "/interfaceInfo/byPathMethod?path={p}&method={m}",
                        InterfaceInfo.class, path, method);
                if (interfaceInfo != null) {
                    stringRedisTemplate.opsForValue().set(interfaceKey, JSONUtil.toJsonStr(interfaceInfo), 30, TimeUnit.MINUTES);
                }
            }
        } catch (Exception e) {
            log.error("getInterfaceInfo error", e);
        }
        if (interfaceInfo == null) {
            return handleNoAuth(response);
        }
        // todo 是否还有调用次数
        // 5. 请求转发，调用模拟接口 + 响应日志
        //        Mono<Void> filter = chain.filter(exchange);
        //        return filter;
        //
        // 转发前：重写身份头（2026-10-07 [数据权限信任根] 修复 + [写入授权收口] 扩展）。
        // 规则（信任边界以"路径契约 + 通道"共同划分）：
        //   ① 剥离：除 /oa/** 外一律剥离客户端自带的 X-User-* / X-Area-Code。
        //      —— /oa/** 是 interface_info 登记在案的"OA 代传"契约（requestParams 明示这两个头是入参），
        //         其余路径的客户端身份头一律不可信：此前只在门户通道剥离，AK/SK/MCP 通道可带伪造头，
        //         实测可冒充任意门户用户（探针：AK/SK + 伪造 X-User-Id → 编排层按伪造身份落库）。
        //   ② 注入：门户通道（浏览器登录态）写入网关核验过的权威身份（X-User-Id/Warehouse/Role）。
        //      AK/SK（服务方）通道只剥不注 —— 服务方身份由业务参数表达，且不再能伪造"门户身份"。
        boolean oaContractPath = path.startsWith("/oa/");
        ServerWebExchange forwardExchange = (oaContractPath && portalIdentity == null)
                ? exchange
                : rewriteIdentityHeaders(exchange, portalIdentity, !oaContractPath);
        return handleResponse(forwardExchange, chain, interfaceInfo.getId(), invokeUser.getId());

    }

    /**
     * 处理响应
     *
     * @param exchange
     * @param chain
     * @return
     */
    public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain, long interfaceInfoId, long userId) {
        try {
            ServerHttpResponse originalResponse = exchange.getResponse();
            // 缓存数据的工厂
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            // 拿到响应码
            // Spring 6+ 起 getStatusCode() 返回 HttpStatusCode（不再是 HttpStatus），
            // 判断相等要用 isSameCodeAs（HttpStatusCode 不保证是 HttpStatus 枚举实例）
            HttpStatusCode statusCode = originalResponse.getStatusCode();
            if (statusCode.isSameCodeAs(HttpStatus.OK)) {
                // 装饰，增强能力
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
                    // 保证一次请求只计费一次（同步响应有一个 DataBuffer，SSE 有多个）
                    final AtomicBoolean counted = new AtomicBoolean(false);

                    private void tryCount() {
                        if (counted.compareAndSet(false, true)) {
                            try {
                                // 通过 MQ 异步计数，不阻塞响应链路
                                Map<String, Object> msg = new HashMap<>();
                                msg.put("interfaceInfoId", interfaceInfoId);
                                msg.put("userId", userId);
                                rabbitTemplate.convertAndSend("invoke.count.queue", msg);
                            } catch (Exception e) {
                                log.error("异步计数发送 MQ 失败，降级为同步 HTTP 调用", e);
                                // 降级：MQ 发送失败时回退到同步 HTTP 调用 backend
                                try {
                                    restTemplate.postForObject(
                                            BACKEND_INNER + "/userInterfaceInfo/invokeCount"
                                                    + "?interfaceInfoId={iid}&userId={uid}",
                                            null, Boolean.class, interfaceInfoId, userId);
                                } catch (Exception ex) {
                                    log.error("降级同步计数也失败", ex);
                                }
                            }
                        }
                    }

                    // 普通响应走这里（一次性 body，如 JSON）
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        log.info("body instanceof Flux: {}", (body instanceof Flux));
                        if (body instanceof Flux) {
                            Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                            // 往返回值里写数据
                            // 拼接字符串
                            return super.writeWith(
                                    fluxBody.map(dataBuffer -> {
                                        // 7. 调用成功，接口调用次数 + 1 invokeCount
                                        tryCount();
                                        byte[] content = new byte[dataBuffer.readableByteCount()];
                                        dataBuffer.read(content);
                                        DataBufferUtils.release(dataBuffer);//释放掉内存
                                        // 构建日志
                                        StringBuilder sb2 = new StringBuilder(200);
                                        List<Object> rspArgs = new ArrayList<>();
                                        rspArgs.add(originalResponse.getStatusCode());
                                        String data = new String(content, StandardCharsets.UTF_8); //data
                                        sb2.append(data);
                                        // 打印日志
                                        log.info("响应结果：" + data);
                                        return bufferFactory.wrap(content);
                                    }));
                        } else {
                            // 8. 调用失败，返回一个规范的错误码
                            log.error("<--- {} 响应code异常", getStatusCode());
                        }
                        return super.writeWith(body);
                    }

                    // SSE 流式响应走这里（text/event-stream，多个 chunk 逐块推送）
                    @Override
                    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                        // SSE 首个 chunk 到达时计费一次，后续 chunk 不再重复计费
                        tryCount();
                        return super.writeAndFlushWith(body);
                    }
                };
                // 设置 response 对象为装饰过的
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
            return chain.filter(exchange); // 降级处理返回数据
        } catch (Exception e) {
            log.error("网关处理响应异常" + e);
            return chain.filter(exchange);
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }

    public Mono<Void> handleNoAuth(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return response.setComplete();
    }

    /**
     * 401 + JSON 应答（门户通道用）。
     *
     * <p>为什么不用 {@link #handleNoAuth}（403 空体）：门户前端 auth.js 只在 <b>401</b> 时
     * 清登录态并跳回登录页；403 会被当成"没权限"而一直卡在页面上。
     * 应答体形状与内核一致（{@code {"error":...,"status":401}}），前端不必为"经网关"多写一套解析。
     */
    public Mono<Void> handleUnauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory()
                .wrap(("{\"error\":\"" + message + "\",\"status\":401}").getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 门户登录/登出通道：匿名放行 + 在登录应答里登记 token → ak。
     *
     * <p><b>为什么要在网关登记</b>：门户（浏览器）用的是内核签发的 Bearer 令牌，网关无法自行验证
     * （令牌在终端库 a_user_token 里）。做法与 MCP 通道同构 —— 把令牌当作"ak 的免签名皮肤"：
     * 登录成功时把 {@code portal:token:<token> → ak} 写进 Redis，后续请求按这条映射还原成
     * ak 链路，用户解析 / interface_info 校验 / 计量全部复用同一套逻辑。
     *
     * <p><b>身份把关在哪</b>：真正的身份校验仍在内核（Bearer + 角色），网关这层只认
     * "这个令牌是不是本网关登记过的"。代价：Redis 被清空或换网关部署后，已登录用户需重新登录
     * （令牌寿命 24h，与内核一致）。
     */
    private Mono<Void> handlePortalAuth(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean isLogout = exchange.getRequest().getPath().value().startsWith("/api/auth/logout");
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        ServerHttpResponse original = exchange.getResponse();
        DataBufferFactory factory = original.bufferFactory();
        ServerHttpResponseDecorator decorated = new ServerHttpResponseDecorator(original) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (body instanceof Flux) {
                    // 登录/登出应答都很小（几百字节），整段读出来再回写是安全的
                    return super.writeWith(Flux.from(body).buffer().map(buffers -> {
                        int size = buffers.stream().mapToInt(DataBuffer::readableByteCount).sum();
                        byte[] content = new byte[size];
                        int offset = 0;
                        for (DataBuffer b : buffers) {
                            int len = b.readableByteCount();
                            b.read(content, offset, len);
                            offset += len;
                            DataBufferUtils.release(b);
                        }
                        updatePortalTokenMapping(content, isLogout, authorization);
                        return factory.wrap(content);
                    }));
                }
                return super.writeWith(body);
            }
        };
        return chain.filter(exchange.mutate().response(decorated).build());
    }

    /** 登录成功 → 写映射（令牌在 Redis 里的寿命与内核一致，24h）；登出成功 → 删映射 */
    private void updatePortalTokenMapping(byte[] content, boolean isLogout, String authorization) {
        try {
            String text = new String(content, StandardCharsets.UTF_8);
            if (isLogout) {
                if (authorization != null && authorization.startsWith("Bearer ")) {
                    stringRedisTemplate.delete(PORTAL_TOKEN_PREFIX + authorization.substring("Bearer ".length()).trim());
                }
                return;
            }
            // 登录失败时应答里没有 token（只有 error 字段）→ 取不到就什么都不做
            cn.hutool.json.JSONObject obj = JSONUtil.parseObj(text);
            String token = obj.getStr("token");
            if (token != null && !token.isEmpty()) {
                // 2026-10-07 [数据权限信任根]：登记值从"纯 ak"升级为身份 JSON ——
                // userCode / warehouseCode 来自 backend 登录应答（AuthController.login），
                // 后续门户请求据此注入 X-User-*，下游不再信任客户端自报的参数。
                LinkedHashMap<String, Object> identity = new LinkedHashMap<>();
                identity.put("ak", portalAccessKey);
                identity.put("userCode", obj.getStr("userCode"));
                identity.put("warehouseCode", obj.getStr("warehouseCode"));
                identity.put("role", obj.getStr("role"));
                stringRedisTemplate.opsForValue()
                        .set(PORTAL_TOKEN_PREFIX + token, JSONUtil.toJsonStr(identity), 24, TimeUnit.HOURS);
                log.info("门户登录登记：token → ak={}，身份 userCode={} warehouseCode={}（TTL 24h，与内核令牌同寿命）",
                        portalAccessKey, identity.get("userCode"), identity.get("warehouseCode"));
            }
        } catch (Exception e) {
            // 登记失败不影响登录本身；后续请求会因"未登记"被 401，表现为需要重新登录
            log.warn("门户登录登记失败（不影响登录应答）: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 门户身份注入（2026-10-07 [数据权限信任根] 修复）
    // ------------------------------------------------------------------

    /**
     * 门户登录身份（网关是唯一权威来源）。
     *
     * <p>userCode / warehouseCode / role 为 null 表示旧格式令牌（升级前登记的）：
     * 只做防伪剥离，不注入身份 —— 等价于旧行为，用户重新登录后自动升级。
     */
    private record PortalIdentity(String ak, String userCode, String warehouseCode, String role) {
    }

    /** 解析 portal:token:* 的登记值：新格式为身份 JSON；旧格式为纯 ak 字符串（兼容）。 */
    private PortalIdentity parsePortalIdentity(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (!raw.startsWith("{")) {
            return new PortalIdentity(raw, null, null, null);
        }
        try {
            cn.hutool.json.JSONObject obj = JSONUtil.parseObj(raw);
            return new PortalIdentity(obj.getStr("ak", portalAccessKey),
                    obj.getStr("userCode"), obj.getStr("warehouseCode"), obj.getStr("role"));
        } catch (Exception e) {
            log.warn("门户令牌身份解析失败（按旧格式处理）: {}", e.getMessage());
            return new PortalIdentity(raw, null, null, null);
        }
    }

    /**
     * 转发前重写身份头：按需剥离客户端伪造头，再（门户通道）写入网关核验身份。
     *
     * <p>两步走，顺序不能反：
     * <ol>
     *   <li><b>剥离</b>（{@code stripClientHeaders=true} 时）：删除客户端自带的
     *       {@code X-User-*} / {@code X-Area-Code} —— 不剥离的话"注入"等于给伪造值盖章；
     *       AK/SK、MCP 通道同样剥离（服务方身份走业务参数，不走身份头）；</li>
     *   <li><b>写入</b>（{@code identity != null} 时）：门户通道注入权威
     *       {@code X-User-Id} / {@code X-User-Warehouse} / {@code X-User-Role}，下游以它为准。</li>
     * </ol>
     * 例外：{@code /oa/**} 是 interface_info 登记的"OA 代传"契约（X-User-Id/X-Area-Code 是入参），
     * 不剥离也不注入 —— 该通道的身份语义由 OA 集成方负责，与本平台的"门户身份"体系隔离。
     */
    private ServerWebExchange rewriteIdentityHeaders(ServerWebExchange exchange, PortalIdentity identity,
                                                     boolean stripClientHeaders) {
        return exchange.mutate().request(builder -> builder.headers(headers -> {
            if (stripClientHeaders) {
                List<String> spoofed = new ArrayList<>();
                headers.forEach((name, values) -> {
                    String lower = name.toLowerCase();
                    if (lower.startsWith("x-user-") || lower.equals("x-area-code")) {
                        spoofed.add(name);
                    }
                });
                spoofed.forEach(headers::remove);
            }
            if (identity == null) {
                return;
            }
            String userCode = identity.userCode();
            if (userCode != null && !userCode.isEmpty()) {
                headers.set("X-User-Id", userCode);
                String warehouseCode = identity.warehouseCode();
                if (warehouseCode != null && !warehouseCode.isEmpty()) {
                    headers.set("X-User-Warehouse", warehouseCode);
                }
                String role = identity.role();
                if (role != null && !role.isEmpty()) {
                    headers.set("X-User-Role", role);
                }
            }
        })).build();
    }

    public Mono<Void> handleInvokeError(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        return response.setComplete();
    }
}