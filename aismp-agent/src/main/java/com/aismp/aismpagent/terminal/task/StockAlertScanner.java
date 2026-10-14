package com.aismp.aismpagent.terminal.task;

import com.aismp.aismpagent.terminal.mapper.WarehouseMapper;
import com.aismp.aismpagent.terminal.model.entity.Warehouse;
import com.aismp.aismpagent.terminal.model.vo.InventoryHealthVO;
import com.aismp.aismpagent.terminal.service.TerminalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存告警扫描器 —— **原子能力**：巡检各仓库存健康度，产出「发现清单」。
 *
 * <p><b>职责边界（2026-10-07 调整）</b>：本类只做「查 + 判定」，
 * <b>不再写通知、不再自带定时</b>。告警闭环的编排（scan → 判定 → notify）
 * 上移到编排层（agent-orchestrator 的 alert 图）：
 *   编排层定时（默认 10 分钟，可配）或手动触发本类的 {@link #scan(String)} 拿发现清单，
 *   再逐条调 {@code POST /transferOrder/notice} 写通知（落库时按 receiver+type 去重）。
 * 这样 agent 退化为无状态的原子能力提供方，业务闭环的"大脑"在编排层 ——
 * 定时节奏、去重策略、失败重试都可在编排层调整，不必重编译 Java。
 *
 * <p><b>为什么扫描本身不调 LLM</b>：健康度判定全是确定性 SQL 聚合
 * （见 {@code TerminalServiceImpl#inventoryHealth}），属于开发文档 1.4 的红线 ——
 * 数字红线不交给模型。LLM 只在链路后半段（账管主动询问建议时）参与。
 *
 * <p><b>通知草稿为什么在这里生成</b>：文案模板依赖健康检查结果的数据形状
 * （summary / 首要建议字段），留在数据源头最不容易漂移；
 * 编排层只负责"投递 + 去重语义由落库端保证"。
 */
@Component
@Slf4j
public class StockAlertScanner {

    /** 通知类型：库存告警（与 ORDER_CREATED/ORDER_SENT/... 同一通道，只是 type 不同）。 */
    public static final String TYPE_STOCK_ALERT = "STOCK_ALERT";

    /** 收件角色：账管（调拨的发起与决策方）。 */
    private static final String ROLE_ACCOUNT_MANAGER = "ACCOUNT_MANAGER";

    @Resource
    private WarehouseMapper warehouseMapper;

    @Resource
    private TerminalService terminalService;

    /**
     * 扫描指定仓（不传则全部仓），返回有调拨建议的仓清单。
     *
     * <p>单仓失败不中断整轮 —— 一个仓的数据有问题不该影响其它仓的巡检。
     *
     * @return 扫描摘要（扫描仓数 + 发现清单；写通知由编排层完成）
     */
    public ScanResult scan(String warehouseCode) {
        List<Warehouse> warehouses;
        if (StringUtils.hasText(warehouseCode)) {
            Warehouse one = warehouseMapper.selectById(warehouseCode);
            warehouses = one == null ? List.of() : List.of(one);
        } else {
            warehouses = warehouseMapper.selectList(null);
        }

        List<Finding> findings = new ArrayList<>();
        for (Warehouse wh : warehouses) {
            InventoryHealthVO health;
            try {
                health = terminalService.inventoryHealth(wh.getWarehouseCode());
            } catch (Exception e) {
                log.warn("库存巡检跳过 {}：{}", wh.getWarehouseCode(), e.getMessage());
                continue;
            }
            if (health.getSuggestions() == null || health.getSuggestions().isEmpty()) {
                continue;
            }
            findings.add(buildFinding(wh, health));
        }
        return new ScanResult(warehouses.size(), findings);
    }

    /** 把健康检查结果压成「发现 + 通知草稿」（文案模板留在数据源头，编排层只投递）。 */
    private Finding buildFinding(Warehouse wh, InventoryHealthVO health) {
        String receiver = wh.getWarehouseCode() + ":" + ROLE_ACCOUNT_MANAGER;
        String title = String.format("库存告警：%s 有 %d 条调拨建议待处理",
                wh.getName(), health.getSuggestions().size());

        InventoryHealthVO.Suggestion top = health.getSuggestions().get(0);
        StringBuilder content = new StringBuilder();
        content.append(health.getSummary()).append('\n');
        content.append(String.format("首要建议：%s %s，从 %s 调入 %d 台（对方可用 %s 台）",
                top.getModel(), top.getMaterialName(), top.getFromWarehouseName(),
                top.getSuggestQty(), top.getAvailableAtFrom()));
        if (health.getSuggestions().size() > 1) {
            content.append(String.format("；另有 %d 条型号建议", health.getSuggestions().size() - 1));
        }
        content.append("\n点此通知可直接查看补货建议并一键建单。");

        return new Finding(wh.getWarehouseCode(), wh.getName(),
                health.getSuggestions().size(),
                new NoticeDraft(receiver, TYPE_STOCK_ALERT, title, clip(content.toString())));
    }

    /** content 列是 varchar(1024)，超长截断避免插入失败。 */
    private static String clip(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    /** 巡检发现（一个有调拨建议的仓）。 */
    public record Finding(String warehouseCode, String warehouseName,
                          int suggestionCount, NoticeDraft notice) {
    }

    /** 待写通知草稿（编排层调 /transferOrder/notice 投递）。 */
    public record NoticeDraft(String receiver, String noticeType, String title, String content) {
    }

    /** 扫描摘要。 */
    public record ScanResult(int scanned, List<Finding> findings) {
    }
}