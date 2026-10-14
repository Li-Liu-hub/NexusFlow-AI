package com.aismp.project.controller;

import com.aismp.aismpcommon.model.entity.InterfaceInfo;
import com.aismp.aismpcommon.model.entity.User;
import com.aismp.aismpcommon.service.InnerInterfaceInfoService;
import com.aismp.aismpcommon.service.InnerUserInterfaceInfoService;
import com.aismp.aismpcommon.service.InnerUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 服务间内部接口（供网关注入鉴权所需数据）。
 *
 * <p><b>为什么有这个类</b>：网关原先通过 <b>Dubbo</b> 调 {@code InnerXxxService}
 * （见 {@code aismp-gateway/CustomGlobalFilter} 里的 {@code @DubboReference}）。
 * 阶段3 把 Dubbo 整体移除，改成 HTTP —— 因为：
 * <ul>
 *   <li>Dubbo 在本项目只用于「网关 → backend」这一条链路，收益撑不起一个 RPC 框架；</li>
 *   <li>全链路统一 HTTP + Nacos 服务发现后，架构更简单，网关也不再需要 Dubbo 的 SB4 兼容版本（当时并不确定有没有）；</li>
 *   <li>Spring Cloud Gateway 本身是 WebFlux，用 HTTP 与它同构，少一层协议转换。</li>
 * </ul>
 *
 * <p><b>调用方</b>：{@code aismp-gateway} 的 {@code CustomGlobalFilter}，
 * 走 {@code lb://aismp-backend}（Nacos 服务发现 + 负载均衡）。
 *
 * <p><b>安全说明</b>：这几个接口只监听在 localhost（网关/backend 都在同机），
 * 且不经过网关对外暴露；返回的 User 含 secretKey，属于内部凭据，不要对外开放。
 */
@Slf4j
@RestController
@RequestMapping("/inner")
public class InnerApiController {

    @Resource
    private InnerUserService innerUserService;

    @Resource
    private InnerInterfaceInfoService innerInterfaceInfoService;

    @Resource
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;

    /** 按 accessKey 查调用方用户（网关鉴权第一步）。 */
    @GetMapping("/user/byAccessKey")
    public User getInvokeUser(@RequestParam("accessKey") String accessKey) {
        return innerUserService.getInvokeUser(accessKey);
    }

    /** 按「路径 + 方法」查接口是否已登记（网关鉴权第二步，未登记直接 403）。 */
    @GetMapping("/interfaceInfo/byPathMethod")
    public InterfaceInfo getInterfaceInfo(@RequestParam("path") String path,
                                          @RequestParam("method") String method) {
        return innerInterfaceInfoService.getInterfaceInfo(path, method);
    }

    /**
     * 调用次数 +1。
     *
     * <p>网关侧正常走 RabbitMQ 异步计数，本接口只是 <b>MQ 发送失败时的同步降级</b>，
     * 所以调用量很低，用 POST 语义。
     */
    @PostMapping("/userInterfaceInfo/invokeCount")
    public boolean invokeCount(@RequestParam("interfaceInfoId") long interfaceInfoId,
                               @RequestParam("userId") long userId) {
        try {
            return innerUserInterfaceInfoService.invokeCount(interfaceInfoId, userId);
        } catch (Exception e) {
            log.error("invokeCount 失败 interfaceInfoId={} userId={}", interfaceInfoId, userId, e);
            return false;
        }
    }
}
