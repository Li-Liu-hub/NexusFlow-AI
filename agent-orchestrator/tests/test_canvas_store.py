# orch_graph 存储集成测试：连本机 PG（127.0.0.1，非部署环境）
#
# 存储不可用（PG 挂了）时这些测试会因断言失败而红 —— 与降级策略一致：
# 运行时降级不影响对话，但测试环境必须能连库才能验证发布/回滚语义。
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pytest

from canvas.default_dsl import DEFAULT_DSL
from canvas import store as gstore


@pytest.fixture(scope="module")
def guard():
    # 存储不可用则跳过（本机开发环境 PG 应可用；CI 无 PG 时跳过）
    if not gstore._ready():
        pytest.skip("PG 不可用，跳过 orch_graph 集成测试")
    yield


def test_publish_load_rollback_roundtrip(guard):
    marker = f"probe-{uuid.uuid4().hex[:8]}"
    # 发布一份带标记的图
    dsl = {**DEFAULT_DSL}
    v1 = gstore.publish(dsl, note=marker)
    assert v1 is not None
    assert gstore.latest_published_version() == v1
    # 读回一致
    loaded = gstore.load_published(v1)
    assert loaded["entry"] == DEFAULT_DSL["entry"]
    assert len(loaded["nodes"]) == len(DEFAULT_DSL["nodes"])
    # 回滚 v1 → 新版本（链式，不破坏历史）
    v2 = gstore.rollback(v1)
    assert v2 is not None and v2 > v1
    assert gstore.latest_published_version() == v2
    # 回滚不存在版本 → None
    assert gstore.rollback(999999) is None


def test_draft_roundtrip(guard):
    marker = f"draft-{uuid.uuid4().hex[:8]}"
    dsl = {**DEFAULT_DSL}
    assert gstore.save_draft(dsl, note=marker) is True
    loaded = gstore.load_draft()
    assert loaded is not None and loaded["entry"] == DEFAULT_DSL["entry"]
