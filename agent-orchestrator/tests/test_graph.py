# 编排图测试：拓扑、路由分流、数字闸门、建议缓存、三通道端到端
#
# 图里的外部依赖（LLM、网关接口）在测试中全部被 patch 掉 ——
# 这一层测的是**图的骨架与状态流转**，不是外部服务本身。
# 外部服务另有 e2e 脚本验证（local-init/e2e-test.mjs）。
import sys
import time
from datetime import date
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import graph
from graph import (
    KB_CHANNEL,
    RouteDecision,
    build_graph,
    load_suggestion,
    route_after_critic,
    route_after_supervise,
    save_suggestion,
    verify_numbers,
)
from tools.algorithms import InventoryRow


# ---------------------------------------------------------------------------
# 拓扑
# ---------------------------------------------------------------------------

class TestTopology:
    def test_business_nodes_present(self):
        nodes = set(build_graph().get_graph().nodes)
        expected = {"supervise", "kb_node", "chart_plan", "chart_build", "fetch",
                    "algorithm", "report", "verify_numbers", "order", "critic", "respond"}
        assert expected <= nodes, f"缺失节点：{expected - nodes}"

    def test_router_can_be_disabled(self):
        nodes = set(build_graph(router_enabled=False).get_graph().nodes)
        assert "supervise" not in nodes
        assert "kb_node" in nodes, "关掉路由时仍要能落到 kb 通道"

    def test_critic_can_be_disabled(self):
        nodes = set(build_graph(critic_enabled=False).get_graph().nodes)
        assert "critic" not in nodes
        assert "verify_numbers" in nodes, "关质检也要保留数字自检"


# ---------------------------------------------------------------------------
# 路由分流
# ---------------------------------------------------------------------------

class TestRouting:
    def test_supervise_three_routes(self):
        """意图识别只有三类：chart / replenish / kb；order 不是意图，是补货线的按钮落点。"""
        # 问答意图（kb）在路由处被改写为 KB_CHANNEL 指向的节点 —— 数字员工接管后即 digital_human
        assert route_after_supervise({"route": "kb"}) == KB_CHANNEL
        assert route_after_supervise({"route": "chart"}) == "chart"
        assert route_after_supervise({"route": "replenish"}) == "replenish"
        # order 仅由 suggestion_id 特判到达（防御性保留映射，正常不会由路由标签产生）
        assert route_after_supervise({"route": "order"}) == "order"

    def test_order_is_not_an_intent_label(self):
        """回归锚点：路由提示词不得把 order 当分类标签（2026-10-07 用户纠正）。"""
        assert "输出：order" not in graph.ROUTE_PROMPT
        assert "chart 或 replenish 或 kb" in graph.ROUTE_PROMPT

    def test_stray_order_label_normalized_to_replenish(self):
        """模型若仍吐旧标签 order → 归一为 replenish（先给建议卡与按钮，点按钮才建单）。"""
        with patch.object(graph, "get_llm", side_effect=lambda **kw: _fake_llm("order")), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            out = graph.supervise({"query": "帮我从长春调 50 台 F657 到九台"})
        assert out["route"] == "replenish"

    def test_supervise_uses_llm_only_no_keyword_override(self):
        """2026-10-08 用户要求：意图识别用 AI，不走关键词 —— 模型结果不再被关键词表覆盖。

        旧实现里「报表」会命中 _keyword_route 把模型判断改成 chart；现在必须听模型的。
        """
        assert not hasattr(graph, "_keyword_route"), "关键词路由兜底应已移除"
        with patch.object(graph, "get_llm", side_effect=lambda **kw: _fake_llm("kb")), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            out = graph.supervise({"query": "给我出一份库存报表"})
        assert out["route"] == "kb"
        assert "关键词" not in out["meta"]["route_reason"]

    def test_suggestion_id_bypasses_llm_route(self):
        """两段式第二段：带 suggestion_id 回来 = 用户已确认建议，直接进建单节点。"""
        assert route_after_supervise({"suggestion_id": "sug_x", "route": "kb"}) == "order"
        assert route_after_supervise({"meta": {"suggestion_id": "sug_x"}}) == "order"

    def test_supervise_short_circuits_on_suggestion_id(self):
        """2026-10-08 提速修复：按钮点击是协议动作，路由确定性直达 order，
        supervise 不得再烧 LLM（实测一次路由 8~17s，是「申请调拨」慢的元凶）。"""
        llm_calls = []

        def _spy_llm(**kw):
            llm_calls.append(kw)
            raise AssertionError("带 suggestion_id 的请求不应触发 LLM")

        with patch.object(graph, "get_llm", side_effect=_spy_llm), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            out = graph.supervise({"query": "按建议建单",
                                   "meta": {"suggestion_id": "sug_x"}})
        assert out["route"] == "order"
        assert llm_calls == [], "短路路径不得调用 LLM"
        assert "免 LLM" in out["meta"]["route_reason"]

    def test_supervise_defaults_to_kb(self):
        # 防御：route 意外缺失时不应崩，落到最安全的问答通道
        assert route_after_supervise({}) == KB_CHANNEL

    def test_kb_channel_is_switchable(self):
        """「数字员工接管问答」的可回退开关：KB_CHANNEL 只能是这两个值之一。
        改回 "kb" 即恢复旧的 Java RAG 通道，路由提示词无需改动。"""
        assert KB_CHANNEL in ("kb", "digital_human")

    def test_critic_pass_goes_respond(self):
        assert route_after_critic({"critic_feedback": ""}) == "respond"

    def test_number_gate_triggers_rework(self):
        s = {"critic_feedback": "调拨行数量不符", "critic_gate": "number", "revision_count": 0}
        assert route_after_critic(s) == "rework"

    def test_data_gate_triggers_rework(self):
        s = {"critic_feedback": "取数失败", "critic_gate": "data", "revision_count": 0}
        assert route_after_critic(s) == "rework"

    def test_semantic_gate_degrades_instead_of_rework(self):
        # kb / order 的语义不合格没有可整改的检索产物，回炉收益低 —— 直接放行留痕
        s = {"critic_feedback": "不够详细", "critic_gate": "semantic", "revision_count": 0}
        assert route_after_critic(s) == "respond"

    def test_max_revisions_forces_degrade(self):
        s = {"critic_feedback": "数字不符", "critic_gate": "number", "revision_count": 2}
        assert route_after_critic(s) == "respond"


