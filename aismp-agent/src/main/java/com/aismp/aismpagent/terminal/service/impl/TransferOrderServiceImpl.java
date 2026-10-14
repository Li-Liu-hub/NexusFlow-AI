package com.aismp.aismpagent.terminal.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.aismp.aismpagent.terminal.common.ErrorCode;
import com.aismp.aismpagent.terminal.exception.BusinessException;
import com.aismp.aismpagent.terminal.mapper.NoticeMapper;
import com.aismp.aismpagent.terminal.mapper.TerminalMapper;
import com.aismp.aismpagent.terminal.mapper.TransferOrderItemMapper;
import com.aismp.aismpagent.terminal.mapper.TransferOrderMapper;
import com.aismp.aismpagent.terminal.mapper.WarehouseMapper;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.NoticeUpsertRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderActionRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderCreateRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderOutboundRequest;
import com.aismp.aismpagent.terminal.model.dto.transferOrder.TransferOrderQueryRequest;
import com.aismp.aismpagent.terminal.model.entity.Notice;
import com.aismp.aismpagent.terminal.model.entity.Terminal;
import com.aismp.aismpagent.terminal.model.entity.TransferOrder;
import com.aismp.aismpagent.terminal.model.entity.TransferOrderItem;
import com.aismp.aismpagent.terminal.model.entity.Warehouse;
import com.aismp.aismpagent.terminal.model.vo.TransferOrderVO;
import com.aismp.aismpagent.terminal.service.TransferOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 调拨单服务实现
 * <p>
 * 四条硬规则（开发文档 1.4）全部在此校验，Service 层是唯一入口，
 * 不允许绕过（AI 建单与手工建单走同一个方法）。
 *
 * @author agent-platform
 */
