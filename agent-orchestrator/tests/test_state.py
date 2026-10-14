# State 契约单测：reducer 合并语义 + 字段完整性
# 用法: uv run pytest tests/test_state.py
import typing

from state import OrchestratorState, merge_dict


class TestMergeDict:
    def test_merge_keeps_both_writers(self):
        # executor 写耗时，respond 写模型名 → 两个 key 都要在
        left = {"duration_ms": 120}
        right = {"model": "glm-5.2"}
        merged = merge_dict(left, right)
        assert merged == {"duration_ms": 120, "model": "glm-5.2"}

    def test_merge_same_key_right_wins(self):
        # 同 key 冲突时右值赢（LangGraph reducer 约定：right 是最新写入）
        assert merge_dict({"conversation_id": "a"}, {"conversation_id": "b"}) == {
            "conversation_id": "b"
        }

    def test_merge_empty_side(self):
        assert merge_dict({}, {"k": 1}) == {"k": 1}
        assert merge_dict({"k": 1}, {}) == {"k": 1}


class TestStateContract:
    def test_all_fields_declared(self):
        """三条业务路线契约的字段必须齐全。

        少了哪个字段，对应节点写回时会被 LangGraph 静默丢弃
        （TypedDict 不声明 = 该字段不进入 State），症状是"节点跑了但下游读不到"。
        """
        hints = typing.get_type_hints(OrchestratorState, include_extras=True)
        assert set(hints) == {
            # 入口与路由
            "query",
            "route",
            # chart 路线
            "chart_plan",
            # kb 通道
            "rewritten_query",
            "agent_response",
            # replenish 通道
            "inventory_snapshot",
            "algo_result",
            "strategy",
            "cards",
            "suggestion_id",
            # order 通道
            "order_info",
            # 质检
            "critic_feedback",
            "critic_gate",
            "revision_count",
            # 元信息
            "meta",
        }

    def test_state_allows_partial_updates(self):
        """total=False：节点只写自己产出的字段，不必填满整个契约。"""
        partial: OrchestratorState = {"query": "哪些仓位缺货"}
        assert partial["query"] == "哪些仓位缺货"

    def test_meta_has_explicit_reducer(self):
        # 模块计划硬性要求：State 带"显式 reducer"，落点就是 meta 的 Annotated 元数据
        hints = typing.get_type_hints(OrchestratorState, include_extras=True)
        meta_meta = hints["meta"].__metadata__
        assert merge_dict in meta_meta