# ---------------------------------------------------------------------------
# 数字闸门（verify_numbers）—— 数字红线的第一道防线
# ---------------------------------------------------------------------------

def _algo_with_transfer(qty=10):
    return {
        "transfers": [{"fromWarehouse": "7A00", "toWarehouse": "7AZA",
                       "model": "F657", "materialCode": "M1", "quantity": qty}],
        "procurements": [{"model": "PT9500", "materialCode": "M2", "suggestedQty": 5}],
    }


class TestVerifyNumbers:
    def test_passes_when_card_matches_algo(self):
        state = {
            "algo_result": _algo_with_transfer(10),
            "cards": [
                {"type": "transfer_list", "totalQuantity": 10,
                 "rows": [{"fromWarehouse": "7A00", "toWarehouse": "7AZA",
                           "model": "F657", "quantity": 10}]},
                {"type": "procurement_list", "totalQuantity": 5,
                 "rows": [{"model": "PT9500", "suggestedQty": 5}]},
            ],
        }
        out = verify_numbers(state)
        assert out["meta"]["verify_passed"] is True
        assert out["meta"]["verify_violations"] == []

    def test_detects_quantity_mismatch(self):
        state = {
            "algo_result": _algo_with_transfer(10),
            "cards": [{"type": "transfer_list", "totalQuantity": 99,
                       "rows": [{"fromWarehouse": "7A00", "toWarehouse": "7AZA",
                                 "model": "F657", "quantity": 99}]}],
        }
        out = verify_numbers(state)
        assert out["meta"]["verify_passed"] is False
        joined = " ".join(out["meta"]["verify_violations"])
        assert "99" in joined and "10" in joined

    def test_detects_phantom_row(self):
        """卡片里出现算法没算过的行 —— 典型的 LLM 幻觉，必须拦住"""
        state = {
            "algo_result": _algo_with_transfer(10),
            "cards": [{"type": "transfer_list", "totalQuantity": 10,
                       "rows": [{"fromWarehouse": "7A00", "toWarehouse": "7AZA",
                                 "model": "F657", "quantity": 10},
                                {"fromWarehouse": "9999", "toWarehouse": "7AZA",
                                 "model": "GHOST", "quantity": 0}]}],
        }
        out = verify_numbers(state)
        assert out["meta"]["verify_passed"] is False
        assert any("不存在" in v for v in out["meta"]["verify_violations"])


# ---------------------------------------------------------------------------
# 建议缓存（两段式人工确认的凭据）
# ---------------------------------------------------------------------------

