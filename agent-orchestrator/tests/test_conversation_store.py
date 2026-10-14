# conversation_store 的可测面单测（不连真实 PG）
#
# 这个模块几乎全是 SQL，看起来"没什么可单测的"。但里面藏着两条**安全/口径**规则，
# 值得用假连接把它钉死（这类规则一旦被无意改掉，症状是"别人的会话标题被改了"，
# 属于最难通过功能测试发现的一类问题）：
#   1. 标题生成：首问去空白+换行折叠+截断 30 字，空则回落"新对话"；
#   2. 归属校验：rename/delete 的 WHERE 必须同时带 id 和 user_id
#      （只按 id 删 = 越权删别人的会话）。
#
# 手法：monkeypatch _ready() → True、_connect() → 假连接，直接断言 SQL 与绑定参数。
import sys
from datetime import datetime
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import conversation_store as cs


class _FakeCursor:
    def __init__(self, rowcount=0):
        self.rowcount = rowcount
        self.calls = []

    def execute(self, sql, params=None):
        self.calls.append((sql, params))
        return self

    def fetchall(self):
        return []

    def fetchone(self):
        return None


class _FakeConn:
    def __init__(self, rowcount=0):
        self._cur = _FakeCursor(rowcount)
        self.calls = []

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def execute(self, sql, params=None):
        self.calls.append((sql, params))
        return self._cur

    @property
    def cursor_calls(self):
        return self.calls


@pytest.fixture
def fake_conn(monkeypatch):
    conn = _FakeConn()
    monkeypatch.setattr(cs, "_ready", lambda: True)
    monkeypatch.setattr(cs, "_connect", lambda: conn)
    return conn


# ── _iso：时间序列化 ──

def test_iso_formats_datetime():
    dt = datetime(2026, 10, 11, 15, 4, 5)
    assert cs._iso(dt) == "2026-10-11 15:04:05"


def test_iso_none_becomes_empty_string():
    assert cs._iso(None) == ""


def test_iso_non_datetime_falls_back_to_str():
    assert cs._iso("2026-01-01") == "2026-01-01"


# ── 标题生成规则 ──

def test_title_from_first_query(fake_conn):
    cs.ensure_conversation("c1", "u1", "9B00", first_query="  九台市库存查询  ")
    sql, params = fake_conn.calls[0]
    assert "INSERT INTO orch_conversation" in sql
    assert params[3] == "九台市库存查询", "标题应去掉首尾空白"
    assert "ON CONFLICT (id) DO NOTHING" in sql, "重复建会话必须幂等"


def test_title_truncated_to_30(fake_conn):
    long_q = "库" * 50
    cs.ensure_conversation("c1", "u1", None, first_query=long_q)
    _, params = fake_conn.calls[0]
    assert params[3] == "库" * 30
    assert len(params[3]) == 30


def test_title_newline_collapsed(fake_conn):
    cs.ensure_conversation("c1", "u1", None, first_query="第一行\n第二行")
    _, params = fake_conn.calls[0]
    assert "\n" not in params[3]
    assert params[3] == "第一行 第二行"


def test_title_defaults_when_query_blank(fake_conn):
    cs.ensure_conversation("c1", "u1", None, first_query="   ")
    _, params = fake_conn.calls[0]
    assert params[3] == "新对话"

    fake_conn.calls.clear()
    cs.ensure_conversation("c2", "u1", None, first_query=None)
    _, params = fake_conn.calls[0]
    assert params[3] == "新对话"


def test_ensure_conversation_skips_empty_conv_id(fake_conn):
    """空 conv_id 直接返回 False，且不应触达数据库。"""
    assert cs.ensure_conversation("", "u1", None) is False
    assert fake_conn.calls == []


def test_ensure_conversation_binds_graph_version(fake_conn):
    """画布方案「会话粘版本」：graph_version 必须写进 INSERT。"""
    cs.ensure_conversation("c1", "u1", "9B00", first_query="q", graph_version=7)
    sql, params = fake_conn.calls[0]
    assert "graph_version" in sql
    assert params[4] == 7


# ── 归属校验（安全）──

def test_rename_requires_both_id_and_user(fake_conn):
    cs.rename_conversation("c1", "u1", "新标题")
    sql, params = fake_conn.calls[0]
    assert "WHERE id = %s AND user_id = %s" in sql, \
        "重命名必须同时按 id + user_id 过滤，否则可越权改他人会话"
    assert params[1] == "c1" and params[2] == "u1"


def test_delete_requires_both_id_and_user(fake_conn):
    fake_conn._cur.rowcount = 1
    cs.delete_conversation("c1", "u1")
    sql, params = fake_conn.calls[0]
    assert "DELETE FROM orch_conversation WHERE id = %s AND user_id = %s" in sql, \
        "删除必须带 user_id 归属校验"
    assert params == ("c1", "u1")


def test_delete_returns_false_when_no_row_matched(fake_conn):
    """归属不符 → rowcount=0 → False，且不应继续删消息。"""
    fake_conn._cur.rowcount = 0
    assert cs.delete_conversation("c1", "someone_else") is False
    assert len(fake_conn.calls) == 1, "没删到会话就不该再删其消息"


def test_delete_removes_messages_after_conversation(fake_conn):
    fake_conn._cur.rowcount = 1
    cs.delete_conversation("c1", "u1")
    assert len(fake_conn.calls) == 2
    assert "DELETE FROM orch_message" in fake_conn.calls[1][0]


def test_rename_and_delete_skip_blank_args(fake_conn):
    assert cs.rename_conversation("", "u1", "t") is False
    assert cs.rename_conversation("c1", "u1", "   ") is False
    assert cs.delete_conversation("c1", "") is False
    assert fake_conn.calls == []


# ── 存储不可用时的降级 ──

def test_reads_degrade_when_store_unavailable(monkeypatch):
    """存储不可用不能影响对话：读接口应返回空结果而不是抛异常。"""
    monkeypatch.setattr(cs, "_ready", lambda: False)
    assert cs.list_conversations("u1") == []
    got = cs.get_conversation("c1")
    assert got["conversation_id"] == "c1"
    assert got["messages"] == []