@Service
@Slf4j
public class TransferOrderServiceImpl extends ServiceImpl<TransferOrderMapper, TransferOrder>
        implements TransferOrderService {

    /** 操作类型 */
    public static final String OP_TRANSFER = "TRANSFER";
    public static final String OP_ISSUE = "ISSUE";
    public static final String OP_RECALL = "RECALL";
    public static final String OP_RETURN = "RETURN";
    private static final Set<String> OPERATIONS =
            new HashSet<>(Arrays.asList(OP_TRANSFER, OP_ISSUE, OP_RECALL, OP_RETURN));

    /**
     * 单据状态（2026-10-08 链路重构：A 账管申请 → B 仓管导入 SN 出库 → A 仓管入库确认/驳回）
     * <pre>
     *   APPLIED  --B仓管确认出库--> OUTBOUND  --A仓管确认入库--> DONE
     *      │                            │
     *      └─B仓管拒绝(货未动)→ REJECTED └─A仓管驳回(货退回B仓)→ REJECTED
     * </pre>
     */
    public static final String ST_APPLIED = "APPLIED";
    public static final String ST_OUTBOUND = "OUTBOUND";
    public static final String ST_DONE = "DONE";
    public static final String ST_REJECTED = "REJECTED";

    /**
     * 历史状态（旧账管/仓管闭环，2026-10-08 已下线；仅用于老单据的标签展示）。
     *
     * <p>⚠️ 这些值仍存在于历史数据的 {@code transfer_order.status} 列里，
     * <b>不能删除常量、也不能改状态映射</b>；但<b>新逻辑禁止使用</b>——
     * 新链路只会产出 APPLIED / OUTBOUND / DONE / REJECTED（见 {@code TransferOrderStateTest}）。
     */
    @Deprecated
    public static final String ST_PENDING = "PENDING";
    /** @deprecated 历史状态，仅用于老单据标签展示；新逻辑禁用。 */
    @Deprecated
    public static final String ST_SENT = "SENT";
    /** @deprecated 历史状态，仅用于老单据标签展示；新逻辑禁用。 */
    @Deprecated
    public static final String ST_ACCOUNT_CONFIRMED = "ACCOUNT_CONFIRMED";

    /** 终端状态 */
    private static final String TS_AVAILABLE = "可用";
    private static final String TS_IN_TRANSIT = "在途";

    /** 调拨/下发 只允许"可用"；收回/回退 额外允许 待检、待报废 */
    private static final Set<String> STATUS_FOR_TRANSFER = Collections.singleton(TS_AVAILABLE);
    private static final Set<String> STATUS_FOR_RECALL_OR_RETURN =
            new HashSet<>(Arrays.asList(TS_AVAILABLE, "待检", "待报废"));

    private static final Random RANDOM = new Random();

    @Resource
    private TerminalMapper terminalMapper;
    @Resource
    private WarehouseMapper warehouseMapper;
    @Resource
    private TransferOrderItemMapper itemMapper;
    @Resource
    private NoticeMapper noticeMapper;

    // ------------------------------------------------------------------
    // 建单
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransferOrderVO createOrder(TransferOrderCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.getFromWarehouse())
                || !StringUtils.hasText(request.getToWarehouse())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "调出/调入仓位不能为空");
        }
        if (CollectionUtils.isEmpty(request.getItems())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "调拨明细不能为空");
        }
        if (Objects.equals(request.getFromWarehouse(), request.getToWarehouse())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "调出与调入仓不能相同");
        }

        Warehouse from = warehouseMapper.selectById(request.getFromWarehouse());
        Warehouse to = warehouseMapper.selectById(request.getToWarehouse());
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "仓位不存在");
        }

        String op = StringUtils.hasText(request.getOperationType())
                ? request.getOperationType().toUpperCase() : OP_TRANSFER;
        validateOperation(op, from, to);

        // 跨本地网：需省级审批，且仅支持单一物料
        int needApproval = 0;
        if (!Objects.equals(from.getCity(), to.getCity())) {
            needApproval = 1;
            if (request.getItems().size() > 1) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "跨本地网调拨仅支持单一物料，当前提交了 " + request.getItems().size() + " 种");
            }
        }

        String orderNo = generateOrderNo();

        TransferOrder order = new TransferOrder();
        order.setOrderNo(orderNo);
        order.setOperationType(op);
        order.setFromWarehouse(from.getWarehouseCode());
        order.setToWarehouse(to.getWarehouseCode());
        order.setStatus(ST_APPLIED);
        order.setItemCount(request.getItems().size());
        order.setTotalQuantity(request.getItems().stream()
                .mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum());
        order.setNeedApproval(needApproval);
        order.setCreator(StringUtils.hasText(request.getCreator()) ? request.getCreator() : "system");
        order.setSource(StringUtils.hasText(request.getSource()) ? request.getSource() : "MANUAL");
        order.setSuggestionId(request.getSuggestionId());
        order.setCreatedAt(new Date());
        this.save(order);

        for (TransferOrderCreateRequest.Item item : request.getItems()) {
            if (!StringUtils.hasText(item.getMaterialCode()) || item.getQuantity() == null
                    || item.getQuantity() <= 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "明细的物料编码与数量必须有效");
            }
            // 建单时校验库存充足性（出库才是真正扣减，这里先挡住明显不可能的）
            // ⚠️ 必须用 countAvailable（COUNT(*)）而不是 listAvailableSerialNos：
            // 后者为算个数会把整个可用清单搬回来（实测单次 2939 行），
            // 是"建 13 张单超时"的元凶（2026-10-01 e2e 暴露）
            int availableCount = terminalMapper.countAvailable(
                    from.getWarehouseCode(), item.getMaterialCode());
            if (availableCount < item.getQuantity()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        String.format("仓位 %s 的物料 %s 可用库存不足：需要 %d，实际可用 %d",
                                from.getWarehouseCode(), item.getMaterialCode(),
                                item.getQuantity(), availableCount));
            }

            TransferOrderItem entity = new TransferOrderItem();
            entity.setOrderNo(orderNo);
            entity.setMaterialCode(item.getMaterialCode());
            entity.setModel(item.getModel());
            entity.setMaterialName(item.getMaterialName());
            entity.setQuantity(item.getQuantity());
            itemMapper.insert(entity);
        }

        // 通知调出方仓管：有一条申请待确认出库（2026-10-08 链路重构：申请直达仓管，无账管环节）
        pushNotice(roleReceiver(from.getWarehouseCode(), "WAREHOUSE_KEEPER"), "ORDER_APPLIED",
                "有一条调拨申请待您确认出库",
                String.format("调拨单 %s：%s 申请从本仓调入 %d 台，请导入 SN 码并确认出库",
                        orderNo, to.getName(), order.getTotalQuantity()),
                orderNo);

        log.info("建单成功：{} 操作={} {}→{} 数量={} 需审批={}",
                orderNo, op, from.getWarehouseCode(), to.getWarehouseCode(),
                order.getTotalQuantity(), needApproval);

        return getOrderDetail(orderNo);
    }

    /**
     * 校验"调拨四操作"的层级关系
     * <p>
     * TRANSFER 调拨：上级发起，把自己的货调给下属（from 层级 < to 层级）
     * ISSUE    下发：上级→下级，**不可跨级**（to 必须正好是 from 的下一级）
     * RECALL   收回：上级收回下级（货从下级回到上级，from 层级 > to 层级）
     * RETURN   回退：下级退上级（方向同 RECALL）
     */
    private void validateOperation(String op, Warehouse from, Warehouse to) {
        if (!OPERATIONS.contains(op)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "不支持的操作类型：" + op + "（仅 TRANSFER / ISSUE / RECALL / RETURN）");
        }
        int fl = from.getLevel() == null ? 0 : from.getLevel();
        int tl = to.getLevel() == null ? 0 : to.getLevel();

        switch (op) {
            case OP_ISSUE:
                if (tl != fl + 1) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            String.format("下发只能到直接下级仓位（%s 是 %d 级，%s 是 %d 级）",
                                    from.getName(), fl, to.getName(), tl));
                }
                break;
            case OP_TRANSFER:
                if (fl >= tl) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "调拨由上级发起，调出仓位层级必须高于调入仓位");
                }
                break;
            case OP_RECALL:
            case OP_RETURN:
                if (tl >= fl) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            ("RECALL".equals(op) ? "收回" : "回退") + "的方向必须是下级退上级");
                }
                break;
            default:
                break;
        }
    }

    private String generateOrderNo() {
        String day = new SimpleDateFormat("yyyyMMdd").format(new Date());
        return "TR" + day + String.format("%04d", RANDOM.nextInt(10000));
    }

    // ------------------------------------------------------------------
    // 出库
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransferOrderVO outbound(TransferOrderOutboundRequest request) {
        if (request == null || !StringUtils.hasText(request.getOrderNo())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "调拨单号不能为空");
        }
        TransferOrder order = this.getById(request.getOrderNo());
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "调拨单不存在");
        }
        if (!ST_APPLIED.equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "只有「待确认出库」的调拨申请可以确认出库，当前状态：" + order.getStatus());
        }

        List<String> serialNos = request.getSerialNos();
        if (CollectionUtils.isEmpty(serialNos)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "出库串码清单不能为空");
        }
        // 去重，避免同一串码被重复计数
        List<String> distinct = serialNos.stream().distinct().collect(Collectors.toList());

        List<TransferOrderItem> items = listItems(order.getOrderNo());
        Set<String> allowedStatus = allowedStatusFor(order.getOperationType());

        // 明细按物料编码索引，用于型号比对
        java.util.Map<String, TransferOrderItem> itemByMaterial = items.stream()
                .collect(Collectors.toMap(TransferOrderItem::getMaterialCode, i -> i, (a, b) -> a));

        // 【性能】一次性把这批串码查出来 —— 逐条 selectById 是 N 次单条查询，
        // 实测一次调拨 2939 台就是 2939 次往返，是出库超时的元凶
        List<Terminal> terminals = terminalMapper.selectList(
                new QueryWrapper<Terminal>().in("serialNo", distinct));
        java.util.Map<String, Terminal> bySerial = terminals.stream()
                .collect(Collectors.toMap(Terminal::getSerialNo, t -> t, (a, b) -> a));

        java.util.Map<String, Integer> counted = new java.util.HashMap<>();
        for (String serialNo : distinct) {
            Terminal t = bySerial.get(serialNo);
            if (t == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "串码不存在：" + serialNo);
            }
            // ① 必须在本单的调出仓位
            if (!Objects.equals(t.getWarehouseCode(), order.getFromWarehouse())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        String.format("串码 %s 不在调出仓位 %s（当前在 %s）",
                                serialNo, order.getFromWarehouse(), t.getWarehouseCode()));
            }
            // ② 状态必须符合该操作类型的限制
            if (!allowedStatus.contains(t.getTerminalStatus())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        String.format("串码 %s 的状态为「%s」，%s 仅允许 %s",
                                serialNo, t.getTerminalStatus(), order.getOperationType(), allowedStatus));
            }
            // ③ 型号必须在本单明细里
            TransferOrderItem matched = itemByMaterial.get(t.getMaterialCode());
            if (matched == null) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        String.format("串码 %s 的物料 %s 不在本调拨单明细中",
                                serialNo, t.getMaterialCode()));
            }
            counted.merge(t.getMaterialCode(), 1, Integer::sum);
        }

        // ④ 每个物料的出库数量必须与明细数量严格一致
        for (TransferOrderItem item : items) {
            int actual = counted.getOrDefault(item.getMaterialCode(), 0);
            if (actual != item.getQuantity()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        String.format("物料 %s 出库数量不符：单据要求 %d，实际导入 %d",
                                item.getMaterialCode(), item.getQuantity(), actual));
            }
        }

        // 【性能】按源状态分组批量更新（收回/回退允许"可用/待检/待报废"三种状态，
        // 所以不能一条 SQL 打天下；状态种类数很小，分组后仍只有几次往返）
        java.util.Map<String, List<String>> byStatus = new java.util.HashMap<>();
        for (String serialNo : distinct) {
            Terminal t = bySerial.get(serialNo);
            byStatus.computeIfAbsent(t.getTerminalStatus(), k -> new ArrayList<>()).add(serialNo);
        }
        int updated = 0;
        for (java.util.Map.Entry<String, List<String>> entry : byStatus.entrySet()) {
            updated += terminalMapper.transferBatch(entry.getValue(), entry.getKey(),
                    TS_IN_TRANSIT, order.getFromWarehouse());
        }
        if (updated != distinct.size()) {
            // 事务回滚：比对与更新之间被并发改动
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    String.format("出库未全部生效（期望 %d 条，实际 %d 条），已回滚",
                            distinct.size(), updated));
        }

        // 串码回写到明细，供入库环节照抄
        for (TransferOrderItem item : items) {
            List<String> own = distinct.stream()
                    .filter(s -> {
                        Terminal t = bySerial.get(s);
                        return t != null && Objects.equals(t.getMaterialCode(), item.getMaterialCode());
                    })
                    .collect(Collectors.toList());
            item.setSerialNos("[\"" + String.join("\",\"", own) + "\"]");
            itemMapper.updateById(item);
        }

        order.setStatus(ST_OUTBOUND);
        order.setOutboundAt(new Date());
        this.updateById(order);

        pushNotice(roleReceiver(order.getToWarehouse(), "WAREHOUSE_KEEPER"), "OUTBOUND",
                "有货已出库，请确认入库",
                String.format("调拨单 %s 已由 %s 出库，共 %d 台（%d 个 SN），请确认入库或驳回入库",
                        order.getOrderNo(), order.getFromWarehouse(), distinct.size(), distinct.size()),
                order.getOrderNo());

        log.info("出库成功：{} 串码 {} 个", order.getOrderNo(), distinct.size());
        return getOrderDetail(order.getOrderNo());
    }

    // ------------------------------------------------------------------
    // 入库 / 拒绝
    // ------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransferOrderVO inbound(TransferOrderActionRequest request) {
        TransferOrder order = requireOrder(request);
        if (!ST_OUTBOUND.equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "只有「已出库」的调拨单可以入库确认，当前状态：" + order.getStatus());
        }

        List<TransferOrderItem> items = listItems(order.getOrderNo());
        // 汇总全部串码，一次批量换仓（逐条 UPDATE 在批量场景是 N 次往返）
        List<String> allSerialNos = new ArrayList<>();
        for (TransferOrderItem item : items) {
            allSerialNos.addAll(parseSerialNos(item.getSerialNos()));
        }
        if (allSerialNos.isEmpty()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "该调拨单没有串码记录（出库未完成？），无法入库");
        }

        int moved = terminalMapper.transferBatch(allSerialNos, TS_IN_TRANSIT,
                TS_AVAILABLE, order.getToWarehouse());
        if (moved != allSerialNos.size()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    String.format("入库未全部生效（期望 %d 条，实际 %d 条），已回滚",
                            allSerialNos.size(), moved));
        }

        order.setStatus(ST_DONE);
        order.setInboundAt(new Date());
        this.updateById(order);

        pushNotice(order.getCreator(), "DONE",
                "入库已确认，调拨完成",
                String.format("调拨单 %s 已由 %s 确认入库，共 %d 台",
                        order.getOrderNo(), order.getToWarehouse(), moved),
                order.getOrderNo());
        pushNotice(roleReceiver(order.getFromWarehouse(), "WAREHOUSE_KEEPER"), "DONE",
                "调拨完成：对方已确认入库",
                String.format("调拨单 %s：%d 台已由 %s 确认入库完成",
                        order.getOrderNo(), moved, order.getToWarehouse()),
                order.getOrderNo());

        log.info("入库成功：{} 串码 {} 个", order.getOrderNo(), moved);
        return getOrderDetail(order.getOrderNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransferOrderVO reject(TransferOrderActionRequest request) {
        TransferOrder order = requireOrder(request);
        String reason = StringUtils.hasText(request.getReason())
                ? request.getReason() : "未填写原因";

        if (ST_APPLIED.equals(order.getStatus())) {
            // 出库环节拒绝（B 仓管不同意）：货未动，直接置驳回并回传申请方
            order.setStatus(ST_REJECTED);
            order.setRejectReason(reason);
            this.updateById(order);
            pushNotice(order.getCreator(), "REJECTED",
                    "调拨申请被拒绝",
                    String.format("调拨单 %s 被 %s 拒绝：%s",
                            order.getOrderNo(), order.getFromWarehouse(), reason),
                    order.getOrderNo());
            log.info("调拨申请被拒绝：{} 原因={}", order.getOrderNo(), reason);
            return getOrderDetail(order.getOrderNo());
        }

        if (ST_OUTBOUND.equals(order.getStatus())) {
            // 入库环节驳回（A 仓管）：在途串码退回原调出仓「可用」，货不留在路上
            List<TransferOrderItem> items = listItems(order.getOrderNo());
            List<String> allSerialNos = new ArrayList<>();
            for (TransferOrderItem item : items) {
                allSerialNos.addAll(parseSerialNos(item.getSerialNos()));
            }
            int returned = 0;
            if (!allSerialNos.isEmpty()) {
                returned = terminalMapper.transferBatch(allSerialNos, TS_IN_TRANSIT,
                        TS_AVAILABLE, order.getFromWarehouse());
                if (returned != allSerialNos.size()) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            String.format("驳回回滚未全部生效（期望 %d 条，实际 %d 条），已回滚",
                                    allSerialNos.size(), returned));
                }
            }
            order.setStatus(ST_REJECTED);
            order.setRejectReason(reason);
            this.updateById(order);

            pushNotice(order.getCreator(), "REJECTED",
                    "入库被驳回，货已退回",
                    String.format("调拨单 %s 被 %s 驳回入库：%s；%d 台已退回 %s",
                            order.getOrderNo(), order.getToWarehouse(), reason,
                            returned, order.getFromWarehouse()),
                    order.getOrderNo());
            pushNotice(roleReceiver(order.getFromWarehouse(), "WAREHOUSE_KEEPER"), "REJECTED",
                    "出库货品被退回本仓",
                    String.format("调拨单 %s 被调入方驳回入库：%s；%d 台已退回本仓可用库存",
                            order.getOrderNo(), reason, returned),
                    order.getOrderNo());
            log.info("入库被驳回：{} 原因={} 退回 {} 台", order.getOrderNo(), reason, returned);
            return getOrderDetail(order.getOrderNo());
        }

        throw new BusinessException(ErrorCode.OPERATION_ERROR,
                "当前状态（" + order.getStatus() + "）不支持驳回："
                        + "仅「待确认出库」的申请与「待确认入库」的在途单可驳回");
    }

    // 旧的账管/仓管确认闭环（send → accountConfirm → keeperConfirm）已于 2026-10-08 下线：
    // 链路改为「A 账管申请 → B 仓管导入 SN 出库 → A 仓管入库确认/驳回」，不再有账管确认环节。
    // 出库走 outbound（导 SN），入库确认走 inbound，驳回走 reject（按状态分支）。

    /** "仓位:角色" 形式的通知收件人（前端按登录身份的 仓位+角色 取件） */
    private String roleReceiver(String warehouseCode, String role) {
        return warehouseCode + ":" + role;
    }

    @Override
    public List<Notice> listNotices(String warehouseCode, String role, String userCode, int limit) {
        List<String> keys = new ArrayList<>();
        if (StringUtils.hasText(warehouseCode) && StringUtils.hasText(role)) {
            keys.add(warehouseCode + ":" + role);
        }
        if (StringUtils.hasText(warehouseCode)) {
            keys.add(warehouseCode);
        }
        if (StringUtils.hasText(userCode)) {
            keys.add(userCode);
        }
        if (keys.isEmpty()) {
            return new ArrayList<>();
        }
        int size = Math.min(50, Math.max(1, limit));
        return noticeMapper.selectList(new QueryWrapper<Notice>()
                .in("receiver", keys)
                .orderByDesc("createdAt")
                .last("LIMIT " + size));
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public TransferOrderVO getOrderDetail(String orderNo) {
        TransferOrder order = this.getById(orderNo);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "调拨单不存在");
        }
        TransferOrderVO vo = toVO(order);
        List<TransferOrderVO.Line> lines = new ArrayList<>();
        int moved = 0;
        for (TransferOrderItem item : listItems(orderNo)) {
            TransferOrderVO.Line line = new TransferOrderVO.Line();
            line.setMaterialCode(item.getMaterialCode());
            line.setModel(item.getModel());
            line.setMaterialName(item.getMaterialName());
            line.setQuantity(item.getQuantity());
            line.setSerialNos(parseSerialNos(item.getSerialNos()));
            lines.add(line);
            moved += line.getSerialNos().size();
        }
        vo.setItems(lines);
        vo.setMovedQuantity(moved);
        return vo;
    }

    @Override
    public Page<TransferOrder> pageOrders(TransferOrderQueryRequest query) {
        return pageOrders(query, null);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private TransferOrder requireOrder(TransferOrderActionRequest request) {
        if (request == null || !StringUtils.hasText(request.getOrderNo())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "调拨单号不能为空");
        }
        TransferOrder order = this.getById(request.getOrderNo());
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "调拨单不存在");
        }
        return order;
    }

    private Set<String> allowedStatusFor(String operationType) {
        if (OP_RECALL.equals(operationType) || OP_RETURN.equals(operationType)) {
            return STATUS_FOR_RECALL_OR_RETURN;
        }
        return STATUS_FOR_TRANSFER;
    }

    private List<TransferOrderItem> listItems(String orderNo) {
        return itemMapper.selectList(new QueryWrapper<TransferOrderItem>().eq("orderNo", orderNo));
    }

    /**
     * 解析明细里的串码 JSON（存的是 ["a","b"] 形式）
     */
    private List<String> parseSerialNos(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (!StringUtils.hasText(body)) {
            return new ArrayList<>();
        }
        return Arrays.stream(body.split(","))
                .map(s -> s.trim().replace("\"", ""))
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    private TransferOrderVO toVO(TransferOrder order) {
        TransferOrderVO vo = new TransferOrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setOperationType(order.getOperationType());
        vo.setFromWarehouse(order.getFromWarehouse());
        vo.setToWarehouse(order.getToWarehouse());
        vo.setStatus(order.getStatus());
        vo.setStatusLabel(statusLabel(order.getStatus()));
        vo.setPendingAction(pendingAction(order.getStatus()));
        vo.setItemCount(order.getItemCount());
        vo.setTotalQuantity(order.getTotalQuantity());
        // 已完成时给出实际调拨台数（前端完成提示用）——总数即实调数（出库数量严格等于单据数量）
        vo.setMovedQuantity(ST_DONE.equals(order.getStatus()) ? order.getTotalQuantity() : null);
        vo.setNeedApproval(order.getNeedApproval());
        vo.setCreator(order.getCreator());
        vo.setRejectReason(order.getRejectReason());
        vo.setSource(order.getSource());
        vo.setSuggestionId(order.getSuggestionId());
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        vo.setCreatedAt(order.getCreatedAt() == null ? null : fmt.format(order.getCreatedAt()));
        vo.setOutboundAt(order.getOutboundAt() == null ? null : fmt.format(order.getOutboundAt()));
        vo.setInboundAt(order.getInboundAt() == null ? null : fmt.format(order.getInboundAt()));
        vo.setSentAt(order.getSentAt() == null ? null : fmt.format(order.getSentAt()));
        vo.setAccountConfirmedAt(order.getAccountConfirmedAt() == null ? null : fmt.format(order.getAccountConfirmedAt()));
        vo.setKeeperConfirmedAt(order.getKeeperConfirmedAt() == null ? null : fmt.format(order.getKeeperConfirmedAt()));
        vo.setSenderUser(order.getSenderUser());
        vo.setAccountConfirmer(order.getAccountConfirmer());
        vo.setKeeperConfirmer(order.getKeeperConfirmer());

        Warehouse from = warehouseMapper.selectById(order.getFromWarehouse());
        Warehouse to = warehouseMapper.selectById(order.getToWarehouse());
        vo.setFromWarehouseName(from == null ? null : from.getName());
        vo.setToWarehouseName(to == null ? null : to.getName());
        return vo;
    }

    /** 状态中文标签（前端直接显示） */
    private String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            // 环节状态（2026-10-09）：徽标直接写「当前卡在哪个环节」，与门户列表一致
            case ST_APPLIED -> "待确认出库";
            case ST_OUTBOUND -> "待确认入库";
            case ST_DONE -> "已完成";
            case ST_REJECTED -> "已驳回";
            // 历史状态（2026-10-08 前旧闭环，仅老单据展示用）
            case ST_PENDING -> "待发送（历史）";
            case ST_SENT -> "待账管确认（历史）";
            case ST_ACCOUNT_CONFIRMED -> "待仓管确认（历史）";
            default -> status;
        };
    }

    /** "现在轮到谁"的提示（终态返回空串） */
    private String pendingAction(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case ST_APPLIED -> "待调出方仓管导入 SN 并确认出库";
            case ST_OUTBOUND -> "待调入方仓管确认入库或驳回";
            default -> "";
        };
    }

    private void pushNotice(String receiver, String type, String title, String content, String orderNo) {
        Notice notice = new Notice();
        notice.setReceiver(receiver);
        notice.setNoticeType(type);
        notice.setTitle(title);
        notice.setContent(content);
        notice.setOrderNo(orderNo);
        notice.setIsRead(0);
        notice.setCreatedAt(new Date());
        noticeMapper.insert(notice);
    }

    /**
     * 写 / 刷新一条站内通知（幂等）。
     *
     * <p>去重口径与工单类通知（pushNotice）刻意不同：工单每一步都是一次性事件，
     * 必须逐条落；而告警是**周期性巡检**产出的持续状态，同一仓未读时只刷新内容 ——
     * 否则每 10 分钟一条会把账管的通知面板刷满，反而淹没了工单类通知。
     * 账管读掉之后若仍未处理，下一轮会重新提醒。
     */
    @Override
    public Map<String, Object> upsertNotice(NoticeUpsertRequest request) {
        if (request == null || !StringUtils.hasText(request.getReceiver())
                || !StringUtils.hasText(request.getNoticeType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "receiver 与 noticeType 必填");
        }
        Notice existing = noticeMapper.selectOne(new QueryWrapper<Notice>()
                .eq("receiver", request.getReceiver())
                .eq("noticeType", request.getNoticeType())
                .eq("isRead", 0)
                .orderByDesc("createdAt")
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setTitle(request.getTitle());
            existing.setContent(clipContent(request.getContent()));
            existing.setCreatedAt(new Date());
            noticeMapper.updateById(existing);
            return Map.of("action", "updated", "id", existing.getId());
        }

        Notice notice = new Notice();
        notice.setReceiver(request.getReceiver());
        notice.setNoticeType(request.getNoticeType());
        notice.setTitle(request.getTitle());
        notice.setContent(clipContent(request.getContent()));
        notice.setOrderNo(request.getOrderNo());
        notice.setIsRead(0);
        notice.setCreatedAt(new Date());
        noticeMapper.insert(notice);
        log.info("站内通知 → {}：{}", request.getReceiver(), request.getTitle());
        return Map.of("action", "inserted", "id", notice.getId());
    }

    /** content 列是 varchar(1024)，超长截断避免插入失败。 */
    private static String clipContent(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }

    /**
     * 分页查询（带可见范围，2026-10-08）。
     *
     * <p>visibleScope 非空（门户通道）：工单的调出仓或调入仓任一端命中可见范围才保留 ——
     * query 自报的 fromWarehouse/toWarehouse 条件与之取交集，只能收紧、不能放大。
     * visibleScope 为 null（服务方通道 / 本机脚本）：保持原语义，不做范围过滤。
     */
    @Override
    public Page<TransferOrder> pageOrders(TransferOrderQueryRequest query, java.util.Set<String> visibleScope) {
        QueryWrapper<TransferOrder> wrapper = new QueryWrapper<>();
        if (query != null) {
            wrapper.eq(StringUtils.hasText(query.getOrderNo()), "orderNo", query.getOrderNo());
            wrapper.eq(StringUtils.hasText(query.getStatus()), "status", query.getStatus());
            wrapper.eq(StringUtils.hasText(query.getOperationType()), "operationType", query.getOperationType());
            wrapper.eq(StringUtils.hasText(query.getFromWarehouse()), "fromWarehouse", query.getFromWarehouse());
            wrapper.eq(StringUtils.hasText(query.getToWarehouse()), "toWarehouse", query.getToWarehouse());
            // 时间筛选（2026-10-09）：按创建时间过滤；endTime 只给日期时含当天整天
            java.util.Date start = parseTimeParam(query.getStartTime(), false);
            java.util.Date end = parseTimeParam(query.getEndTime(), true);
            wrapper.ge(start != null, "createdAt", start);
            wrapper.le(end != null, "createdAt", end);
        }
        if (visibleScope != null) {
            wrapper.and(w -> w.in("fromWarehouse", visibleScope).or().in("toWarehouse", visibleScope));
        }
        wrapper.orderByDesc("createdAt");
        long current = query == null ? 1 : Math.max(1, query.getCurrent());
        long size = query == null ? 10 : Math.min(50, Math.max(1, query.getPageSize()));
        return this.page(new Page<>(current, size), wrapper);
    }

    /**
     * 【实验功能·演示用】按工单明细生成出库 SN 清单（2026-10-08）。
     *
     * <p>每物料从调出仓可用库存按库龄倒序抓 quantity 个 —— 与 outbound 用同一个
     * {@code listAvailableSerialNos}（库龄倒序），生成的清单原样导入必过四重校验：
     * 存在 ✓ / 在调出仓 ✓ / 状态=可用 ✓ / 每物料数量=明细数量 ✓。
     * 只读操作，不动任何库存；物料缺货时 serialNos 数量 &lt; quantity，由前端如实提示。
     */
    @Override
    public List<Map<String, Object>> buildSnFile(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "调拨单号不能为空");
        }
        TransferOrder order = this.getById(orderNo);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "调拨单不存在");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (TransferOrderItem item : listItems(orderNo)) {
            List<String> sns = terminalMapper.listAvailableSerialNos(
                    order.getFromWarehouse(), item.getMaterialCode(), item.getQuantity());
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("materialCode", item.getMaterialCode());
            row.put("materialName", item.getMaterialName());
            row.put("model", item.getModel());
            row.put("quantity", item.getQuantity());
            row.put("serialNos", sns == null ? new ArrayList<>() : sns);
            result.add(row);
        }
        return result;
    }

    /**
     * 解析时间筛选参数（yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）。
     * endOfDay=true 且只给日期时取当天 23:59:59 —— 让「止」日期包含整天；
     * 解析失败按未传参处理（返回 null），不拦截查询。
     */
    private java.util.Date parseTimeParam(String raw, boolean endOfDay) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim();
        boolean dateOnly = s.length() <= 10;
        try {
            java.util.Date d = new SimpleDateFormat(dateOnly ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm:ss").parse(s);
            if (endOfDay && dateOnly) {
                return new java.util.Date(d.getTime() + 24L * 60 * 60 * 1000 - 1000);
            }
            return d;
        } catch (java.text.ParseException e) {
            return null;
        }
    }
}
