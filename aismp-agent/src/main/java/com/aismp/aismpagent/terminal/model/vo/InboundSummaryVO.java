package com.aismp.aismpagent.terminal.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 入库量汇总（不限时间桶）：总量 + 可选的分组明细
 * <p>
 * 问「一共入库多少台」「按仓/按型号看」时用它；groupBy=none 时 groups 只有一行（键为空）。
 *
 * @author agent-platform
 */
@Data
public class InboundSummaryVO implements Serializable {

    /**
     * 起始日期（含），未限制为 null
     */
    private String from;

    /**
     * 结束日期（含），未限制为 null
     */
    private String to;

    /**
     * 分组口径：none / warehouse / model
     */
    private String groupBy;

    /**
     * 入库总量（台，= 各分组之和）
     */
    private Integer totalCnt;

    /**
     * 分组明细（按 cnt 降序）
     */
    private List<InboundBucketVO> groups;

    private static final long serialVersionUID = 1L;
}