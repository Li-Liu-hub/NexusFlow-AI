# 确定性算法工具箱的单测
#
# 这些测试同时充当**口径说明书**：断言里的数字就是算法的定义。
# 纯函数、无 IO、无网络，可以随时跑。
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tools.algorithms import (
    InventoryRow,
    STRATEGY_PROCURE_ONLY,
    STRATEGY_TRANSFER_ONLY,
    gap,
    plan_replenishment,
    score_brands,
    summarize_aging,
    waterline,
    weekly_usage,
)


# ---------------------------------------------------------------------------
# 文档报表口径（开发文档 B 附录）
# ---------------------------------------------------------------------------

def test_weekly_usage_报表口径():
    # 近三月（90 天）用了 300 台 → 周使用量 = 300/90*7 = 23.33
    assert weekly_usage(300, 90) == 23.33
    # 除零保护
    assert weekly_usage(300, 0) == 0.0


def test_waterline_安全周数():
    # 周使用量 23.33 × 安全 4 周 = 93.32
    assert waterline(23.33, 4) == 93.32


def test_gap_缺口():
    # 需求 100，可用 30，在途 20 → 缺 50
    assert gap(100, 30, 20) == 50
    # 够用则归零，不出负数
    assert gap(10, 30, 20) == 0


# ---------------------------------------------------------------------------
# 分布口径：调拨
# ---------------------------------------------------------------------------

def _row(code, model, available, level=3, aging=100.0, material="M1", name=None):
    return InventoryRow(
        warehouseCode=code,
        warehouseName=name or f"仓{code}",
        warehouseLevel=level,
        materialCode=material,
        model=model,
        materialName=f"{model}物料",
        bigType="光猫",
        availableQty=available,
        totalQty=available,
        avgAgingDays=aging,
    )


def test_富余仓调给短缺仓_量按均值缺口():
    # W1 是地市级(L2)、W2 是区县级(L3)：上级→下级 = TRANSFER
    # 均值 505；短缺判据 < 303 → W2；富余判据 > 707 → W1
    # 缺口 = 505-10 = 495；可调出 = 1000-505 = 495 → 正好调平，不产生采购
    rows = [
        _row("W1", "F657", 1000, level=2, aging=400.0),
        _row("W2", "F657", 10, level=3, aging=30.0),
    ]
    plan = plan_replenishment(rows)

    assert plan.strategy == STRATEGY_TRANSFER_ONLY
    assert len(plan.transfers) == 1
    t = plan.transfers[0]
    assert t.fromWarehouse == "W1"
    assert t.toWarehouse == "W2"
    assert t.quantity == 495
    assert t.operationType == "TRANSFER", "上级→下级应识别为调拨"
    assert plan.procurements == []
    # 长库龄的富余仓要能在理由里体现出来（盘活长库龄是本场景的核心痛点）
    assert "长库龄" in t.reason


def test_下级富余上级不足_收回已下线_缺口转采购():
    """2026-10-08 移除收回单：下级→上级方向不再产出「收回」建议，
    上级仓缺口转入采购兜底（原 RECALL 行为已下线）。"""
    rows = [
        _row("W1", "F657", 10, level=2, aging=30.0),
        _row("W2", "F657", 1000, level=3, aging=400.0),
    ]
    plan = plan_replenishment(rows)
    assert plan.transfers == [], "收回单已下线，下级→上级不应再产出调拨建议"
    assert len(plan.procurements) == 1, "上级仓缺口应转采购兜底"
    p = plan.procurements[0]
    assert p.suggestedQty == 495
    assert p.toWarehouse == "W1"


def test_平级仓之间不产出调拨建议():
    """业务规则：调拨由上级发起调给下级，平级仓之间不能直接调拨。"""
    rows = [
        _row("W1", "F657", 1000, level=3, aging=400.0),
        _row("W2", "F657", 10, level=3, aging=30.0),
    ]
    plan = plan_replenishment(rows)
    assert plan.transfers == [], "平级仓之间不应产出直接调拨建议"


def test_调出仓必须留够水位_不是把货搬空():
    # W1(L2) 有 600，W2(L3) 有 10，均值 305，可调出 = 600-305 = 295
    rows = [
        _row("W1", "F657", 600, level=2),
        _row("W2", "F657", 10, level=3),
    ]
    plan = plan_replenishment(rows)
    assert len(plan.transfers) == 1
    assert plan.transfers[0].quantity == 295
    remaining_in_w1 = 600 - plan.transfers[0].quantity
    assert remaining_in_w1 == 305, "调出后必须留下不低于水位的量"