class TestSuggestionCache:
    def test_save_and_load(self):
        sid = save_suggestion({"strategy": "transfer_only", "transfers": []})
        assert sid.startswith("sug_")
        assert load_suggestion(sid)["strategy"] == "transfer_only"

    def test_unknown_id_returns_none(self):
        assert load_suggestion("sug_not_exist") is None

    def test_expired_suggestion_returns_none(self, monkeypatch):
        sid = save_suggestion({"strategy": "x"})
        monkeypatch.setattr(graph, "_SUGGESTION_TTL_SECONDS", 0)
        # 把 _saved_at 往回拨 1 秒来模拟过期 —— 不能指望"连续两次 time.time() 有差异"：
        # Windows 上 time.time() 分辨率约 15.6ms，紧挨着的两次调用会返回同一个值
        graph._SUGGESTIONS[sid]["_saved_at"] = time.time() - 1
        assert load_suggestion(sid) is None


# ---------------------------------------------------------------------------
# 三通道端到端（外部依赖全部 mock）
# ---------------------------------------------------------------------------

def _fake_llm(route: str):
    """伪造 get_llm：结构化输出按 schema 返回，普通调用返回固定文本。"""
    llm = MagicMock()

    def _with_structured_output(schema, **kwargs):
        holder = MagicMock()

        def _invoke(prompt):
            if schema is RouteDecision:
                return RouteDecision(route=route, reason="测试固定路由")
            # critic 的 CriticVerdict
            return schema(passed=True, reason="ok")

        holder.invoke.side_effect = _invoke
        return holder

    llm.with_structured_output.side_effect = _with_structured_output
    # 路由已改为纯文本解析（MiMo 的 function calling 不稳），
    # 所以 invoke 的返回必须能被解析出路由词，否则会走"3 次失败 → 降级 kb"分支，
    # 进而尝试真连网关导致测试失败（2026-10-01 修）
    llm.invoke.return_value = MagicMock(content=route)
    return llm


SNAPSHOT = {
    "generatedAt": "2026-10-01 01:00:00",
    "totalTerminals": 100,
    "totalAvailable": 60,
    "totalInTransit": 0,
    "longAgingCount": 50,
    "inventory": [
        # 两仓同级、极端不均衡 → 必然产生一条调拨建议
        {"warehouseCode": "7A00", "warehouseName": "长春市分公司", "warehouseLevel": 2,
         "materialCode": "M1", "model": "F657", "materialName": "光猫F657", "bigType": "光猫",
         "availableQty": 1000, "totalQty": 1000, "inTransitQty": 0,
         "avgAgingDays": 400.0, "maxAgingDays": 500},
        {"warehouseCode": "7AZA", "warehouseName": "九台区分公司", "warehouseLevel": 3,
         "materialCode": "M1", "model": "F657", "materialName": "光猫F657", "bigType": "光猫",
         "availableQty": 10, "totalQty": 10, "inTransitQty": 0,
         "avgAgingDays": 30.0, "maxAgingDays": 40},
    ],
    "aging": [{"band": "365+", "cnt": 50, "availableQty": 30, "sortNo": 4}],
    "byModel": [],
}


class _Sink:
    """冒充 get_stream_writer 的返回（一个可调用对象），捕获终态载荷。"""

    def __init__(self):
        self.result = None
        self.steps = []

    def __call__(self, payload):
        if isinstance(payload, dict) and "__result__" in payload:
            self.result = payload["__result__"]
        else:
            self.steps.append(payload)


