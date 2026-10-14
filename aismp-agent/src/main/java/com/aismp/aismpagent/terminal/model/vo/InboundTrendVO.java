package com.aismp.aismpagent.terminal.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 入库量时间序列（月度趋势 / 年度对比 / 按日期查询）
 * <p>
 * 「查数助手」时间维度工具的后端返回：totalCnt 是符合条件总数（= 各桶之和），
 * 供模型直接引用，不需要它自己求和（数字红线）。
 *
 * @author agent-platform
 */
@Data
public class InboundTrendVO implements Serializable {

    /**
     * 分桶粒度：month / year / day
     */
    private String granularity;

    /**
     * 起始日期（含），未限制为 null
     */
    private String from;

    /**
     * 结束日期（含），未限制为 null
     */
    private String to;

    /**
     * 符合条件总数（= 各桶 cnt 之和）
     */
    private Integer totalCnt;

    /**
     * 桶数
     */
    private Integer bucketCount;

    /**
     * 各桶明细（按时间升序；按仓/按型号分组时同一时间内 cnt 降序）
     */
    private List<InboundBucketVO> rows;

    private static final long serialVersionUID = 1L;
}