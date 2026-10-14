package com.aismp.project.controller;

import com.aismp.project.token.TokenUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内部接口：token 配额闸门与用量落账（仅内核调用，同机直连 7529，不经网关）。
 *
 * <pre>
 *   GET  /api/inner/token/check?userId=  → {denied: "" 或拒绝原因}
 *   POST /api/inner/token/charge         → {charged: true, totalTokens: n}
 * </pre>
 *
 * <p>2026-10-06 批 A：配额账本迁到 backend 后，内核的对话入口/出口通过这两个接口
 * 完成「查配额 / 落用量」，内核不再直连账本表。
 */
@RestController
@RequestMapping("/inner/token")
public class InnerTokenController {

    private final TokenUsageService service;

    public InnerTokenController(TokenUsageService service) {
        this.service = service;
    }

    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam("userId") String userId) {
        String denied = service.checkQuota(userId);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("denied", denied == null ? "" : denied);
        return result;
    }

    public record ChargeRequest(String userId, String agentCode, String model,
                                Integer requests, Long promptTokens, Long completionTokens,
                                Long totalTokens, Integer toolCalls) {
    }

    @PostMapping("/charge")
    public Map<String, Object> charge(@RequestBody ChargeRequest req) {
        // 缺省值对齐内核的落账口径：requests>=1、用量>=0、toolCalls>=0
        return service.charge(
                req.userId(),
                req.agentCode(),
                req.model(),
                req.requests() == null ? 1 : Math.max(req.requests(), 1),
                req.promptTokens() == null ? 0L : req.promptTokens(),
                req.completionTokens() == null ? 0L : req.completionTokens(),
                req.totalTokens() == null ? 0L : req.totalTokens(),
                req.toolCalls() == null ? 0 : req.toolCalls());
    }
}