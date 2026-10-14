package com.aismp.aismpagent.terminal.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 库龄分布（按区间分桶）
 * <p>
 * 对应开发文档里"长库龄总量"这个统计卡片，也是"建议调出"的判据之一：
 * 库龄高 + 本仓位可用量高于水位线 = 适合调出的候选。
 *
 * @author agent-platform
 */
@Data
public class AgingDistributionVO implements Serializable {

    /**
     * 区间标签：0-30 / 30-90 / 90-180 / 180-365 / 365+ / NULL
     */
    private String band;

    /**
     * 该区间终端总数
     */
    private Integer cnt;

    /**
     * 其中可用的数量
     */
    private Integer availableQty;

    /**
     * 区间排序号（0-5），前端按它排序
     */
    private Integer sortNo;

    private static final long serialVersionUID = 1L;
}
