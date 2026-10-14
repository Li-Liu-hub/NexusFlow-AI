package com.aismp.aismpagent.terminal.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.NoticeUpsertRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderActionRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderCreateRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderOutboundRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderQueryRequest;
import com.aismp.aismpagent.terminal.model.entity.TransferOrder;
import com.aismp.aismpagent.terminal.model.vo.TransferOrderVO;

/**
 * 调拨单服务 —— 补货建议的落地闭环
 * <p>
 * 链路（2026-10-08 重构）：A 仓账管申请 → B 仓管导入 SN 码确认出库（在途）
 * → A 仓管确认入库（或驳回，货退回 B 仓）。无账管确认环节。
 * <p>
 * 闭环边界（开发文档 M2 关键机制 3）：AI 只负责到"提交调拨申请"为止，
 * 出库 / 入库的状态流转与库存扣减由本服务承担，AI 不介入库存事务。
 *
 * @author agent-platform
 */
public interface TransferOrderService extends IService<TransferOrder> {

    /**
     * 创建调拨申请（校验四条硬规则），落库状态 APPLIED（待调出方仓管确认出库）
     */
    TransferOrderVO createOrder(TransferOrderCreateRequest request);

    /**
     * 出库：按单导入串码（模板上传解析后传入），做「型号 / 数量 / 状态 / 在仓」四重比对，
     * 通过才把串码置为「在途」（仍挂调出仓），等待调入方仓管入库确认
     */
    TransferOrderVO outbound(TransferOrderOutboundRequest request);

    /**
     * 入库确认：在途串码换仓到调入方「可用」，单据完成
     */
    TransferOrderVO inbound(TransferOrderActionRequest request);

    /**
     * 驳回（按状态分支）：
     * <ul>
     *   <li>APPLIED（出库前，B 仓管拒绝）：货未动，直接置驳回；</li>
     *   <li>OUTBOUND（在途，A 仓管驳回入库）：在途串码退回原调出仓「可用」。</li>
     * </ul>
     */
    TransferOrderVO reject(TransferOrderActionRequest request);

    /**
     * 单详情（含明细）
     */
    TransferOrderVO getOrderDetail(String orderNo);

    /**
     * 分页查询
     */
    Page<TransferOrder> pageOrders(TransferOrderQueryRequest query);

    /**
     * 站内通知取件：按「仓位:角色」「仓位」「本人账号」三类收件人合并查询。
     * <p>工单每一步（申请/出库/入库/驳回）都会 pushNotice。
     */
    java.util.List<com.aismp.aismpagent.terminal.model.entity.Notice> listNotices(
            String warehouseCode, String role, String userCode, int limit);

    /**
     * 写 / 刷新一条站内通知（幂等）。
     * <p>同 receiver + noticeType 存在未读通知 → 只刷新标题/内容/时间，不再新增 ——
     * 避免周期性巡检把账管的通知面板刷满。供编排层告警闭环（scan → notify）调用。
     *
     * @return {"action": "inserted"|"updated", "id": 通知主键}
     */
    java.util.Map<String, Object> upsertNotice(NoticeUpsertRequest request);

    /**
     * 分页查询（带可见范围，2026-10-08）：visibleScope 非空时只返回
     * 调出仓或调入仓任一端命中可见范围的工单（门户通道专用；null = 不限制）。
     * <p>query 自报的仓位条件仍生效 —— 只能在可见范围上收紧，不能放大。
     */
    Page<TransferOrder> pageOrders(TransferOrderQueryRequest query, java.util.Set<String> visibleScope);

    /**
     * 【实验功能·演示用】按工单明细生成出库 SN 清单（2026-10-08）。
     * <p>每物料从调出仓可用库存按库龄倒序抓 quantity 个（不动库存，只读）；
     * 前端导出 CSV 后原样导入，必过 outbound 的四重校验。
     * 物料可用量不足时 serialNos 数量 &lt; quantity，由前端提示缺口。
     *
     * @return [{materialCode, materialName, model, quantity, serialNos:[...]}]
     */
    java.util.List<java.util.Map<String, Object>> buildSnFile(String orderNo);
}
