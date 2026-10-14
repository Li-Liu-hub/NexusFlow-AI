# 告警闭环图测试：scan → 判定 → notify 的状态流转与失败纪律
#
# 编排层承担告警闭环后（2026-10-07），这里测的是：
#   ① 发现清单 → 通知投递的映射；② 失败不中断整轮；③ 巡检失败要显式进摘要。
# agent 侧的原子接口另有 E2E 覆盖（stock-alert-closed-loop-e2e.py）。
import sys
from dataclasses import asdict
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import alert_graph
from alert_graph import build_alert_graph, run_alert_scan
from tools.algorithms import InventoryRow, TransferSuggestion
from tools.terminal_api import TerminalApiError


def _finding(code="9BZA", title="库存告警：东宁市分公司 有 2 条调拨建议待处理"):
    return {
        "warehouseCode": code,
        "warehouseName": "东宁市分公司",
        "suggestionCount": 2,
        "notice": {
            "receiver": code + ":ACCOUNT_MANAGER",
            "noticeType": "STOCK_ALERT",
            "title": title,
            "content": "库龄结构摘要\n首要建议：F657 光猫F657，从 9B00 调入 30 台",
        },
    }


class TestAlertGraph:
    def test_topology(self):
        nodes = set(build_alert_graph().get_graph().nodes)
        assert {"scan", "judge", "notify", "summarize"} <= nodes

    def test_happy_path_inserts_notice(self):
        with patch.object(alert_graph, "visible_scope", return_value={"9BZA"}) as scope, \
             patch.object(alert_graph, "fetch_snapshot", return_value={"inventory": []}), \
             patch.object(alert_graph, "plan_replenishment", return_value=object()), \
             patch.object(alert_graph, "_findings_from_plan", return_value=[_finding()]), \
             patch.object(alert_graph, "upsert_notice",
                          return_value={"action": "inserted", "id": 11}) as upsert:
            summary = run_alert_scan("9BZA")

        scope.assert_called_once_with("9BZA")   # 指定仓原样传入可见范围计算
        upsert.assert_called_once()
        assert upsert.call_args.kwargs["receiver"] == "9BZA:ACCOUNT_MANAGER"
        assert summary["scanned"] == 1
        assert summary["notified"] == 1 and summary["updated"] == 0
        assert summary["warehouses"] == ["9BZA"]
        assert summary["ranAt"]

    def test_second_round_refreshes_instead_of_adding(self):
        """未读告警再巡检一轮 → 落库端返回 updated（只刷新不新增）。"""
        with patch.object(alert_graph, "visible_scope", return_value={"9BZA"}), \
             patch.object(alert_graph, "fetch_snapshot", return_value={"inventory": []}), \
             patch.object(alert_graph, "plan_replenishment", return_value=object()), \
             patch.object(alert_graph, "_findings_from_plan", return_value=[_finding()]), \
             patch.object(alert_graph, "upsert_notice",
                          return_value={"action": "updated", "id": 11}):
            summary = run_alert_scan()
        assert summary["updated"] == 1 and summary["notified"] == 0

    def test_scan_failure_is_reported_not_raised(self):
        """巡检失败要让调用方看到原因（定时日志/门户提示），而不是抛异常。"""
        with patch.object(alert_graph, "visible_scope", return_value=None), \
             patch.object(alert_graph, "fetch_snapshot",
                          side_effect=TerminalApiError("网关 403")):
            summary = run_alert_scan()
        assert "403" in summary["scanError"]
        assert summary["notified"] == 0 and summary["failed"] == 0

    def test_notify_failure_does_not_stop_other_warehouses(self):
        findings = [_finding("9BZA"), _finding("9B00", "库存告警：另一仓")]
        with patch.object(alert_graph, "visible_scope", return_value=None), \
             patch.object(alert_graph, "fetch_snapshot", return_value={"inventory": []}), \
             patch.object(alert_graph, "plan_replenishment", return_value=object()), \
             patch.object(alert_graph, "_findings_from_plan", return_value=findings), \
             patch.object(alert_graph, "upsert_notice",
                          side_effect=[TerminalApiError("落库失败"),
                                       {"action": "inserted", "id": 12}]):
            summary = run_alert_scan()

        assert summary["notified"] == 1        # 第二条照常投递
        assert summary["failed"] == 1
        assert any("9BZA" in e for e in summary["errors"])

    def test_incomplete_draft_is_dropped_with_trace(self):
        """草稿缺标题 → 丢弃并留痕，绝不写出一条空白通知。"""
        bad = _finding(title="")
        with patch.object(alert_graph, "visible_scope", return_value={"9BZA"}), \
             patch.object(alert_graph, "fetch_snapshot", return_value={"inventory": []}), \
             patch.object(alert_graph, "plan_replenishment", return_value=object()), \
             patch.object(alert_graph, "_findings_from_plan", return_value=[bad]), \
             patch.object(alert_graph, "upsert_notice") as upsert:
            summary = run_alert_scan()

        upsert.assert_not_called()
        assert summary["failed"] == 1
        assert any("跳过" in e for e in summary["errors"])

    def test_scan_uses_unified_plan_algorithm(self):
        """口径统一（2026-10-07）：scan 用 plan_replenishment 的真实产出生成发现与通知草稿。"""
        rows = [InventoryRow(warehouseCode="9BZA", warehouseName="东宁市新区分公司",
                             model="F657", materialCode="7471502303", availableQty=29),
                InventoryRow(warehouseCode="9B00", warehouseName="东宁市分公司",
                             model="F657", materialCode="7471502303", availableQty=835)]
        plan = SimpleNamespace(
            transfers=[TransferSuggestion(model="F657", materialCode="7471502303",
                                          materialName="光猫F657", bigType="",
                                          fromWarehouse="9B00", fromWarehouseName="东宁市分公司",
                                          toWarehouse="9BZA", toWarehouseName="东宁市新区分公司",
                                          quantity=100, priority="", reason="")],
            procurements=[])
        with patch.object(alert_graph, "visible_scope", return_value={"9BZA"}), \
             patch.object(alert_graph, "fetch_snapshot",
                          return_value={"inventory": [asdict(r) for r in rows]}), \
             patch.object(alert_graph, "plan_replenishment", return_value=plan) as algo, \
             patch.object(alert_graph, "upsert_notice",
                          return_value={"action": "inserted", "id": 1}) as upsert:
            summary = run_alert_scan("9BZA")

        algo.assert_called_once()                       # 算法被真实调用（非打桩产出）
        assert summary["scanned"] == 1 and summary["warehouses"] == ["9BZA"]
        notice = upsert.call_args.kwargs
        assert notice["receiver"] == "9BZA:ACCOUNT_MANAGER"
        assert "1 条调拨建议" in notice["content"] and "F657" in notice["content"]