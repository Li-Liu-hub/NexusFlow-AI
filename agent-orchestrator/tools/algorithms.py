# 确定性算法工具箱 —— 「算法即工具」的落地
#
# 设计原则（开发文档 M2 关键机制 5）：
#   LLM 只决定**跑哪几种分析、传什么参数**，所有数字计算都在本模块里完成。
#   本模块是纯函数 + 无 IO + 无随机，可独立单测 ——
#   这样"能幻觉的只有选择，不能幻觉事实"。
#
# 口径说明（重要）：
#   开发文档 B 附录给的是**报表口径**：
#       周使用量 = (近三月使用总量 ÷ 天数) × 7
#       水位线   = 周使用量 × 安全周数
#       缺口     = 预测需求 − (可用 + 在途)
#   这套口径需要「领用/出库流水」才能算。当前数据源（terminal 库存快照表）
#   **只有当前状态、没有历史流水**，因此：
#     - 文档口径的函数（weekly_usage / waterline）保留并可直接使用，
#       一旦补上用量流水表即可无缝切换；
#     - 当前默认启用的是**分布口径**（plan_replenishment），
#       它只用库存分布与库龄就能算出可执行的调拨/采购建议，属于真实可算的数字。
#   两套口径共用同一个出口结构，编排层不需要区分。
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Iterable

# ---- 可调参数（对应"参数配置"菜单的语义）----
SAFETY_WEEKS_DEFAULT = 4          # 水位线 = 周使用量 × 安全周数
LONG_AGING_DAYS = 180             # 长库龄阈值（天），与库龄预警报表口径一致
HIGH_AGING_DAYS = 365             # 严重积压阈值（天）
LOW_STOCK_RATIO = 0.6             # 库存低于同级均值的该比例，视为短缺
SURPLUS_RATIO = 1.4               # 库存高于同级均值的该比例，视为富余

PRIORITY_URGENT = "紧急补货"
PRIORITY_NORMAL = "建议补货"

STRATEGY_TRANSFER_ONLY = "transfer_only"
STRATEGY_PROCURE_ONLY = "procure_only"
STRATEGY_MIXED = "mixed"


@dataclass
class InventoryRow:
    """库存聚合行（与后端 TerminalInventoryVO 字段对齐）"""
    warehouseCode: str
    warehouseName: str = ""
    warehouseLevel: int = 0
    materialCode: str = ""
    model: str = ""
    materialName: str = ""
    # 厂商（取 terminal.vendor，如 华为/中兴/烽火）—— 2026-10-12 采购清单表新增「厂商」列
    vendor: str = ""
    bigType: str = ""
    availableQty: int = 0
    totalQty: int = 0
    inTransitQty: int = 0
    avgAgingDays: float = 0.0
    maxAgingDays: int = 0
    # 聚合行专用：同仓同型号的物料明细（由 _aggregate_by_warehouse_model 填充，
    # 原始快照行没有这个字段）。建单需要物料编码粒度，算法比较在仓粒度，
    # 两级粒度靠这个字段衔接。
    materials: list = field(default_factory=list)

    @staticmethod
    def from_dict(d: dict) -> "InventoryRow":
        def _i(key, default=0):
            v = d.get(key)
            return default if v is None else int(v)

        def _f(key, default=0.0):
            v = d.get(key)
            return default if v is None else float(v)

        return InventoryRow(
            warehouseCode=d.get("warehouseCode") or "",
            warehouseName=d.get("warehouseName") or "",
            warehouseLevel=_i("warehouseLevel"),
            materialCode=d.get("materialCode") or "",
            model=d.get("model") or "",
            materialName=d.get("materialName") or "",
            vendor=d.get("vendor") or "",
            bigType=d.get("bigType") or "",
            availableQty=_i("availableQty"),
            totalQty=_i("totalQty"),
            inTransitQty=_i("inTransitQty"),
            avgAgingDays=_f("avgAgingDays"),
            maxAgingDays=_i("maxAgingDays"),
        )


@dataclass
class TransferSuggestion:
    """调拨建议行 —— 对应前端「建议调拨补货清单表」的一行"""
    model: str
    materialCode: str
    materialName: str
    bigType: str
    fromWarehouse: str
    fromWarehouseName: str
    toWarehouse: str
    toWarehouseName: str
    quantity: int
    priority: str
    reason: str
    # 操作类型：由**仓位层级方向**决定，不是随便填的 ——
    #   from 层级 < to 层级（上级→下级）→ TRANSFER 调拨
    #   from 层级 > to 层级（下级→上级）→ RECALL 收回
    # 2026-10-01 e2e 暴露：算法只看库存分布、不看方向，产出了 7AZA(L3)→7A00(L2)
    # 的建议却被后端规则拒（"调拨由上级发起"）—— 方向语义必须在这里就对上。
    operationType: str = "TRANSFER"
    # 物料明细（2026-10-08 补）：一行建议现在对应"仓对 + 型号"，
    # 调拨量按均摊拆到调入仓的各物料编码上（_split_by_materials），
    # 建单时展开成 items，不再依赖单值 materialCode。
    materials: list = field(default_factory=list)


