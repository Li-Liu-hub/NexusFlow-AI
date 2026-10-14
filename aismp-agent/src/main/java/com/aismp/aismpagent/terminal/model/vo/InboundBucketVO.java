package com.aismp.aismpagent.terminal.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 入库量时间桶（时间维度工具的通用行）
 * <p>
 * 三类查询共用：时间序列（period + cnt）、分组时间序列（period + groupKey/groupName + cnt）、
 * 汇总分组（groupKey/groupName + cnt，period 为空串）。
 * <p>
 * 口径：全部按【首次入库时间 firstInboundAt】归桶 —— 历史视角，
 * 不随设备当前状态/仓位变化。
 *
 * @author agent-platform
 */
@Data
public class InboundBucketVO implements Serializable {

    /**
     * 时间桶：月 YYYY-MM / 年 YYYY / 日 YYYY-MM-DD；汇总查询为空串
     */
    private String period;

    /**
     * 分组键：仓位编码或型号；未分组为空串
     */
    private String groupKey;

    /**
     * 分组显示名：仓位名或型号；未分组为空串
     */
    private String groupName;

    /**
     * 入库量（台）
     */
    private Integer cnt;

    private static final long serialVersionUID = 1L;
}