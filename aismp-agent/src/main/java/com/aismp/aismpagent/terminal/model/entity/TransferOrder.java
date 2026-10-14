package com.aismp.aismpagent.terminal.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 调拨单
 * <p>
 * 承载四种操作（与开发文档 1.4 的"调拨四操作"一一对应）：
 * TRANSFER 调拨（上级发起，调下属两个平级库）、ISSUE 下发（上级→下级，不可跨级）、
 * RECALL 收回（上级收回下级）、RETURN 回退（下级退上级，四级账管仅支持这种）。
 *
 * <p><b>工单闭环状态机（2026-10-08 链路重构，与用户对齐）</b>：
 * <pre>
 *   APPLIED   待确认出库（A 仓账管已提交申请）
 *     │ B 仓管导入 SN 码并确认出库（串码置「在途」，仍挂 B 仓）
 *     ▼
 *   OUTBOUND  待确认入库（已出库，在途）
 *     │ A 仓管确认入库（在途 → 可用 @A 仓）
 *     ▼
 *   DONE      已完成
 * </pre>
 * 驳回：B 仓管在 APPLIED 阶段拒绝（货未动），或 A 仓管在 OUTBOUND 阶段驳回入库
 * （在途串码退回 B 仓「可用」）→ 均置 REJECTED 并记录理由。
 * <p>
 * 历史状态 PENDING/SENT/ACCOUNT_CONFIRMED 为 2026-10-08 前旧账管/仓管闭环遗留，
 * 仅用于老单据的标签展示，新链路不再产生。
 *
 * @author agent-platform
 */
@TableName(value = "transfer_order")
@Data
public class TransferOrder implements Serializable {

    /**
     * 调拨单号（业务主键）
     */
    @TableId(value = "orderNo", type = IdType.INPUT)
    private String orderNo;

    /**
     * 操作类型：TRANSFER / ISSUE / RECALL / RETURN
     */
    private String operationType;

    /**
     * 调出仓位编码
     */
    private String fromWarehouse;

    /**
     * 调入仓位编码
     */
    private String toWarehouse;

    /**
     * 状态：PENDING / SENT / ACCOUNT_CONFIRMED / OUTBOUND / DONE / REJECTED
     */
    private String status;

    /**
     * 明细种类数
     */
    private Integer itemCount;

    /**
     * 总数量
     */
    private Integer totalQuantity;

    /**
     * 是否需要省级审批（跨本地网调拨需要，且仅支持单一物料）
     */
    private Integer needApproval;

    /**
     * 创建人
     */
    private String creator;

    /**
     * 拒绝原因
     */
    private String rejectReason;

    /**
     * 来源：MANUAL 手工建单 / AI 智能体生成
     */
    private String source;

    /**
     * 关联的补货建议 ID（AI 建单时记录，用于两段式人工确认的追溯）
     */
    private String suggestionId;

    /**
     * 建单时间。
     * <p>时间字段统一显式固定为「北京时间字符串」：Jackson 3（Boot 4）默认把 java.util.Date
     * 序列化成 ISO-8601 UTC（形如 2026-10-08T01:04:20.000Z），门户按 yyyy-MM-dd HH:mm:ss
     * 截断显示会差 8 小时（2026-10-08 修，list/page 直接返回本实体）。口径与 /get 的
     * TransferOrderVO（SimpleDateFormat）一致。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date createdAt;

    /**
     * 出库时间（手工路径）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date outboundAt;

    /**
     * 入库时间（手工路径）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date inboundAt;

    /**
     * 工单发送时间（账管/仓管闭环）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date sentAt;

    /**
     * 调出方账管确认时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date accountConfirmedAt;

    /**
     * 调出方仓管确认（执行出库/换仓）时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date keeperConfirmedAt;

    /**
     * 发送工单的人（账号）
     */
    private String senderUser;

    /**
     * 确认的账管账号
     */
    private String accountConfirmer;

    /**
     * 确认的仓管账号
     */
    private String keeperConfirmer;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