class TestReplenishChannel:
    def test_replenish_produces_transfer_card(self):
        sink = _Sink()
        with patch.object(graph, "get_llm", side_effect=lambda **kw: _fake_llm("replenish")), \
             patch.object(graph, "fetch_snapshot", return_value=SNAPSHOT), \
             patch.object(graph, "get_stream_writer", return_value=sink):
            final = build_graph().invoke(
                {"query": "给我一份补货建议", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        assert final["route"] == "replenish"
        assert final["strategy"] == "transfer_only"

        types = [c["type"] for c in final["cards"]]
        assert "replenish_reminder" in types
        assert "transfer_list" in types

        transfer_card = next(c for c in final["cards"] if c["type"] == "transfer_list")
        # 1000 与 10 两仓，均值 505 → 缺口 495
        assert transfer_card["totalQuantity"] == 495
        assert transfer_card["rows"][0]["quantity"] == 495

        # 数字自检必须通过（卡片数字 === 算法输出）
        assert final["meta"]["verify_passed"] is True
        # 建议 id 要能被 order 通道取回
        assert load_suggestion(final["suggestion_id"]) is not None
        # respond 组装的终态载荷
        assert sink.result is not None
        assert sink.result["route"] == "replenish"
        assert sink.result["cards"]

    def test_fetch_failure_does_not_crash_graph(self):
        from tools.terminal_api import TerminalApiError

        with patch.object(graph, "get_llm", side_effect=lambda **kw: _fake_llm("replenish")), \
             patch.object(graph, "fetch_snapshot", side_effect=TerminalApiError("网关 403")), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "补货建议", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        # 取数失败时图仍要跑完并给出可读结果（不抛异常）
        assert final["meta"]["fetch_ok"] is False
        assert "网关 403" in (final["meta"]["fetch_error"] or "")
        assert final["cards"], "即使没数据也要有提醒卡片"


class TestDigitalHumanChannel:
    """数字员工通道：kb 意图现在落到 digital_human 节点（数字员工接管问答）。"""

    def test_forwards_to_digital_human_kernel(self):
        with patch.object(graph, "get_llm", side_effect=lambda **kw: _fake_llm("kb")), \
             patch.object(graph, "digital_human_chat",
                          return_value={"version": 4, "answer": "调拨分四种操作。"}) as m, \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "调拨有哪几种操作", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        assert final["route"] == "kb"  # supervise 的意图标签保持 kb 不变
        assert final["agent_response"] == "调拨分四种操作。"
        assert final["meta"]["channel"] == "digital_human"
        assert final["meta"]["agent_version"] == 4
        m.assert_called_once()

    def test_agent_code_comes_from_meta(self):
        """meta 指定 agent_code 时优先 —— 一个编排层可对接多个数字员工。"""
        with patch.object(graph, "get_llm", side_effect=lambda **kw: _fake_llm("kb")), \
             patch.object(graph, "digital_human_chat",
                          return_value={"version": 1, "answer": "ok"}) as m, \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "你好", "revision_count": 0, "meta": {"agent_code": "custom_bot"}},
                config={"recursion_limit": 20})

        assert m.call_args[0][0] == "custom_bot"
        assert final["meta"]["agent_code"] == "custom_bot"

    def test_degrades_when_kernel_unavailable(self):
        from tools.digital_human_api import DigitalHumanError

        with patch.object(graph, "get_llm", side_effect=lambda **kw: _fake_llm("kb")), \
             patch.object(graph, "digital_human_chat", side_effect=DigitalHumanError("连不上")), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "随便问问", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        assert "数字员工暂时不可用" in final["agent_response"]


