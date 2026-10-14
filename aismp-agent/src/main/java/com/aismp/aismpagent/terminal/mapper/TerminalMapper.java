package com.aismp.aismpagent.terminal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aismp.aismpagent.terminal.model.entity.Terminal;
import com.aismp.aismpagent.terminal.model.vo.AgingDistributionVO;
import com.aismp.aismpagent.terminal.model.vo.InboundBucketVO;
import com.aismp.aismpagent.terminal.model.vo.TerminalInventoryVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 终端台账 Mapper
 * <p>
 * 除 BaseMapper 的标准 CRUD 外，这里放**补货算法需要的聚合查询**。
 * 全部用 SQL 聚合而不是 Java 层循环 —— 28354 条数据下单次聚合是毫秒级，
 * 循环统计则会随数据量线性劣化。
 * <p>
 * 注意：本项目 map-underscore-to-camel-case=false，结果集列名必须用别名对齐 VO 的驼峰属性。
 *
 * @author agent-platform
 */
public interface TerminalMapper extends BaseMapper<Terminal> {

    /**
     * 库存聚合：按 仓位 × 物料 × 型号 统计可用量、在途量、库龄
     *
     * @param warehouseCode 可选，限定仓位
     * @param model         可选，限定型号
     * @param bigType       可选，限定大类
     */
    @Select("<script>" +
            "SELECT t.warehouseCode            AS warehouseCode, " +
            "       w.name                     AS warehouseName, " +
            "       w.level                    AS warehouseLevel, " +
            "       t.bigType                  AS bigType, " +
            "       t.materialCode             AS materialCode, " +
            "       t.model                    AS model, " +
            "       t.materialName             AS materialName, " +
            "       t.vendor                   AS vendor, " +
            "       SUM(CASE WHEN t.terminalStatus = '可用' THEN 1 ELSE 0 END) AS availableQty, " +
            "       COUNT(*)                   AS totalQty, " +
            "       SUM(CASE WHEN t.terminalStatus = '在途' THEN 1 ELSE 0 END) AS inTransitQty, " +
            "       ROUND(AVG(t.agingDays), 1) AS avgAgingDays, " +
            "       MAX(t.agingDays)           AS maxAgingDays " +
            "FROM terminal t " +
            "LEFT JOIN warehouse w ON t.warehouseCode = w.warehouseCode " +
            "WHERE 1 = 1 " +
            "<if test='warehouseCode != null and warehouseCode != \"\"'> AND t.warehouseCode = #{warehouseCode}</if> " +
            "<if test='model != null and model != \"\"'> AND t.model = #{model}</if> " +
            "<if test='bigType != null and bigType != \"\"'> AND t.bigType = #{bigType}</if> " +
            "GROUP BY t.warehouseCode, w.name, w.level, t.bigType, t.materialCode, t.model, t.materialName, t.vendor " +
            "ORDER BY t.warehouseCode, availableQty DESC" +
            "</script>")
    List<TerminalInventoryVO> aggregateInventory(@Param("warehouseCode") String warehouseCode,
                                                 @Param("model") String model,
                                                 @Param("bigType") String bigType);

    /**
     * 按型号跨仓位汇总 —— 用于判断"全省是否有货，是否只能走采购"
     */
    @Select("SELECT t.model                    AS model, " +
            "       t.materialCode             AS materialCode, " +
            "       t.materialName             AS materialName, " +
            "       t.vendor                   AS vendor, " +
            "       t.bigType                  AS bigType, " +
            "       SUM(CASE WHEN t.terminalStatus = '可用' THEN 1 ELSE 0 END) AS availableQty, " +
            "       SUM(CASE WHEN t.terminalStatus = '在途' THEN 1 ELSE 0 END) AS inTransitQty, " +
            "       COUNT(*)                   AS totalQty, " +
            "       ROUND(AVG(t.agingDays), 1) AS avgAgingDays, " +
            "       MAX(t.agingDays)           AS maxAgingDays " +
            "FROM terminal t " +
            "GROUP BY t.model, t.materialCode, t.materialName, t.vendor, t.bigType " +
            "ORDER BY availableQty DESC")
    List<TerminalInventoryVO> aggregateByModel();

    /**
     * 库龄分布：整体或指定仓位
     */
    @Select("<script>" +
            "SELECT CASE WHEN t.agingDays IS NULL THEN 'NULL' " +
            "            WHEN t.agingDays &lt; 30  THEN '0-30' " +
            "            WHEN t.agingDays &lt; 90  THEN '30-90' " +
            "            WHEN t.agingDays &lt; 180 THEN '90-180' " +
            "            WHEN t.agingDays &lt; 365 THEN '180-365' " +
            "            ELSE '365+' END AS band, " +
            "       CASE WHEN t.agingDays IS NULL THEN 5 " +
            "            WHEN t.agingDays &lt; 30  THEN 0 " +
            "            WHEN t.agingDays &lt; 90  THEN 1 " +
            "            WHEN t.agingDays &lt; 180 THEN 2 " +
            "            WHEN t.agingDays &lt; 365 THEN 3 " +
            "            ELSE 4 END AS sortNo, " +
            "       COUNT(*) AS cnt, " +
            "       SUM(CASE WHEN t.terminalStatus = '可用' THEN 1 ELSE 0 END) AS availableQty " +
            "FROM terminal t " +
            "WHERE 1 = 1 " +
            "<if test='warehouseCode != null and warehouseCode != \"\"'> AND t.warehouseCode = #{warehouseCode}</if> " +
            "GROUP BY band, sortNo " +
            "ORDER BY sortNo" +
            "</script>")
    List<AgingDistributionVO> agingDistribution(@Param("warehouseCode") String warehouseCode);

