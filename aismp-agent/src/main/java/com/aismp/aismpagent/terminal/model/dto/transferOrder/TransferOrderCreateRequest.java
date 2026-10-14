package com.aismp.aismpagent.terminal.model.dto.transferOrder;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 创建调拨单请求
 * <p>
 * 双入口共用：
 * 1. 手工建单（source=MANUAL）
 * 2. AI 智能体按补货建议建单（source=AI，带 suggestionId 便于两段式确认的追溯）
 *
 * @author agent-platform
 */
@Data
public class TransferOrderCreateRequest implements Serializable {

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
     * 创建人
     */
    private String creator;

    /**
     * 来源：MANUAL（默认）/ AI
     */
    private String source;

    /**
     * 关联的补货建议 ID（AI 建单时必填）
     */
    private String suggestionId;

    /**
     * 明细
     */
    private List<Item> items;

    @Data
    public static class Item implements Serializable {
        /**
         * 物料编码
         */
        private String materialCode;
        /**
         * 规格型号
         */
        private String model;
        /**
         * 物料名称
         */
        private String materialName;
        /**
         * 调拨数量
         */
        private Integer quantity;

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}