@dataclass
class ProcurementSuggestion:
    """采购建议行 —— 对应前端「建议采购补货清单表」的一行"""
    model: str
    materialCode: str
    materialName: str
    bigType: str
    suggestedBrand: str
    suggestedQty: int
    priority: str
    reason: str
    # 缺口所属仓位（2026-10-07 补）：数据可见范围过滤需要知道"这条建议是给哪个仓的"
    toWarehouse: str = ""
    # 厂商（2026-10-12 新增）：采购清单表「厂商」列取该物料在库终端的实际厂家。
    # 与 suggestedBrand（从「可调拨同型库存」推导的建议品牌，推导不出时为"待定"）不同，
    # 这是型号的客观属性，直接来自库存数据，不存在编造问题。
    vendor: str = ""


@dataclass
class ReplenishmentPlan:
    """补货方案（算法节点的完整输出）"""
    strategy: str
    transfers: list[TransferSuggestion] = field(default_factory=list)
    procurements: list[ProcurementSuggestion] = field(default_factory=list)
    # 留痕：算法跑了哪几步、用了什么参数（供 critic 与审计）
    trace: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "strategy": self.strategy,
            "transfers": [asdict(t) for t in self.transfers],
            "procurements": [asdict(p) for p in self.procurements],
            "trace": self.trace,
        }


# ---------------------------------------------------------------------------
# 文档报表口径（需要用量流水，当前数据源暂不可用，接口先留好）
# ---------------------------------------------------------------------------

def weekly_usage(recent_total: int, days: int) -> float:
    """周使用量 = (近三月使用总量 ÷ 天数) × 7

    开发文档 B 附录第 1 条。days 为 0 时返回 0（不做除零）。
    """
    if days <= 0:
        return 0.0
    return round(recent_total / days * 7, 2)


def waterline(weekly: float, safety_weeks: int = SAFETY_WEEKS_DEFAULT) -> float:
    """水位线 = 周使用量 × 安全周数"""
    return round(weekly * safety_weeks, 2)


def gap(predicted_demand: int, available: int, in_transit: int) -> int:
    """缺口 = 预测需求 − (可用 + 在途)，负数归零"""
    return max(0, int(predicted_demand) - int(available) - int(in_transit))


# ---------------------------------------------------------------------------
# 分布口径（当前默认启用，只用库存 + 库龄，全部是真实可算的数字）
# ---------------------------------------------------------------------------

def _group_by_model(rows: Iterable[InventoryRow]) -> dict[str, list[InventoryRow]]:
    grouped: dict[str, list[InventoryRow]] = {}
    for r in rows:
        if not r.model:
            continue
        grouped.setdefault(r.model, []).append(r)
    return grouped