    /**
     * 取某仓位某物料下的可用串码（出库校验用）
     *
     * <p>⚠️ 只用于"需要串码明细"的场景（前端选码、导出模板）。
     * 如果只是判断库存够不够，请用 {@link #countAvailable} ——
     * 用本方法配合大 limit 去数数会让 SQL 返回成百上千行（实测 2939 行），
     * 是建单接口超时的元凶。
     */
    @Select("SELECT serialNo FROM terminal " +
            "WHERE warehouseCode = #{warehouseCode} AND materialCode = #{materialCode} " +
            "  AND terminalStatus = '可用' " +
            "ORDER BY agingDays DESC " +
            "LIMIT #{limit}")
    List<String> listAvailableSerialNos(@Param("warehouseCode") String warehouseCode,
                                        @Param("materialCode") String materialCode,
                                        @Param("limit") int limit);

    /**
     * 数某仓位某物料的可用库存量（建单时校验充足性用）。
     *
     * <p>与 listAvailableSerialNos 的区别：只返回一个数字，不搬运明细行。
     * 有 idx_terminal_wh_status(warehouseCode, terminalStatus) 索引，毫秒级。
     */
    @Select("SELECT COUNT(*) FROM terminal " +
            "WHERE warehouseCode = #{warehouseCode} AND materialCode = #{materialCode} " +
            "  AND terminalStatus = '可用'")
    int countAvailable(@Param("warehouseCode") String warehouseCode,
                       @Param("materialCode") String materialCode);

    /**
     * 批量换状态 + 换仓（出库/入库共用）。
     *
     * <p>逐条 UPDATE 在批量场景下是 N 次往返（实测一次调拨 2939 台 = 2939 次 UPDATE），
     * 这里改成一条 IN 语句。返回受影响行数，调用方据此确认"是否全部改到了"。
     *
     * @param serialNos 串码列表（调用方保证非空且无重复）
     */
    @Update("<script>" +
            "UPDATE terminal SET terminalStatus = #{toStatus}, " +
            "       warehouseCode = #{toWarehouseCode}, " +
            "       ownerWarehouse = (SELECT name FROM warehouse WHERE warehouseCode = #{toWarehouseCode}), " +
            "       statusChangedAt = NOW(), updatedAt = NOW() " +
            "WHERE terminalStatus = #{fromStatus} AND serialNo IN " +
            "<foreach collection='serialNos' item='s' open='(' separator=',' close=')'>#{s}</foreach>" +
            "</script>")
    int transferBatch(@Param("serialNos") List<String> serialNos,
                      @Param("fromStatus") String fromStatus,
                      @Param("toStatus") String toStatus,
                      @Param("toWarehouseCode") String toWarehouseCode);

    /**
     * 单个终端的状态流转 + 换仓（出库/入库共用）
     * 返回受影响行数，调用方据此判断是否真的改到了库存。
     */
    @Update("UPDATE terminal SET terminalStatus = #{toStatus}, warehouseCode = #{toWarehouseCode}, " +
            "       ownerWarehouse = (SELECT name FROM warehouse WHERE warehouseCode = #{toWarehouseCode}), " +
            "       statusChangedAt = NOW(), updatedAt = NOW() " +
            "WHERE serialNo = #{serialNo} AND terminalStatus = #{fromStatus}")
    int transferOne(@Param("serialNo") String serialNo,
                    @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus,
                    @Param("toWarehouseCode") String toWarehouseCode);

    // -------------------------------------------------------------------------
    // 入库量时间维度（2026-10-07 新增）：「查数助手」的月/年/日期工具后端
    //
    // 口径说明：全部按【首次入库时间 firstInboundAt】归桶 —— 历史视角，
    // 一台设备 2025-06 入库、后来调拨走，仍记在 2025-06（不随当前状态漂移）。
    // -------------------------------------------------------------------------

