package com.aismp.aismpagent.terminal.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.aismp.aismpagent.terminal.common.ErrorCode;
import com.aismp.aismpagent.terminal.exception.BusinessException;
import com.aismp.aismpagent.terminal.mapper.TerminalMapper;
import com.aismp.aismpagent.terminal.mapper.WarehouseMapper;
import com.aismp.aismpagent.terminal.model.entity.Terminal;
import com.aismp.aismpagent.terminal.model.entity.Warehouse;
import com.aismp.aismpagent.terminal.model.vo.AgingDistributionVO;
import com.aismp.aismpagent.terminal.model.vo.InboundBucketVO;
import com.aismp.aismpagent.terminal.model.vo.InboundCompareVO;
import com.aismp.aismpagent.terminal.model.vo.InboundSummaryVO;
import com.aismp.aismpagent.terminal.model.vo.InboundTrendVO;
import com.aismp.aismpagent.terminal.model.vo.InventoryHealthVO;
import com.aismp.aismpagent.terminal.model.vo.InventorySnapshotVO;
import com.aismp.aismpagent.terminal.model.vo.TerminalInventoryVO;
import com.aismp.aismpagent.terminal.service.TerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 终端库存服务实现
 *
 * @author agent-platform
 */
@Service
@Slf4j
public class TerminalServiceImpl extends ServiceImpl<TerminalMapper, Terminal> implements TerminalService {

    /**
     * 长库龄阈值（天）—— 与开发文档"长库龄预警通报表"口径一致
     */
    private static final int LONG_AGING_DAYS = 180;

    @Resource
    private TerminalMapper terminalMapper;

    @Resource
    private WarehouseMapper warehouseMapper;

    @Override
    public List<TerminalInventoryVO> aggregateInventory(String warehouseCode, String model, String bigType) {
        return terminalMapper.aggregateInventory(warehouseCode, model, bigType);
    }

    @Override
    public List<TerminalInventoryVO> aggregateByModel() {
        return terminalMapper.aggregateByModel();
    }

    @Override
    public List<AgingDistributionVO> agingDistribution(String warehouseCode) {
        return terminalMapper.agingDistribution(warehouseCode);
    }

    @Override
    public InventorySnapshotVO buildSnapshot(String warehouseCode, String model) {
        InventorySnapshotVO snapshot = new InventorySnapshotVO();
        snapshot.setWarehouseCode(warehouseCode);
        snapshot.setModel(model);
        snapshot.setGeneratedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        List<TerminalInventoryVO> inventory =
                terminalMapper.aggregateInventory(warehouseCode, model, null);
        List<AgingDistributionVO> aging = terminalMapper.agingDistribution(warehouseCode);
        List<TerminalInventoryVO> byModel = terminalMapper.aggregateByModel();

        snapshot.setInventory(inventory);
        snapshot.setAging(aging);
        snapshot.setByModel(byModel);

        // 汇总数字：全部由上面的聚合结果累加得出，不额外查库、不做估算
        int total = 0, available = 0, inTransit = 0;
        if (inventory != null) {
            for (TerminalInventoryVO vo : inventory) {
                total += vo.getTotalQty() == null ? 0 : vo.getTotalQty();
                available += vo.getAvailableQty() == null ? 0 : vo.getAvailableQty();
                inTransit += vo.getInTransitQty() == null ? 0 : vo.getInTransitQty();
            }
        }
        snapshot.setTotalTerminals(total);
        snapshot.setTotalAvailable(available);
        snapshot.setTotalInTransit(inTransit);

        // 长库龄：从库龄分布里把 >=180 的两档加起来
        int longAging = 0;
        if (aging != null) {
            for (AgingDistributionVO band : aging) {
                if ("180-365".equals(band.getBand()) || "365+".equals(band.getBand())) {
                    longAging += band.getCnt() == null ? 0 : band.getCnt();
                }
            }
        }
        snapshot.setLongAgingCount(longAging);

        log.info("库存快照：仓库={} 型号={} 总数={} 可用={} 在途={} 长库龄={}",
                warehouseCode, model, total, available, inTransit, longAging);
        return snapshot;
    }

    @Override
    public List<String> listAvailableSerialNos(String warehouseCode, String materialCode, int limit) {
        return terminalMapper.listAvailableSerialNos(warehouseCode, materialCode, limit);
    }