def _aggregate_by_warehouse_model(rows: Iterable[InventoryRow]) -> list[InventoryRow]:
    """把「仓位 × 物料」粒度的行聚合成「仓位 × 型号」粒度（同仓同型号一行）。

    为什么必须聚合（2026-10-08 实测踩中）：快照接口按 仓位×物料×型号 分组返回
    （TerminalMapper.aggregateInventory 的 GROUP BY），同一型号有多个物料编码时
    同一仓库会返回多行。而均值比较假设"一行 = 一个仓"，不聚合的话：
      - 同一仓以多行身份重复进入 short/surplus → 产出 from/to/model 全同的重复建议行；
      - 均值被物料行数加权（仓的权重 = 物料行数而不是库存量）。
    聚合规则：数量求和；库龄按总量加权平均；物料明细保留在 materials 供建单拆分；
    主物料（materialCode 等单值字段）取可用量最大的那个，供展示与采购口径沿用。
    """
    merged: dict[tuple[str, str], InventoryRow] = {}
    for r in rows:
        if not r.model:
            continue
        key = (r.warehouseCode, r.model)
        agg = merged.get(key)
        if agg is None:
            merged[key] = InventoryRow(
                warehouseCode=r.warehouseCode,
                warehouseName=r.warehouseName,
                warehouseLevel=r.warehouseLevel,
                materialCode=r.materialCode,
                model=r.model,
                materialName=r.materialName,
                # 2026-10-12：聚合行必须显式带上 vendor —— 聚合行是**新建**的 InventoryRow，
                # 不写在这里就会在本环节静默丢失（采购清单「厂商」列取不到值的真实原因）
                vendor=r.vendor,
                bigType=r.bigType,
                availableQty=r.availableQty,
                totalQty=r.totalQty,
                inTransitQty=r.inTransitQty,
                avgAgingDays=r.avgAgingDays,
                maxAgingDays=r.maxAgingDays,
                materials=[{
                    "materialCode": r.materialCode,
                    "materialName": r.materialName,
                    "bigType": r.bigType,
                    "availableQty": r.availableQty,
                }] if r.materialCode else [],
            )
            continue
        w = agg.totalQty + r.totalQty
        if w > 0:
            agg.avgAgingDays = round(
                (agg.avgAgingDays * agg.totalQty + r.avgAgingDays * r.totalQty) / w, 1)
        else:
            agg.avgAgingDays = max(agg.avgAgingDays, r.avgAgingDays)
        agg.availableQty += r.availableQty
        agg.totalQty += r.totalQty
        agg.inTransitQty += r.inTransitQty
        agg.maxAgingDays = max(agg.maxAgingDays, r.maxAgingDays)
        if r.materialCode:
            agg.materials.append({
                "materialCode": r.materialCode,
                "materialName": r.materialName,
                "bigType": r.bigType,
                "availableQty": r.availableQty,
            })

    for agg in merged.values():
        if agg.materials:
            dom = max(agg.materials, key=lambda m: m.get("availableQty") or 0)
            agg.materialCode = dom["materialCode"]
            agg.materialName = dom.get("materialName") or ""
            agg.bigType = dom.get("bigType") or ""
    return list(merged.values())


