package com.aismp.aismpagent.terminal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.aismp.aismpagent.terminal.common.BaseResponse;
import com.aismp.aismpagent.terminal.common.ErrorCode;
import com.aismp.aismpagent.terminal.common.ResultUtils;
import com.aismp.aismpagent.terminal.exception.BusinessException;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.NoticeUpsertRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderActionRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderCreateRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderOutboundRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderQueryRequest;
import com.aismp.aismpagent.terminal.model.entity.TransferOrder;
import com.aismp.aismpagent.terminal.model.vo.TransferOrderVO;
import com.aismp.aismpagent.terminal.service.TransferOrderService;
import com.aismp.aismpagent.terminal.service.WarehouseScopeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.Set;

/**
 * 调拨单接口 —— 补货建议的落地闭环
 * <p>
 * 状态机（2026-10-08 链路重构，与用户对齐）：
 * <pre>
 *   APPLIED 待确认出库 --B仓管导入SN确认出库--> OUTBOUND 待确认入库（在途） --A仓管确认入库--> DONE
 *        │                                              │
 *        └─B仓管拒绝(货未动)→ REJECTED                   └─A仓管驳回(货退回B仓)→ REJECTED
 * </pre>
 * A 仓账管申请（create）→ B 仓管出库（outbound，导入 SN 模板）→ A 仓管入库确认（inbound）
 * 或驳回入库（reject）；**无账管确认环节**。AI 只调 /create 提交申请，
 * 出库/入库由仓管在界面上操作 —— AI 不介入库存事务。
 * <p>
 * 实际 URL 前缀是 /api，如 POST /api/transferOrder/create
 *
 * @author agent-platform
 */
@RestController
@RequestMapping("/transferOrder")
@Slf4j
public class TransferOrderController {

    @Resource
    private TransferOrderService transferOrderService;

    @Resource
    private WarehouseScopeResolver scopeResolver;

