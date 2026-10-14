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
 * 站内通知
 * <p>
 * 调拨闭环的每一步都会产生通知：建单推送给调出方库管、出库推送给调入方库管、
 * 入库完成后通知发起方、任一方拒绝则通知发起方。
 *
 * @author agent-platform
 */
@TableName(value = "notice")
@Data
public class Notice implements Serializable {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 接收方（账号 / 工号 / 仓位编码）
     */
    private String receiver;

    /**
     * 类型：ORDER_CREATED / OUTBOUND / INBOUND / REJECTED / LOGISTICS
     */
    private String noticeType;

    private String title;

    private String content;

    /**
     * 关联调拨单号
     */
    private String orderNo;

    /**
     * 是否已读
     */
    private Integer isRead;

    /**
     * 创建/刷新时间。
     * <p>显式固定为「北京时间字符串」：Jackson 3（Boot 4）默认把 java.util.Date 序列化成
     * ISO-8601 UTC（形如 2026-10-08T01:04:20.000Z），门户按 yyyy-MM-dd HH:mm:ss 截断显示
     * 会差 8 小时（2026-10-08 修）。与工单 VO（SimpleDateFormat）的接口口径保持一致。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private Date createdAt;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