    @Override
    public Map<String, Object> statCards() {
        Map<String, Object> cards = new HashMap<>(8);
        Integer total = baseMapper.selectCount(null).intValue();
        cards.put("totalStock", total);
        cards.put("longAging", countLongAging());
        // 数据源限制：当前只有库存快照表，没有出入库流水，这两项无法从真实数据得出。
        // 按"宁可显示空也不编数字"的原则返回 null。
        cards.put("yesterdayInstall", null);
        cards.put("yesterdayRecycle", null);
        return cards;
    }

    /**
     * 长库龄台数（库龄 >= 180 天）
     */
    private int countLongAging() {
        List<AgingDistributionVO> aging = terminalMapper.agingDistribution(null);
        int n = 0;
        if (aging != null) {
            for (AgingDistributionVO band : aging) {
                if ("180-365".equals(band.getBand()) || "365+".equals(band.getBand())) {
                    n += band.getCnt() == null ? 0 : band.getCnt();
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 库存健康检查 + 跨仓调拨建议（2026-10-06，门户「库存健康检查」按钮）
    // ------------------------------------------------------------------

    /** 富余仓阈值：调出方某物料可用 ≥ 200 台才建议调 */
    private static final int SURPLUS_THRESHOLD = 200;
    /** 缺货阈值：本仓某物料可用 < 50 台才建议调 */
    private static final int SHORTAGE_THRESHOLD = 50;

    @Override
    public InventoryHealthVO inventoryHealth(String warehouseCode) {
        if (!StringUtils.hasText(warehouseCode)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "warehouseCode 必填");
        }
        Warehouse me = warehouseMapper.selectById(warehouseCode);
        if (me == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "仓位不存在：" + warehouseCode);
        }

        List<TerminalInventoryVO> mine = terminalMapper.aggregateInventory(warehouseCode, null, null);
        List<AgingDistributionVO> aging = terminalMapper.agingDistribution(warehouseCode);

        int total = 0;
        int available = 0;
        int longAging = 0;
        Set<String> models = new HashSet<>();
        Map<String, Integer> myByMaterial = new HashMap<>();
        for (TerminalInventoryVO row : mine) {
            total += row.getTotalQty() == null ? 0 : row.getTotalQty();
            available += row.getAvailableQty() == null ? 0 : row.getAvailableQty();
            if (row.getModel() != null) {
                models.add(row.getModel());
            }
            myByMaterial.merge(row.getMaterialCode(),
                    row.getAvailableQty() == null ? 0 : row.getAvailableQty(), Integer::sum);
        }
        for (AgingDistributionVO band : aging) {
            if ("365+".equals(band.getBand())) {
                longAging += band.getCnt() == null ? 0 : band.getCnt();
            }
        }

        // 调出方（兄弟仓）：优先上级仓（三级库 → 二级库），没有上级取库存最大的其他仓
        Warehouse partner = resolvePartner(me);
        List<TerminalInventoryVO> theirs = partner == null
                ? new ArrayList<>() : terminalMapper.aggregateInventory(partner.getWarehouseCode(), null, null);

        List<InventoryHealthVO.Suggestion> suggestions = new ArrayList<>();
        for (TerminalInventoryVO row : theirs) {
            int atFrom = row.getAvailableQty() == null ? 0 : row.getAvailableQty();
            if (atFrom < SURPLUS_THRESHOLD) {
                continue;
            }
            int atTo = myByMaterial.getOrDefault(row.getMaterialCode(), 0);
            if (atTo >= SHORTAGE_THRESHOLD) {
                continue;
            }
            int qty = Math.max(10, Math.min(100, atFrom / 10));
            InventoryHealthVO.Suggestion s = new InventoryHealthVO.Suggestion();
            s.setMaterialCode(row.getMaterialCode());
            s.setModel(row.getModel());
            s.setMaterialName(row.getMaterialName());
            s.setBigType(row.getBigType());
            s.setFromWarehouse(partner.getWarehouseCode());
            s.setFromWarehouseName(partner.getName());
            s.setAvailableAtFrom(atFrom);
            s.setFromAvgAgingDays(row.getAvgAgingDays());
            s.setToWarehouse(me.getWarehouseCode());
            s.setToWarehouseName(me.getName());
            s.setAvailableAtTo(atTo);
            s.setSuggestQty(qty);
            s.setReason(String.format(
                    "本仓可用 %d 台（低于阈值 %d），%s 可用 %d 台、平均库龄 %.0f 天，建议调入 %d 台",
                    atTo, SHORTAGE_THRESHOLD, partner.getName(), atFrom,
                    row.getAvgAgingDays() == null ? 0d : row.getAvgAgingDays(), qty));
            suggestions.add(s);
        }
        // 先缺得最狠、再挑对方库龄最长的（长库龄优先出）
        suggestions.sort((a, b) -> {
            int c = Integer.compare(a.getAvailableAtTo(), b.getAvailableAtTo());
            if (c != 0) {
                return c;
            }
            double da = a.getFromAvgAgingDays() == null ? 0 : a.getFromAvgAgingDays();
            double db = b.getFromAvgAgingDays() == null ? 0 : b.getFromAvgAgingDays();
            return Double.compare(db, da);
        });
        if (suggestions.size() > 5) {
            suggestions = new ArrayList<>(suggestions.subList(0, 5));
        }

        double ratio = total == 0 ? 0d : (double) longAging / total;
        InventoryHealthVO vo = new InventoryHealthVO();
        vo.setWarehouseCode(me.getWarehouseCode());
        vo.setWarehouseName(me.getName());
        vo.setTotalStock(total);
        vo.setAvailableQty(available);
        vo.setLongAgingQty(longAging);
        vo.setLongAgingRatio(Math.round(ratio * 1000) / 1000.0);
        vo.setModelCount(models.size());
        vo.setHealthStatus(ratio >= 0.6 ? "RISK" : ratio >= 0.3 ? "WATCH" : "HEALTHY");
        vo.setSummary(String.format("%s：在库 %d 台（可用 %d 台）、型号 %d 个，长库龄（≥365 天）%d 台占比 %.1f%%%s",
                me.getName(), total, available, models.size(), longAging, ratio * 100,
                suggestions.isEmpty() ? "；暂无跨仓调拨建议" : "；有 " + suggestions.size() + " 条调拨建议待处理"));
        vo.setSuggestions(suggestions);

        log.info("库存健康检查：{} 总数={} 可用={} 长库龄={} 建议={} 条",
                warehouseCode, total, available, longAging, suggestions.size());
        return vo;
    }

    /** 找调出方：优先上级仓；没有就取库存最大的其他仓 */
    private Warehouse resolvePartner(Warehouse me) {
        if (StringUtils.hasText(me.getParentCode())) {
            Warehouse parent = warehouseMapper.selectById(me.getParentCode());
            if (parent != null) {
                return parent;
            }
        }
        List<TerminalInventoryVO> all = terminalMapper.aggregateInventory(null, null, null);
        Map<String, Integer> perWarehouse = new HashMap<>();
        for (TerminalInventoryVO row : all) {
            perWarehouse.merge(row.getWarehouseCode(),
                    row.getAvailableQty() == null ? 0 : row.getAvailableQty(), Integer::sum);
        }
        String bestCode = null;
        int bestQty = -1;
        for (Map.Entry<String, Integer> e : perWarehouse.entrySet()) {
            if (e.getKey().equals(me.getWarehouseCode())) {
                continue;
            }
            if (e.getValue() > bestQty) {
                bestQty = e.getValue();
                bestCode = e.getKey();
            }
        }
        return bestCode == null ? null : warehouseMapper.selectById(bestCode);
    }

    // -------------------------------------------------------------------------
    // 入库量时间维度（2026-10-07 新增）：「查数助手」的月/年/日期工具后端
    // 口径：按【首次入库时间】归桶（历史视角），数字全部来自 SQL COUNT。
    // -------------------------------------------------------------------------

    @Override
    public InboundTrendVO inboundTrend(String granularity, String from, String to,
                                       String warehouseCode, String model, String bigType, String groupBy) {
        String g = granularity == null ? "" : granularity.trim().toLowerCase();
        String fmt;
        if ("year".equals(g)) {
            fmt = "%Y";
        } else if ("day".equals(g)) {
            fmt = "%Y-%m-%d";
        } else {
            g = "month";
            fmt = "%Y-%m";
        }
        String f = requireDate(from, "from");
        String t = requireDate(to, "to");
        if ("day".equals(g)) {
            // 按天必须给区间：全跨度按天会返回 3000+ 个桶，既拖库又淹没模型
            if (f == null || t == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "按天查询必须同时给出 from 与 to（yyyy-MM-dd）；查多月/多年请改用月或年粒度");
            }
            long days = ChronoUnit.DAYS.between(LocalDate.parse(f), LocalDate.parse(t));
            if (days < 0 || days > 366) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "按天查询的日期区间需在 366 天以内");
            }
        }
        String group = ("warehouse".equals(groupBy) || "model".equals(groupBy)) ? groupBy : "";
        List<InboundBucketVO> rows =
                terminalMapper.inboundTrend(fmt, f, t, warehouseCode, model, bigType, group);
        InboundTrendVO vo = new InboundTrendVO();
        vo.setGranularity(g);
        vo.setFrom(f);
        vo.setTo(t);
        vo.setRows(rows);
        vo.setBucketCount(rows.size());
        vo.setTotalCnt(rows.stream().mapToInt(r -> r.getCnt() == null ? 0 : r.getCnt()).sum());
        return vo;
    }

    @Override
    public InboundCompareVO inboundMonthsCompare(String months, String warehouseCode,
                                                 String model, String bigType) {
        if (!StringUtils.hasText(months)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "months 不能为空（逗号分隔的 YYYY-MM，如 2025-06,2025-08）");
        }
        // 归一化（容忍 2025年6月 / 2025/6 等写法）+ 去重 + 升序
        TreeSet<String> parsed = new TreeSet<>();
        for (String raw : months.split("[,，]")) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            String m = normalizeMonth(raw);
            if (m == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "月份格式应为 YYYY-MM，收到：" + raw);
            }
            parsed.add(m);
        }
        if (parsed.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "months 里没有可识别的月份（YYYY-MM）");
        }
        if (parsed.size() > 24) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "一次最多对比 24 个月");
        }
        // 区间取数（走 firstInboundAt 索引），再对齐到请求月份、缺失补 0
        YearMonth min = YearMonth.parse(parsed.first());
        YearMonth max = YearMonth.parse(parsed.last());
        List<InboundBucketVO> got = terminalMapper.inboundByMonthRange(
                min.atDay(1).toString(), max.plusMonths(1).atDay(1).toString(),
                warehouseCode, model, bigType);
        Map<String, Integer> byMonth = new HashMap<>();
        for (InboundBucketVO r : got) {
            byMonth.put(r.getPeriod(), r.getCnt() == null ? 0 : r.getCnt());
        }
        List<InboundBucketVO> aligned = new ArrayList<>();
        int total = 0;
        for (String m : parsed) {
            int cnt = byMonth.getOrDefault(m, 0);
            total += cnt;
            InboundBucketVO row = new InboundBucketVO();
            row.setPeriod(m);
            row.setGroupKey("");
            row.setGroupName("");
            row.setCnt(cnt);
            aligned.add(row);
        }
        InboundCompareVO vo = new InboundCompareVO();
        vo.setMonths(new ArrayList<>(parsed));
        vo.setTotalCnt(total);
        vo.setRows(aligned);
        log.info("入库量月份对比：{} 合计={} 台", parsed, total);
        return vo;
    }

    @Override
    public InboundSummaryVO inboundSummary(String from, String to, String warehouseCode,
                                           String model, String bigType, String groupBy) {
        String f = requireDate(from, "from");
        String t = requireDate(to, "to");
        String group = ("warehouse".equals(groupBy) || "model".equals(groupBy)) ? groupBy : "none";
        List<InboundBucketVO> rows = terminalMapper.inboundSummary(
                f, t, warehouseCode, model, bigType, "none".equals(group) ? "" : group);
        InboundSummaryVO vo = new InboundSummaryVO();
        vo.setFrom(f);
        vo.setTo(t);
        vo.setGroupBy(group);
        vo.setGroups(rows);
        vo.setTotalCnt(rows.stream().mapToInt(r -> r.getCnt() == null ? 0 : r.getCnt()).sum());
        return vo;
    }

    /** 日期入参校验（白名单格式，防止畸形参数带进 SQL）；空值返回 null。 */
    private static String requireDate(String value, String name) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String v = value.trim();
        if (!v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, name + " 需为 yyyy-MM-dd 格式，收到：" + value);
        }
        return v;
    }

    /** 「2025年6月 / 2025/6 / 2025-6」→「2025-06」；无法识别返回 null。 */
    private static String normalizeMonth(String raw) {
        String m = raw.trim().replace("年", "-").replace("月", "")
                .replace("/", "-").replace(".", "-");
        if (!m.matches("\\d{4}-\\d{1,2}")) {
            return null;
        }
        String[] parts = m.split("-");
        int mm = Integer.parseInt(parts[1]);
        if (mm < 1 || mm > 12) {
            return null;
        }
        return parts[0] + "-" + String.format("%02d", mm);
    }
}