    /**
     * 提交调拨申请（A 仓账管发起；手工 / AI 双入口）
     *
     * <p>门户通道（有注入身份）：必须是**调入仓（本仓）的账管**，creator 以网关注入身份为准，
     * 忽略请求体自报 —— 防止"代人申请"；服务方通道（编排层 AI 提交申请，无注入头）：保持原语义。
     */
    @PostMapping("/create")
    public BaseResponse<TransferOrderVO> create(
            @RequestBody TransferOrderCreateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Warehouse", required = false) String warehouseCode,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        InjectedIdentity identity = InjectedIdentity.of(userId, warehouseCode, role);
        if (identity != null) {
            if (!identity.warehouseCode().equals(request.getToWarehouse())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                        "只能由调入仓（本仓）发起调拨申请");
            }
            if (!"ACCOUNT_MANAGER".equals(identity.role())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                        "只有账管可以发起调拨申请（当前角色：" + identity.role() + "）");
            }
            request.setCreator(identity.userCode());
        }
        return ResultUtils.success(transferOrderService.createOrder(request));
    }

    /**
     * 出库：B 仓管导入 SN 码（模板上传解析后传入），做型号/数量/状态/在仓四重比对
     *
     * <p>门户通道：注入身份必须＝调出方仓（B 仓）的仓管，否则 403；operator 以注入身份为准。
     */
    @PostMapping("/outbound")
    public BaseResponse<TransferOrderVO> outbound(
            @RequestBody TransferOrderOutboundRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Warehouse", required = false) String warehouseCode,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        InjectedIdentity identity = InjectedIdentity.of(userId, warehouseCode, role);
        if (identity != null) {
            requireOrderSide(identity, request.getOrderNo(), "from", Set.of("WAREHOUSE_KEEPER"));
        }
        return ResultUtils.success(transferOrderService.outbound(request));
    }

    /**
     * 入库确认（A 仓管同意入库）
     *
     * <p>门户通道：注入身份必须＝调入方仓（A 仓）的仓管，否则 403。
     */
    @PostMapping("/inbound")
    public BaseResponse<TransferOrderVO> inbound(
            @RequestBody TransferOrderActionRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Warehouse", required = false) String warehouseCode,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        InjectedIdentity identity = InjectedIdentity.of(userId, warehouseCode, role);
        if (identity != null) {
            requireOrderSide(identity, request.getOrderNo(), "to", Set.of("WAREHOUSE_KEEPER"));
            request.setOperator(identity.userCode());
        }
        return ResultUtils.success(transferOrderService.inbound(request));
    }

    // ------------------------------------------------------------------
    // 驳回（2026-10-08 链路重构：旧的 send/accountConfirm/keeperConfirm 已下线）
    // ------------------------------------------------------------------

    /**
     * 驳回（需填理由）—— 按工单当前状态分支校验操作方：
     * <ul>
     *   <li>APPLIED（出库前）：必须＝调出方仓（B 仓）的仓管；货未动，直接置驳回；</li>
     *   <li>OUTBOUND（在途）：必须＝调入方仓（A 仓）的仓管；在途串码退回 B 仓「可用」。</li>
     * </ul>
     */
    @PostMapping("/reject")
    public BaseResponse<TransferOrderVO> reject(
            @RequestBody TransferOrderActionRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Warehouse", required = false) String warehouseCode,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        InjectedIdentity identity = InjectedIdentity.of(userId, warehouseCode, role);
        if (identity != null) {
            TransferOrderVO order = transferOrderService.getOrderDetail(request.getOrderNo());
            if ("APPLIED".equals(order.getStatus())) {
                requireOrderSide(identity, request.getOrderNo(), "from", Set.of("WAREHOUSE_KEEPER"));
            } else if ("OUTBOUND".equals(order.getStatus())) {
                requireOrderSide(identity, request.getOrderNo(), "to", Set.of("WAREHOUSE_KEEPER"));
            } else {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "当前状态（" + order.getStatus() + "）不支持驳回");
            }
            request.setOperator(identity.userCode());
        }
        return ResultUtils.success(transferOrderService.reject(request));
    }

    /**
     * 站内通知：写 / 刷新一条（幂等，原子能力，2026-10-07）。
     *
     * <p>供编排层告警闭环（scan → 判定 → notify）投递通知草稿：
     * 同 receiver + noticeType 存在未读通知时只刷新内容，不再堆一条。
     * 工单状态机内部的通知（ORDER_SENT/ACCOUNT_CONFIRMED/REJECTED/DONE）不走本接口。
     *
     * @return {"action": "inserted"|"updated", "id": 通知主键}
     */
    @PostMapping("/notice")
    public BaseResponse<java.util.Map<String, Object>> upsertNotice(
            @RequestBody NoticeUpsertRequest request) {
        return ResultUtils.success(transferOrderService.upsertNotice(request));
    }

    /**
     * 站内通知：按「仓位 + 角色 + 本人账号」取件（工单每一步都会推通知）。
     *
     * <p>门户通道：三个过滤条件一律以网关注入身份为准（忽略 query 自报）——
     * 修复前实测可用自己的令牌读到别人的通知（横读）。
     */
    @GetMapping("/notices")
    public BaseResponse<java.util.List<com.aismp.aismpagent.terminal.model.entity.Notice>> notices(
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String userCode,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-User-Warehouse", required = false) String headerWarehouse,
            @RequestHeader(value = "X-User-Role", required = false) String headerRole) {
        InjectedIdentity identity = InjectedIdentity.of(headerUserId, headerWarehouse, headerRole);
        if (identity != null) {
            warehouseCode = identity.warehouseCode();
            role = identity.role();
            userCode = identity.userCode();
        }
        return ResultUtils.success(transferOrderService.listNotices(warehouseCode, role, userCode, limit));
    }

    /**
     * 单详情（含明细与串码）。
     *
     * <p>门户通道：只有与该工单相关的人（建单人 / 调出仓 / 调入仓）可以查看，否则 403。
     */
    @GetMapping("/get")
    public BaseResponse<TransferOrderVO> get(
            @RequestParam String orderNo,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Warehouse", required = false) String warehouseCode,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        TransferOrderVO order = transferOrderService.getOrderDetail(orderNo);
        InjectedIdentity identity = InjectedIdentity.of(userId, warehouseCode, role);
        if (identity != null) {
            boolean involved = identity.userCode().equals(order.getCreator())
                    || identity.warehouseCode().equals(order.getFromWarehouse())
                    || identity.warehouseCode().equals(order.getToWarehouse());
            if (!involved) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "当前登录身份无权查看该工单");
            }
        }
        return ResultUtils.success(order);
    }

    /**
     * 分页列表
     *
     * <p>门户通道：只返回与可见范围（本仓 + 下级仓）相关的工单 —— 调出仓或调入仓
     * 任一端命中即保留，query 自报的仓位条件只能在其上**收紧**、不能放大
     * （修复前任意登录用户改 query 就能翻到别人仓库的工单）。
     * 服务方通道（编排层/本机脚本，无注入头）保持原语义。
     */
    @GetMapping("/list/page")
    public BaseResponse<Page<TransferOrder>> listPage(TransferOrderQueryRequest query,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Warehouse", required = false) String warehouseCode,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        InjectedIdentity identity = InjectedIdentity.of(userId, warehouseCode, role);
        Set<String> scope = identity == null ? null : scopeResolver.visibleFrom(identity.warehouseCode());
        return ResultUtils.success(transferOrderService.pageOrders(query, scope));
    }

    // ------------------------------------------------------------------
    // 写入授权守卫（2026-10-07 [写入授权收口]，新方法置底）
    //   门户通道（有 X-User-* 注入头）→ 操作者/取件范围一律以网关注入身份为准；
    //   服务方通道（编排层 AI 建单等，无注入头）→ 保持原语义（身份由业务参数表达）。
    // ------------------------------------------------------------------

    /** 网关注入的门户身份（非门户通道为 null；头由 CustomGlobalFilter#rewriteIdentityHeaders 注入） */
    private record InjectedIdentity(String userCode, String warehouseCode, String role) {
        static InjectedIdentity of(String userCode, String warehouseCode, String role) {
            if (!StringUtils.hasText(userCode)) {
                return null;
            }
            return new InjectedIdentity(userCode,
                    warehouseCode == null ? "" : warehouseCode,
                    role == null ? "" : role);
        }
    }

    /**
     * 校验注入身份属于工单的指定侧（from=调出仓 / to=调入仓），并可限定角色。
     *
     * @param allowedRoles null = 不限角色
     */
    private void requireOrderSide(InjectedIdentity identity, String orderNo,
                                  String side, Set<String> allowedRoles) {
        TransferOrderVO order = transferOrderService.getOrderDetail(orderNo);
        String expected = "from".equals(side) ? order.getFromWarehouse() : order.getToWarehouse();
        boolean warehouseOk = expected != null && expected.equals(identity.warehouseCode());
        boolean roleOk = allowedRoles == null || allowedRoles.contains(identity.role());
        if (!warehouseOk || !roleOk) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    String.format("当前登录身份（%s / %s）无权操作该工单",
                            identity.warehouseCode(), identity.role()));
        }
    }

    // ------------------------------------------------------------------
    // 实验功能（2026-10-08，演示用，新方法置底）
    // ------------------------------------------------------------------

    /**
     * 【实验功能】一键生成出库 SN 清单数据（前端据此下载 CSV，导入后原样出库）。
     *
     * <p>为演示「导入 SN 并确认出库」环节服务：每物料从调出仓可用库存抓 quantity 个，
     * 只读、不动库存；生成的清单必过 outbound 的四重校验。
     * 授权与 outbound 同侧 —— 门户通道必须是调出方仓（B 仓）仓管。
     */
    @GetMapping("/snFile")
    public BaseResponse<java.util.List<java.util.Map<String, Object>>> snFile(
            @RequestParam String orderNo,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Warehouse", required = false) String warehouseCode,
            @RequestHeader(value = "X-User-Role", required = false) String role) {
        InjectedIdentity identity = InjectedIdentity.of(userId, warehouseCode, role);
        if (identity != null) {
            requireOrderSide(identity, orderNo, "from", Set.of("WAREHOUSE_KEEPER"));
        }
        return ResultUtils.success(transferOrderService.buildSnFile(orderNo));
    }
}
