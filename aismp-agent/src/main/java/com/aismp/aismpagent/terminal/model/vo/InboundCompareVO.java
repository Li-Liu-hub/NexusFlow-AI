package com.aismp.aismpagent.terminal.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 指定月份的入库量对比（如 2025-06 vs 2025-08）
 * <p>
 * rows 与请求的 months 一一对齐（缺失月份补 0），months 按升序返回。
 *
 * @author agent-platform
 */
@Data
public class InboundCompareVO implements Serializable {

    /**
     * 请求对比的月份（YYYY-MM，升序）
     */
    private List<String> months;

    /**
     * 合计入库量（台，= 各月之和）
     */
    private Integer totalCnt;

    /**
     * 每月一行（period = YYYY-MM）
     */
    private List<InboundBucketVO> rows;

    private static final long serialVersionUID = 1L;
}