def test_省内富余不够时转采购():
    # 三仓：W1=5(L2) / W2=3(L3) / W3=0(L3)，均值 2.67
    # 富余判据 > 3.73 → W1(5)；短缺判据 < 1.6 → W3(0)
    # 目标补到 2.67（取整 3），但 W1 最多只能给 int(5-2.67)=2 → 差 1 台转采购
    rows = [
        _row("W1", "F657", 5, level=2),
        _row("W2", "F657", 3, level=3),
        _row("W3", "F657", 0, level=3),
    ]
    plan = plan_replenishment(rows)

    assert plan.strategy in (STRATEGY_PROCURE_ONLY, "mixed")
    assert len(plan.transfers) == 1
    assert plan.transfers[0].quantity == 2
    assert len(plan.procurements) == 1
    assert plan.procurements[0].suggestedQty == 1
    assert "采购" in plan.procurements[0].reason


def test_只有一个仓位时不产出建议():
    # 该型号只有一个仓位有货，仓间无从调剂 —— 宁可不出建议，也不编一条假的
    rows = [_row("W1", "F657", 100)]
    plan = plan_replenishment(rows)
    assert plan.transfers == []
    assert plan.procurements == []


def test_库存都充足时不产出建议():
    rows = [
        _row("W1", "F657", 100, level=2),
        _row("W2", "F657", 95, level=3),
        _row("W3", "F657", 105, level=3),
    ]
    plan = plan_replenishment(rows)
    assert plan.transfers == []
    assert plan.procurements == []


def test_不同型号互不干扰():
    rows = [
        _row("W1", "F657", 1000, level=2, material="M1"),
        _row("W2", "F657", 10, level=3, material="M1"),
        _row("W1", "PT9500", 50, level=2, material="M2"),
        _row("W2", "PT9500", 48, level=3, material="M2"),
    ]
    plan = plan_replenishment(rows)
    models = {t.model for t in plan.transfers}
    assert models == {"F657"}, "库存均衡的型号不应产出任何建议"


def test_trace_留痕不为空():
    rows = [_row("W1", "F657", 1000, level=2), _row("W2", "F657", 10, level=3)]
    plan = plan_replenishment(rows)
    assert any("可用" in line for line in plan.trace)
    assert any("策略" in line for line in plan.trace)


# ---------------------------------------------------------------------------
# 分布口径：粒度与守恒（2026-10-08 修复回归）
# ---------------------------------------------------------------------------

def test_同仓多物料聚合为一行_不产出重复建议():
    """2026-10-08 实测踩中：快照按 仓位×物料×型号 返回，同仓同型号多个物料编码时
    算法把它们当成多个仓，产出 from/to/model 全同的重复建议行（F657×2、B671×3）。"""
    rows = [
        _row("W1", "F657", 1000, level=2, aging=400.0, material="M1"),
        _row("W1", "F657", 600, level=2, aging=200.0, material="M2"),
        _row("W2", "F657", 30, level=3, aging=30.0, material="M1"),
        _row("W2", "F657", 20, level=3, aging=30.0, material="M2"),
    ]
    # 聚合后 W1=1600 / W2=50，均值 825，可调出 775，缺口 775 → 正好调平
    plan = plan_replenishment(rows)

    assert len(plan.transfers) == 1, "同仓多物料必须聚合成一行，不允许重复建议"
    t = plan.transfers[0]
    assert t.fromWarehouse == "W1" and t.toWarehouse == "W2"
    assert t.quantity == 775
    # 物料明细随行携带（建单要拆到物料编码），合计必须守恒
    assert {m["materialCode"] for m in t.materials} == {"M1", "M2"}
    assert sum(m["quantity"] for m in t.materials) == 775


def test_多个富余仓接力调拨_累计不超承诺():
    """可调出量必须随调出递减：修复前 can_give 不扣减，
    同一富余仓会把同一批富余重复承诺给多个短缺仓。
    （2026-10-08 收回单下线：原 L4 富余仓改为 L1，保持上级→下级方向）"""
    rows = [
        _row("W1", "F657", 1000, level=2, aging=400.0),  # 可调出 int(1000-457.5)=542
        _row("W5", "F657", 800, level=1, aging=300.0),   # 可调出 int(800-457.5)=342
        _row("W2", "F657", 10, level=3),                 # 缺 round(447.5)=448
        _row("W3", "F657", 20, level=3),                 # 缺 round(437.5)=438
    ]
    # W1(L2)/W5(L1) 各自同级只有自己 → 都回退全组比较，均值 (1000+800+10+20)/4 = 457.5
    # W1 先出 448（给 W2）再出 94（给 W3），W5 接力 342，W3 剩 2 台缺口转采购
    # （2026-10-08 收回单下线：原 L4 富余仓改为 L1，保持上级→下级的调拨方向）
    plan = plan_replenishment(rows)

    given: dict[str, int] = {}
    for t in plan.transfers:
        given[t.fromWarehouse] = given.get(t.fromWarehouse, 0) + t.quantity
    assert given.get("W1", 0) <= 542, "富余仓累计调出不得超过其可调出量"
    assert given.get("W5", 0) <= 342
    assert sum(given.values()) == 542 + 342, "接力调拨合计 = 各富余仓可调出量之和"
    assert len(plan.procurements) == 1 and plan.procurements[0].suggestedQty == 2