class TestChartChannel:
    """图表路线（新增）：LLM 只选图型，数字全部来自取数接口原值搬运。"""

    AGG = [
        {"warehouseCode": "7A00", "warehouseName": "长春市分公司", "availableQty": 1000},
        {"warehouseCode": "7AZA", "warehouseName": "九台区分公司", "availableQty": 10},
    ]

    @staticmethod
    def _fake_llm(chart_plan="warehouse"):
        """supervise 输出 chart；图表选型按用例返回「维度」或「维度 样式」（如 "warehouse pie"）。"""
        llm = MagicMock()

        def _invoke(prompt):
            if "图表选型" in str(prompt):
                return MagicMock(content=chart_plan)
            return MagicMock(content="chart")

        llm.invoke.side_effect = _invoke
        return llm

    def test_warehouse_chart_uses_api_numbers(self):
        sink = _Sink()
        with patch.object(graph, "get_llm", side_effect=lambda **kw: self._fake_llm("warehouse")), \
             patch.object(graph, "aggregate_inventory", return_value=self.AGG), \
             patch.object(graph, "get_stream_writer", return_value=sink):
            final = build_graph().invoke(
                {"query": "看看各仓库存分布", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        assert final["route"] == "chart"
        assert final["meta"]["chart_kind"] == "warehouse"
        card = final["cards"][0]
        assert card["type"] == "chart" and card["chartType"] == "bar"
        assert card["labels"][0] == "长春市分公司"
        assert card["series"][0]["data"][0] == 1000
        assert "1010" in card["note"], "note 必须复述接口原值合计（1000+10）"
        assert sink.result is not None and sink.result["route"] == "chart"

    def test_aging_chart_sums_long_aging(self):
        rows = [{"band": "90-180", "cnt": 5, "sortNo": 2},
                {"band": "365+", "cnt": 50, "sortNo": 4}]
        with patch.object(graph, "get_llm", side_effect=lambda **kw: self._fake_llm("aging")), \
             patch.object(graph, "aging_distribution", return_value=rows), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "库龄分布", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        card = final["cards"][0]
        assert card["labels"] == ["90-180", "365+"]   # 按 sortNo 排序
        assert "长库龄 50 台" in card["note"]

    def test_chart_fetch_failure_degrades_without_cards(self):
        from tools.terminal_api import TerminalApiError

        with patch.object(graph, "get_llm", side_effect=lambda **kw: self._fake_llm("warehouse")), \
             patch.object(graph, "aggregate_inventory", side_effect=TerminalApiError("网关 403")), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "各仓库存分布", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        # 取数失败必须明确说"没取到"，绝不渲染空图冒充"库存为零"
        assert final["cards"] == []
        assert "失败" in final["agent_response"]
        assert final["meta"]["chart_ok"] is False

    # ---- 图型样式（2026-10-07）：模型选「维度 样式」，关键词兜底，stat 恒为统计卡 ----

    def test_pie_style_from_model_output(self):
        with patch.object(graph, "get_llm", side_effect=lambda **kw: self._fake_llm("warehouse pie")), \
             patch.object(graph, "aggregate_inventory", return_value=self.AGG), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "给我画个各仓库存占比饼图", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        assert final["meta"]["chart_style"] == "pie"
        card = final["cards"][0]
        assert card["chartType"] == "pie"
        # 数据仍是接口原值搬运（换的只是呈现方式）
        assert card["series"][0]["data"] == [1000, 10]

    def test_style_keyword_fallback_when_model_omits_style(self):
        # 模型只回维度（未给样式），按用户原话关键词兜底：「占比」→ pie
        with patch.object(graph, "get_llm", side_effect=lambda **kw: self._fake_llm("warehouse")), \
             patch.object(graph, "aggregate_inventory", return_value=self.AGG), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "各仓库存占比怎么样", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        assert final["meta"]["chart_style"] == "pie"
        assert final["cards"][0]["chartType"] == "pie"

    def test_stat_style_forced_to_metric(self):
        # stat 是四个总数，没有分布形态：模型给的 pie 被忽略，恒为统计卡
        snap = {"totalTerminals": 1010, "totalAvailable": 1000,
                "totalInTransit": 5, "longAgingCount": 50}
        with patch.object(graph, "get_llm", side_effect=lambda **kw: self._fake_llm("stat pie")), \
             patch.object(graph, "fetch_snapshot", return_value=snap), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "现在库存总体情况", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        assert final["meta"]["chart_style"] == "metric"
        assert final["cards"][0]["chartType"] == "metric"

    def test_line_note_marks_non_time_series(self):
        rows = [{"model": "F657", "availableQty": 100}, {"model": "F657G", "availableQty": 30}]
        with patch.object(graph, "get_llm", side_effect=lambda **kw: self._fake_llm("model line")), \
             patch.object(graph, "aggregate_inventory", return_value=rows), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "各型号库存趋势", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        card = final["cards"][0]
        assert card["chartType"] == "line"
        # 数据无时间维度：note 必须如实标注非时间序列
        assert "非时间序列" in card["note"]

    def test_table_style_passthrough(self):
        with patch.object(graph, "get_llm", side_effect=lambda **kw: self._fake_llm("warehouse table")), \
             patch.object(graph, "aggregate_inventory", return_value=self.AGG), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "各仓库存明细表格", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        assert final["meta"]["chart_style"] == "table"
        assert final["cards"][0]["chartType"] == "table"

    def test_tail_merged_into_others(self):
        # 超过 Top N：尾巴合并为「其他」，保证饼图/表格占比分母 == 接口原值合计
        rows = [{"warehouseCode": f"W{i:02d}", "warehouseName": f"仓{i}",
                 "availableQty": 100 - i} for i in range(12)]
        with patch.object(graph, "aggregate_inventory", return_value=rows), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            card, note = graph._build_chart("warehouse", "pie", None)

        labels, data = card["labels"], card["series"][0]["data"]
        assert len(labels) == graph.CHART_TOP_N + 1 and labels[-1] == "其他"
        assert sum(data) == sum(r["availableQty"] for r in rows)   # 各项之和 == 原值合计
        assert "共 12 个仓位" in note


class TestWarehouseScope:
    """数据可见范围（2026-10-07）：本仓 + 所有下级仓 —— 基层只看自己，上级看整棵子树。"""

    WAREHOUSES = [
        {"warehouseCode": "7A00", "parentCode": None},     # 上级（市）
        {"warehouseCode": "7AZA", "parentCode": "7A00"},   # 下级（区）
        {"warehouseCode": "7AZB", "parentCode": "7A00"},   # 下级（区）
        {"warehouseCode": "9B00", "parentCode": None},     # 另一个独立仓
    ]

    def setup_method(self):
        graph._SCOPE_CACHE.clear()

    def teardown_method(self):
        graph._SCOPE_CACHE.clear()

    def test_scope_includes_descendants_recursively(self):
        with patch.object(graph, "list_warehouses", return_value=self.WAREHOUSES):
            assert graph.visible_scope("7A00") == {"7A00", "7AZA", "7AZB"}
            assert graph.visible_scope("7AZA") == {"7AZA"}    # 基层：只看自己
            assert graph.visible_scope("9B00") == {"9B00"}    # 不同支系互不可见
            assert graph.visible_scope(None) is None          # 无仓库归属：不限制

    def test_chart_filtered_to_visible_scope(self):
        rows = [{"warehouseCode": "7A00", "warehouseName": "A市", "availableQty": 100},
                {"warehouseCode": "7AZA", "warehouseName": "A区", "availableQty": 30}]
        with patch.object(graph, "list_warehouses", return_value=self.WAREHOUSES), \
             patch.object(graph, "aggregate_inventory", return_value=rows), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            card, _ = graph._build_chart("warehouse", "bar", {"7AZA"})
        assert card["labels"] == ["A区"] and card["series"][0]["data"] == [30]

    def test_fetch_scoped_totals_but_keeps_full_rows_for_algorithm(self):
        snapshot = {
            "inventory": [
                {"warehouseCode": "7A00", "totalQty": 1000, "availableQty": 1000, "inTransitQty": 0},
                {"warehouseCode": "7AZA", "totalQty": 10, "availableQty": 10, "inTransitQty": 0},
            ],
            "aging": [{"band": "365+", "cnt": 50, "sortNo": 4}],
            "totalTerminals": 1010, "totalAvailable": 1010,
            "totalInTransit": 0, "longAgingCount": 50,
        }
        scoped_aging = [{"band": "365+", "cnt": 5, "availableQty": 5, "sortNo": 4}]
        with patch.object(graph, "list_warehouses", return_value=self.WAREHOUSES), \
             patch.object(graph, "fetch_snapshot", return_value=snapshot), \
             patch.object(graph, "aging_distribution", return_value=scoped_aging), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            out = graph.fetch({"query": "x", "meta": {"warehouse_code": "7AZA"}})
        snap = out["inventory_snapshot"]
        assert len(snap["inventory"]) == 2          # 明细全量：算法要跨仓可比才能找到货源
        assert snap["totalAvailable"] == 10         # 汇总按可见范围（7AZA）
        assert snap["longAgingCount"] == 5          # 库龄按可见仓合并

    def test_replenish_output_filtered_by_scope(self):
        snapshot = {"aging": [], "inventory": [
            {"warehouseCode": "7A00", "warehouseLevel": 2, "model": "F657",
             "materialCode": "M1", "materialName": "光猫F657", "availableQty": 1000,
             "totalQty": 1000, "avgAgingDays": 400.0},
            {"warehouseCode": "7AZA", "warehouseLevel": 3, "model": "F657",
             "materialCode": "M1", "materialName": "光猫F657", "availableQty": 10,
             "totalQty": 10, "avgAgingDays": 30.0},
        ]}
        with patch.object(graph, "list_warehouses", return_value=self.WAREHOUSES), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            # 9B00 用户：7A 系之间的调拨与己无关 → 不可见
            out = graph.algorithm({"inventory_snapshot": snapshot,
                                   "meta": {"warehouse_code": "9B00"}})
            assert out["algo_result"]["transfers"] == []
            # 7A00 用户：能看到（调入方 7AZA 在其子树内）
            out2 = graph.algorithm({"inventory_snapshot": snapshot,
                                    "meta": {"warehouse_code": "7A00"}})
            assert len(out2["algo_result"]["transfers"]) == 1
            # 基层 7AZA 用户：同样能看到这条（是"我"要接收的调拨）
            out3 = graph.algorithm({"inventory_snapshot": snapshot,
                                    "meta": {"warehouse_code": "7AZA"}})
            assert len(out3["algo_result"]["transfers"]) == 1


class TestFetchFailureShortCircuit:
    """取数失败必须短路 —— 绝不能把「数据没取到」说成「库存健康」。

    回归背景：fetch 失败后空快照被算法算成 0 条建议，LLM 措辞时写成
    「各类仓位库存均衡，暂无需操作」，把取数故障伪装成了业务结论。
    """

    def test_report_short_circuits_on_fetch_failure(self):
        with patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            result = graph.report({
                "meta": {"fetch_ok": False, "fetch_error": "网关不可达"},
                "algo_result": {},
                "strategy": "transfer_only",
            })

        assert "获取失败" in result["agent_response"]
        assert result["meta"]["report_skipped_reason"] == "fetch_failed"
        assert result["cards"][0]["type"] == "replenish_unavailable"

    def test_report_does_not_call_llm_on_fetch_failure(self):
        """关键断言：失败路径上 LLM 一次都不应被调用。"""
        with patch.object(graph, "get_stream_writer", return_value=MagicMock()), \
             patch.object(graph, "get_llm") as mock_llm:
            graph.report({
                "meta": {"fetch_ok": False, "fetch_error": "连不上"},
                "algo_result": {},
                "strategy": "transfer_only",
            })

        mock_llm.assert_not_called()

    def test_critic_passes_through_fetch_failure(self):
        """失败文案无数字可 diff、无产物可整改，回炉只会重复同一句话。"""
        result = graph.critic({
            "route": "replenish",
            "meta": {"report_skipped_reason": "fetch_failed"},
            "agent_response": "库存数据获取失败，暂时无法给出补货建议。",
        })

        assert result["critic_gate"] == ""


class TestLegacyKbNode:
    """旧 Java RAG 节点保留未删（KB_CHANNEL 改回 "kb" 即可复用），确保它自身仍然可用。"""

    def test_kb_node_still_works(self):
        with patch.object(graph, "java_rag_agent", return_value="调拨分四种操作。") as m, \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            result = graph.kb_node({"query": "调拨有哪几种操作", "meta": {}})

        assert result["agent_response"] == "调拨分四种操作。"
        assert result["meta"]["channel"] == "kb"
        m.assert_called_once()


class TestTimeChart:
    """时间维度图表（2026-10-08）：入库趋势/月度对比 —— 图表路线第五个内容维度。

    回归背景：chart_plan 只有四个库存维度可选，「各月入库数据明细」被硬选成
    model table（与入库无关的型号汇总表格）—— 时间维度接口只挂了查数助手，
    图表路线不认识它。现接入 inboundTrend，选型师五选一。
    """

    # 无时间词 → 默认最近 6 个月按月分桶（接口原值搬运）
    TREND = {"granularity": "month", "from": "2026-05-01", "to": "2026-10-08",
             "totalCnt": 320, "bucketCount": 3,
             "rows": [{"period": "2026-05", "groupKey": "", "groupName": "", "cnt": 100},
                      {"period": "2026-06", "groupKey": "", "groupName": "", "cnt": 120},
                      {"period": "2026-07", "groupKey": "", "groupName": "", "cnt": 100}]}

    @staticmethod
    def _fake_llm(chart_plan="time line"):
        llm = MagicMock()

        def _invoke(prompt):
            if "图表选型" in str(prompt):
                return MagicMock(content=chart_plan)
            return MagicMock(content="chart")

        llm.invoke.side_effect = _invoke
        return llm

    def test_time_chart_uses_trend_numbers(self):
        """端到端：入库趋势问句 → time 维度 → 折线卡，labels 时间升序、数字为接口原值。"""
        sink = _Sink()
        with patch.object(graph, "get_llm", side_effect=lambda **kw: self._fake_llm("time line")), \
             patch.object(graph, "inbound_trend", return_value=self.TREND), \
             patch.object(graph, "get_stream_writer", return_value=sink):
            final = build_graph().invoke(
                {"query": "最近几个月入库趋势", "revision_count": 0, "meta": {}},
                config={"recursion_limit": 20})

        assert final["route"] == "chart"
        assert final["meta"]["chart_kind"] == "time"
        card = final["cards"][0]
        assert card["type"] == "chart" and card["chartType"] == "line"
        assert card["labels"] == ["2026-05", "2026-06", "2026-07"]  # 时间升序
        assert card["series"][0]["data"] == [100, 120, 100]
        assert "共入库 320 台" in card["note"]
        assert "非时间序列" not in card["note"], "真时间序列不能再背「非时间序列」的免责声明"

    def test_time_chart_scoped_aggregation(self):
        """可见范围收口：带 scope 时 groupBy=warehouse 取数，按 groupKey 过滤聚合。"""
        rows = {"totalCnt": 1129, "rows": [
            {"period": "2026-05", "groupKey": "7A00", "groupName": "A市", "cnt": 100},
            {"period": "2026-05", "groupKey": "7AZA", "groupName": "A区", "cnt": 30},
            {"period": "2026-06", "groupKey": "9B00", "groupName": "B", "cnt": 999}]}
        with patch.object(graph, "inbound_trend", return_value=rows) as mock_trend:
            card, note = graph._build_time_chart("bar", {"7AZA"}, "各月入库对比")

        mock_trend.assert_called_once()
        assert mock_trend.call_args.kwargs.get("group_by") == "warehouse"
        assert card["labels"] == ["2026-05"] and card["series"][0]["data"] == [30]
        assert "30 台" in note

    def test_time_range_keywords(self):
        """时间范围解析是确定性规则活：只认高置信词，未命中默认近 6 个月。"""
        today = date.today()
        y = today.year
        assert graph._time_range("今年入库多少台") == ("month", f"{y}-01-01", today.isoformat())
        assert graph._time_range("去年入库趋势") == ("month", f"{y - 1}-01-01", f"{y - 1}-12-31")
        assert graph._time_range("近3个月入库走势") == ("month", graph._shift_months(today, 2).isoformat(), today.isoformat())
        assert graph._time_range("近三年入库对比")[0] == "year"
        assert graph._time_range("按年看入库量")[0] == "year"
        # 未命中任何时间词：默认近 6 个月
        assert graph._time_range("各月入库对比") == ("month", graph._shift_months(today, 5).isoformat(), today.isoformat())

    def test_shift_months_cross_year(self):
        assert graph._shift_months(date(2026, 3, 15), 5) == date(2025, 10, 1)
        assert graph._shift_months(date(2026, 1, 8), 1) == date(2025, 12, 1)


class TestOrderChannelFromSuggestion:
    def test_order_creates_orders_from_suggestion(self):
        """第二段：带 suggestion_id 回来 → 按建议建单，不再让 LLM 解析话术"""
        sid = save_suggestion({
            "strategy": "transfer_only",
            "transfers": [{
                "model": "F657", "materialCode": "M1", "materialName": "光猫F657",
                "fromWarehouse": "7A00", "fromWarehouseName": "长春市分公司",
                "toWarehouse": "7AZA", "toWarehouseName": "九台区分公司",
                "quantity": 495, "priority": "建议补货", "reason": "测试",
            }],
            "procurements": [],
        })

        fake_vo = {
            "orderNo": "TR202610010001", "operationType": "TRANSFER",
            "fromWarehouse": "7A00", "fromWarehouseName": "长春市分公司",
            "toWarehouse": "7AZA", "toWarehouseName": "九台区分公司",
            "totalQuantity": 495, "status": "PENDING", "needApproval": 0,
            "createdAt": "2026-10-01 01:00:00",
        }

        with patch.object(graph, "get_llm", side_effect=lambda **kw: _fake_llm("order")), \
             patch.object(graph, "create_transfer_order", return_value=fake_vo) as create, \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "帮我调拨", "revision_count": 0, "meta": {"suggestion_id": sid}},
                config={"recursion_limit": 20})

        assert final["route"] == "order"
        create.assert_called_once()
        kwargs = create.call_args.kwargs
        assert kwargs["from_warehouse"] == "7A00"
        assert kwargs["to_warehouse"] == "7AZA"
        assert kwargs["items"][0]["quantity"] == 495
        assert kwargs["suggestion_id"] == sid

        card = final["order_info"]["card"]
        assert card["type"] == "transfer_order"
        assert card["rows"][0]["orderNo"] == "TR202610010001"

    def test_order_reports_failure_explicitly(self):
        """建单失败必须明确报告，不能静默吞掉"""
        from tools.terminal_api import TerminalApiError

        sid = save_suggestion({
            "strategy": "transfer_only",
            "transfers": [{
                "model": "F657", "materialCode": "M1", "materialName": "光猫F657",
                "fromWarehouse": "7A00", "fromWarehouseName": "A",
                "toWarehouse": "7AZA", "toWarehouseName": "B",
                "quantity": 10, "priority": "建议补货", "reason": "测试",
            }],
            "procurements": [],
        })

        with patch.object(graph, "get_llm", side_effect=lambda **kw: _fake_llm("order")), \
             patch.object(graph, "create_transfer_order",
                          side_effect=TerminalApiError("库存不足")), \
             patch.object(graph, "get_stream_writer", return_value=MagicMock()):
            final = build_graph().invoke(
                {"query": "调拨", "revision_count": 0, "meta": {"suggestion_id": sid}},
                config={"recursion_limit": 20})

        assert "失败" in final["agent_response"]
        assert final["meta"]["order_count"] == 0


# ---------------------------------------------------------------------------
# 算法与图的接口对齐
# ---------------------------------------------------------------------------

class TestAlgorithmIntegration:
    def test_inventory_row_maps_snapshot_fields(self):
        row = InventoryRow.from_dict(SNAPSHOT["inventory"][0])
        assert row.warehouseCode == "7A00"
        assert row.availableQty == 1000
        assert row.avgAgingDays == 400.0

    def test_from_dict_tolerates_null_fields(self):
        """后端 JSON 里 null 是常态（库龄脏值被清洗成 NULL），不能崩"""
        row = InventoryRow.from_dict({
            "warehouseCode": "X", "model": "M", "availableQty": None,
            "avgAgingDays": None, "maxAgingDays": None,
        })
        assert row.availableQty == 0
        assert row.avgAgingDays == 0.0