    /**
     * 入库量时间序列：按 月/年/日 分桶统计，可选按仓位/型号附加分组。
     *
     * @param fmt           DATE_FORMAT 的格式串（服务层白名单：%Y-%m / %Y / %Y-%m-%d）
     * @param from          起始日期（含），可空
     * @param to            结束日期（含），可空
     * @param groupBy       "" 不分桶 / warehouse 按仓 / model 按型号
     */
    @Select("<script>" +
            "SELECT DATE_FORMAT(t.firstInboundAt, #{fmt}) AS period, " +
            "<choose>" +
            "  <when test='groupBy == \"warehouse\"'>t.warehouseCode AS groupKey, " +
            "        COALESCE(w.name, t.warehouseCode) AS groupName, </when>" +
            "  <when test='groupBy == \"model\"'>t.model AS groupKey, t.model AS groupName, </when>" +
            "  <otherwise>'' AS groupKey, '' AS groupName, </otherwise>" +
            "</choose>" +
            "COUNT(*) AS cnt " +
            "FROM terminal t " +
            "LEFT JOIN warehouse w ON t.warehouseCode = w.warehouseCode " +
            "WHERE t.firstInboundAt IS NOT NULL " +
            "<if test='from != null and from != \"\"'> AND t.firstInboundAt &gt;= #{from}</if> " +
            "<if test='to != null and to != \"\"'> AND t.firstInboundAt &lt; DATE_ADD(#{to}, INTERVAL 1 DAY)</if> " +
            "<if test='warehouseCode != null and warehouseCode != \"\"'> AND t.warehouseCode = #{warehouseCode}</if> " +
            "<if test='model != null and model != \"\"'> AND t.model = #{model}</if> " +
            "<if test='bigType != null and bigType != \"\"'> AND t.bigType = #{bigType}</if> " +
            "GROUP BY period, groupKey, groupName " +
            "ORDER BY period, cnt DESC" +
            "</script>")
    List<InboundBucketVO> inboundTrend(@Param("fmt") String fmt,
                                       @Param("from") String from,
                                       @Param("to") String to,
                                       @Param("warehouseCode") String warehouseCode,
                                       @Param("model") String model,
                                       @Param("bigType") String bigType,
                                       @Param("groupBy") String groupBy);

    /**
     * 指定日期区间内的按月入库量（monthCompare 的取数：先按区间取，服务层再对齐请求月份）。
     */
    @Select("<script>" +
            "SELECT DATE_FORMAT(t.firstInboundAt, '%Y-%m') AS period, " +
            "       '' AS groupKey, '' AS groupName, COUNT(*) AS cnt " +
            "FROM terminal t " +
            "WHERE t.firstInboundAt IS NOT NULL " +
            "AND t.firstInboundAt &gt;= #{from} AND t.firstInboundAt &lt; #{toExclusive} " +
            "<if test='warehouseCode != null and warehouseCode != \"\"'> AND t.warehouseCode = #{warehouseCode}</if> " +
            "<if test='model != null and model != \"\"'> AND t.model = #{model}</if> " +
            "<if test='bigType != null and bigType != \"\"'> AND t.bigType = #{bigType}</if> " +
            "GROUP BY period ORDER BY period" +
            "</script>")
    List<InboundBucketVO> inboundByMonthRange(@Param("from") String from,
                                              @Param("toExclusive") String toExclusive,
                                              @Param("warehouseCode") String warehouseCode,
                                              @Param("model") String model,
                                              @Param("bigType") String bigType);

    /**
     * 入库量汇总（不按时间分桶）：返回总量与可选的分组明细。
     *
     * @param groupBy "" 只出总量 / warehouse 按仓分布 / model 按型号分布
     */
    @Select("<script>" +
            "SELECT '' AS period, " +
            "<choose>" +
            "  <when test='groupBy == \"warehouse\"'>t.warehouseCode AS groupKey, " +
            "        COALESCE(w.name, t.warehouseCode) AS groupName, </when>" +
            "  <when test='groupBy == \"model\"'>t.model AS groupKey, t.model AS groupName, </when>" +
            "  <otherwise>'' AS groupKey, '' AS groupName, </otherwise>" +
            "</choose>" +
            "COUNT(*) AS cnt " +
            "FROM terminal t " +
            "LEFT JOIN warehouse w ON t.warehouseCode = w.warehouseCode " +
            "WHERE t.firstInboundAt IS NOT NULL " +
            "<if test='from != null and from != \"\"'> AND t.firstInboundAt &gt;= #{from}</if> " +
            "<if test='to != null and to != \"\"'> AND t.firstInboundAt &lt; DATE_ADD(#{to}, INTERVAL 1 DAY)</if> " +
            "<if test='warehouseCode != null and warehouseCode != \"\"'> AND t.warehouseCode = #{warehouseCode}</if> " +
            "<if test='model != null and model != \"\"'> AND t.model = #{model}</if> " +
            "<if test='bigType != null and bigType != \"\"'> AND t.bigType = #{bigType}</if> " +
            "GROUP BY groupKey, groupName " +
            "ORDER BY cnt DESC" +
            "</script>")
    List<InboundBucketVO> inboundSummary(@Param("from") String from,
                                         @Param("to") String to,
                                         @Param("warehouseCode") String warehouseCode,
                                         @Param("model") String model,
                                         @Param("bigType") String bigType,
                                         @Param("groupBy") String groupBy);
}