def test_物料分摊_按调出仓实有加权且守恒():
    rows = [
        _row("W1", "F657", 1000, level=2, aging=400.0, material="M1"),
        _row("W1", "F657", 600, level=2, aging=400.0, material="M2"),
        _row("W2", "F657", 50, level=3, aging=30.0, material="M1"),
        _row("W2", "F657", 40, level=3, aging=30.0, material="M2"),
    ]
    # 聚合后 W1=1600 / W2=90，均值 845，可调出 755，缺口 755
    plan = plan_replenishment(rows)

    t = plan.transfers[0]
    assert t.quantity == 755
    # 按调出仓实有量加权（1000:600）：755×1000//1600=471，755×600//1600=283，
    # 余 1 补给实有最大的 M1 → 472 + 283
    assert sorted(m["quantity"] for m in t.materials) == [283, 472]
    assert sum(m["quantity"] for m in t.materials) == t.quantity, "分摊合计必须等于调拨量"


def test_调出仓缺某物料_只分它有的_逐料钳上限():
    """2026-10-08 线上踩中：给调出仓没有的物料也分量（9B00 物料 7402402299
    实际可用 0 却分到 2133），后端逐物料库存校验拒绝，建单全挂。"""
    rows = [
        _row("W1", "F657", 100, level=2, aging=400.0, material="M1"),
        _row("W1", "F657", 10, level=2, aging=400.0, material="M2"),
        _row("W2", "F657", 0, level=3, aging=30.0, material="M1"),
        _row("W2", "F657", 0, level=3, aging=30.0, material="M2"),
        _row("W2", "F657", 0, level=3, aging=30.0, material="M3"),
    ]
    # 聚合后 W1=110 / W2=0，均值 55，可调出 55，缺口 55
    plan = plan_replenishment(rows)

    t = plan.transfers[0]
    codes = {m["materialCode"]: m["quantity"] for m in t.materials}
    assert set(codes) == {"M1", "M2"}, "调出仓没有的物料（M3）不得分摊"
    # 按实有 100:10 加权：55×100//110=50，55×10//110=5，无余量
    assert codes == {"M1": 50, "M2": 5}
    assert all(q <= 100 for q in codes.values()), "单物料分量不得超过调出仓实有量"


def test_无共同物料_跳过该建议并转采购():
    """调出仓与调入仓持有的物料完全不交叉时，建议物理上无法履约 ——
    宁可不出调拨建议（缺口转采购），也不产出一张必然被后端拒绝的单。"""
    rows = [
        _row("W1", "F657", 1000, level=2, aging=400.0, material="MA"),
        _row("W2", "F657", 10, level=3, aging=30.0, material="MB"),
    ]
    plan = plan_replenishment(rows)

    assert plan.transfers == [], "无共同物料不应产出调拨建议"
    assert len(plan.procurements) == 1
    assert plan.procurements[0].suggestedQty == 495, "缺口全额转采购"
    assert any("无共同物料" in line for line in plan.trace)


# ---------------------------------------------------------------------------
# 辅助算法
# ---------------------------------------------------------------------------

def test_库龄摘要():
    aging = [
        {"band": "0-30", "cnt": 10},
        {"band": "180-365", "cnt": 100},
        {"band": "365+", "cnt": 50},
    ]
    s = summarize_aging(aging)
    assert s["total"] == 160
    assert s["longAging"] == 150
    assert s["longAgingRatio"] == round(150 / 160, 4)


def test_品牌评分_库龄低且库存足的排前面():
    stats = [
        {"vendor": "中兴", "availableQty": 1000, "avgAgingDays": 30},
        {"vendor": "友华", "availableQty": 200, "avgAgingDays": 300},
    ]
    scored = score_brands(stats)
    assert scored[0]["vendor"] == "中兴"
    assert scored[0]["score"] > scored[1]["score"]