def _split_by_materials(qty: int,
                        src_materials: list[dict],
                        tgt_materials: list[dict]) -> list[dict]:
    """把调拨量拆到具体物料上 —— 按**调出仓实有**加权，逐物料钳制上限。

    为什么必须看调出仓（2026-10-08 线上踩中）：只按调入仓的物料结构分摊，
    会给调出仓没有的物料也分量（9B00 的 F657 聚合 8759 台，但物料 7402402299
    实际可用 0），后端逐物料库存校验（countAvailable）必然拒绝，建单全挂。
    规则：
      1. 只分**两边都持有**的物料（调出仓有货 × 调入仓要这个料）；
      2. 按调出仓实有量加权 —— 有什么发什么，不为调入仓编造调出仓没有的料；
      3. 单物料分量 ≤ 调出仓实有量（与后端建单校验同口径）；
      4. 余量按实有量从大到小补齐，保证 sum(明细) == 建议量（守恒）。
    两边没有共同物料 → 返回空表，调用方跳过该建议（物理上无法履约）。
    分物料需求口径建立后（开发文档 B 附录报表口径），在这里替换即可。
    """
    have = {m.get("materialCode") or "": int(m.get("availableQty") or 0)
            for m in (src_materials or [])}
    have.pop("", None)
    common = [m for m in (tgt_materials or [])
              if have.get(m.get("materialCode") or "", 0) > 0]
    if not common:
        return []
    qty = int(qty)
    total_w = sum(have[m["materialCode"]] for m in common)
    parts: dict[str, dict] = {}
    for m in common:
        code = m["materialCode"]
        give = min(qty * have[code] // total_w, have[code])
        parts[code] = {"materialCode": code,
                       "materialName": m.get("materialName") or "",
                       "bigType": m.get("bigType") or "",
                       "quantity": give}
    rest = qty - sum(p["quantity"] for p in parts.values())
    for m in sorted(common, key=lambda x: -have[x["materialCode"]]):
        if rest <= 0:
            break
        p = parts[m["materialCode"]]
        add = min(rest, have[p["materialCode"]] - p["quantity"])
        if add > 0:
            p["quantity"] += add
            rest -= add
    return [p for p in parts.values() if p["quantity"] > 0]


def _match_level(rows: list[InventoryRow], level: int) -> list[InventoryRow]:
    """同层优先：拿同级仓位的分布做比较，避免拿省仓和网格仓直接比。

    同级**少于两个**仓位时回退用全部行 —— 否则均值等于自身，
    会产出「自己调给自己」的荒谬建议（2026-10-01 实测踩中：
    快照里 7A00 是 L2、7AZA 是 L3，7A00 的同级组只有它自己）。
    """
    same = [r for r in rows if r.warehouseLevel == level]
    return same if len(same) >= 2 else rows


def plan_replenishment(rows: Iterable[InventoryRow],
                       safety_weeks: int = SAFETY_WEEKS_DEFAULT,
                       max_transfer_per_model: int = 3) -> ReplenishmentPlan:
    """基于库存分布生成补货方案。

    逻辑（逐型号）：
      0. 先把「仓位 × 物料」粒度聚合成「仓位 × 型号」粒度 —— 快照接口按物料编码
         分组返回，同仓同型号多行时不聚合会把同一仓当成多个仓重复比较
         （2026-10-08 实测踩中：同一 from/to/model 产出多条重复建议行）
      1. 取该型号在各仓位的可用量，按**同层级**算出均值
      2. 可用量 < 均值 × LOW_STOCK_RATIO  → 短缺仓（需要补）
      3. 可用量 > 均值 × SURPLUS_RATIO 且库龄高 → 富余仓（可以调出）
      4. 富余仓的可用量按缺口大小顺序填补短缺仓（可调出量随调出递减，
         不允许把同一批富余重复承诺给多个仓）；填不满的部分转采购
      5. 全省都无富余 → procure_only；两者都有 → mixed；只有调拨 → transfer_only

    返回的每个数字都直接来自入参，不做任何推测性放大。
    """
    plan = ReplenishmentPlan(strategy=STRATEGY_TRANSFER_ONLY)
    rows = _aggregate_by_warehouse_model([r for r in rows if isinstance(r, InventoryRow)])
    grouped = _group_by_model(rows)

    for model, group in grouped.items():
        level = group[0].warehouseLevel
        peers = _match_level(group, level)
        if len(peers) < 2:
            # 只有一个仓位供该型号，谈不上仓间调剂 —— 不产出建议（比编一条假建议诚实）
            continue

        avg_available = sum(r.availableQty for r in peers) / len(peers)
        if avg_available <= 0:
            continue

        short = [r for r in peers if r.availableQty < avg_available * LOW_STOCK_RATIO]
        surplus = [r for r in peers
                   if r.availableQty > avg_available * SURPLUS_RATIO
                   and r.availableQty > 0]
        if not short:
            continue

        # 富余仓排序：库龄越长越优先调出（盘活长库龄是本次场景的核心痛点）
        surplus.sort(key=lambda r: (-r.avgAgingDays, -r.availableQty))

        # 剩余可调出量，随调出递减（2026-10-08 修复：此前 can_give 不扣减，
        # 同一富余仓会把同一批富余重复承诺给多个短缺仓，累计调出量超过实有）
        giveable = {src.warehouseCode: int(src.availableQty - avg_available)
                    for src in surplus}

        for target in sorted(short, key=lambda r: r.availableQty):
            need = int(round(avg_available - target.availableQty))
            if need <= 0:
                continue
            plan.trace.append(
                f"{model}@{target.warehouseCode} 可用 {target.availableQty} < 同级均值 "
                f"{avg_available:.1f}，缺口 {need}"
            )
            remaining = need
            picked = 0
            for src in surplus:
                if remaining <= 0 or picked >= max_transfer_per_model:
                    break
                # 同仓不自调（防线②：即使上游比较基准退化，也不产出荒谬建议）
                if src.warehouseCode == target.warehouseCode:
                    continue
                # 富余仓自己也要留够水位：最多调出"高出均值的那部分"（随调出递减）
                can_give = giveable.get(src.warehouseCode, 0)
                if can_give <= 0:
                    continue
                qty = min(can_give, remaining)
                if qty <= 0:
                    continue
                reason_bits = [
                    f"可用 {src.availableQty} 高于同级均值 {avg_available:.1f}",
                ]
                if src.avgAgingDays >= LONG_AGING_DAYS:
                    reason_bits.append(f"平均库龄 {src.avgAgingDays:.0f} 天（长库龄，优先盘活）")

                # 操作类型：只保留上级→下级的调拨（2026-10-08 移除收回单：
                # 下级→上级的「收回」建议不再产出，上级仓缺口由「转采购」兜底）
                if src.warehouseLevel == target.warehouseLevel:
                    # 平级仓之间不能直接调拨（业务规则：调拨由上级发起、调给下级）
                    continue
                if src.warehouseLevel > target.warehouseLevel:
                    continue

                # 物料分摊看调出仓实有：分不出可履约的明细就跳过（2026-10-08 修复）
                alloc = _split_by_materials(qty, src.materials, target.materials)
                if not alloc:
                    plan.trace.append(
                        f"  → 跳过 {src.warehouseCode}→{target.warehouseCode}"
                        f"（{model}）：两边无共同物料，无法履约")
                    continue
                qty = sum(m["quantity"] for m in alloc)

                plan.transfers.append(TransferSuggestion(
                    model=model,
                    materialCode=src.materialCode or target.materialCode,
                    materialName=src.materialName or target.materialName,
                    bigType=src.bigType or target.bigType,
                    fromWarehouse=src.warehouseCode,
                    fromWarehouseName=src.warehouseName,
                    toWarehouse=target.warehouseCode,
                    toWarehouseName=target.warehouseName,
                    quantity=qty,
                    priority=PRIORITY_URGENT if target.availableQty == 0 else PRIORITY_NORMAL,
                    reason="；".join(reason_bits),
                    operationType="TRANSFER",
                    materials=alloc,
                ))
                remaining -= qty
                giveable[src.warehouseCode] -= qty
                picked += 1
                plan.trace.append(
                    f"  → 从 {src.warehouseCode} 调出 {qty} 台到 {target.warehouseCode}"
                )

            if remaining > 0:
                # 省内富余不足以填平缺口 → 转采购
                brand = pick_brand(peers)
                plan.procurements.append(ProcurementSuggestion(
                    model=model,
                    materialCode=target.materialCode,
                    materialName=target.materialName,
                    vendor=target.vendor,
                    bigType=target.bigType,
                    suggestedBrand=brand,
                    suggestedQty=remaining,
                    priority=PRIORITY_URGENT,
                    reason=f"省内可调拨库存不足，缺口剩余 {remaining} 台需采购",
                    toWarehouse=target.warehouseCode,
                ))
                plan.trace.append(
                    f"  → {target.warehouseCode} 剩余缺口 {remaining} 台转采购（建议品牌 {brand}）"
                )

    # strategy 三态
    has_t, has_p = bool(plan.transfers), bool(plan.procurements)
    if has_t and has_p:
        plan.strategy = STRATEGY_MIXED
    elif has_p:
        plan.strategy = STRATEGY_PROCURE_ONLY
    else:
        plan.strategy = STRATEGY_TRANSFER_ONLY

    plan.trace.insert(0, f"算法：分布口径（安全周数参数 {safety_weeks} 仅留痕，未参与本口径计算）")
    plan.trace.append(
        f"结果：调拨 {len(plan.transfers)} 条 / 采购 {len(plan.procurements)} 条 / 策略 {plan.strategy}"
    )
    return plan


def pick_brand(rows: list[InventoryRow]) -> str:
    """给采购建议挑一个建议品牌。

    诚实说明：当前后端聚合结果里**没有厂家维度**（terminal 表有 vendor 字段，
    但 aggregateInventory 未按厂家分组返回），所以这里返回空串，
    由报表节点在卡片上显示"待定"。

    若要让本函数产出真实品牌，需要两步：
      1. 后端聚合 SQL 增加 vendor 分组（一行改动）；
      2. 用 score_brands() 的评分口径排序取第一名。
    这里不返回编造的品牌名。
    """
    return ""


def score_brands(vendor_stats: list[dict]) -> list[dict]:
    """品牌评分：按厂家的库存周转潜力和库龄给出排序建议。

    vendor_stats 每项形如 {"vendor": "中兴", "availableQty": 1200, "avgAgingDays": 210}
    评分口径（0~100）：
      - 库龄越低越好（权重 60%）：以 365 天为最差基准线性扣分
      - 可用库存越充足越好（权重 40%）：以该组最大库存为基准
    纯启发式，输出用于"建议采购品牌"的排序参考，不作为决策依据。
    """
    if not vendor_stats:
        return []
    max_qty = max(int(v.get("availableQty") or 0) for v in vendor_stats) or 1
    scored = []
    for v in vendor_stats:
        qty = int(v.get("availableQty") or 0)
        aging = float(v.get("avgAgingDays") or 0)
        aging_score = max(0.0, 100.0 * (1 - min(aging, 365) / 365))
        qty_score = 100.0 * qty / max_qty
        score = round(aging_score * 0.6 + qty_score * 0.4, 1)
        scored.append({
            "vendor": v.get("vendor"),
            "availableQty": qty,
            "avgAgingDays": aging,
            "score": score,
        })
    scored.sort(key=lambda x: -x["score"])
    return scored


def summarize_aging(aging_rows: list[dict]) -> dict:
    """库龄摘要：把后端的分桶结果压成算法节点需要的几个数字。"""
    total = 0
    long_aging = 0
    for row in aging_rows or []:
        cnt = int(row.get("cnt") or 0)
        total += cnt
        band = row.get("band")
        if band in ("180-365", "365+"):
            long_aging += cnt
    return {
        "total": total,
        "longAging": long_aging,
        "longAgingRatio": round(long_aging / total, 4) if total else 0.0,
    }
