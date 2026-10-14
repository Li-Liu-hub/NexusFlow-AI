package com.aismp.aismpagent.terminal.model.dto.transferOrder;

import lombok.Data;

import java.io.Serializable;

/**
 * 站内通知写入请求（写或刷新，幂等）
 *
 * <p>调用方是编排层的告警闭环（scan → 判定 → notify）：
 * 扫描拿到发现后，把通知草稿逐条投递到这里落库。
 * 去重语义在服务端保证 —— 同 receiver + noticeType 存在未读通知时只刷新内容，不再堆一条。
 *
 * @author agent-platform
 */
@Data
public class NoticeUpsertRequest implements Serializable {

    /**
     * 收件人（约定：`仓位编码:角色`，如 9BZA:ACCOUNT_MANAGER；也支持本人账号）
     */
    private String receiver;

    /**
     * 通知类型（如 STOCK_ALERT；工单类通知由调拨状态机内部推送，不走本接口）
     */
    private String noticeType;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容
     */
    private String content;

    /**
     * 关联调拨单号（可选；告警类通知无单号）
     */
    private String orderNo;

    private static final long serialVersionUID = 1L;
}