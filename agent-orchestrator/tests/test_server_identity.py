# 门户身份链路单测（2026-10-07 [数据权限信任根] 修复）：
#   - 网关注入头（X-User-Id / X-User-Warehouse）优先于 query 参数 —— 浏览器自报参数不可信
#   - 会话详情归属校验：非本人会话返回 404（不泄露存在性）
#   - 无注入头（本机脚本 / 服务间直连）时回退 query，兼容既有联调脚本
# 说明：不依赖 PG（会话存储被替换为内存桩），不依赖网关（直接打编排层端点）
# 用法: uv run pytest tests/test_server_identity.py
import pytest
from fastapi.testclient import TestClient

import server
from server import app


@pytest.fixture()
def client():
    return TestClient(app)


@pytest.fixture()
def fake_store(monkeypatch):
    """替换会话存储：单测不碰 PG，同时记录收到的 user_id 便于断言。"""
    calls: dict = {"list": [], "ensure": [], "append": []}

    def list_conversations(user_id, limit=20):
        calls["list"].append(user_id)
        return [{"conversation_id": "c1", "title": "t", "message_count": 2,
                 "created_at": "", "updated_at": ""}]

    def get_conversation(cid, limit=200):
        # 固定返回 alice 的会话：越权用例就是"别人来读它"
        return {"conversation_id": cid, "title": "t", "user_id": "alice",
                "warehouse_code": "9BZA", "created_at": "", "messages": []}

    def ensure_conversation(*args, **kwargs):
        calls["ensure"].append(args)
        return True

    def append_message(*args, **kwargs):
        calls["append"].append(args)
        return True

    monkeypatch.setattr(server.store, "list_conversations", list_conversations)
    monkeypatch.setattr(server.store, "get_conversation", get_conversation)
    monkeypatch.setattr(server.store, "ensure_conversation", ensure_conversation)
    monkeypatch.setattr(server.store, "append_message", append_message)
    return calls


class TestConversationList:
    def test_header_identity_wins_over_query(self, client, fake_store):
        """带注入头时，query 里的 user_id（可被浏览器改写）必须被忽略。"""
        r = client.get("/orchestrator/v1/conversation/list",
                       params={"user_id": "mallory"},
                       headers={"X-User-Id": "alice"})
        assert r.status_code == 200
        assert fake_store["list"] == ["alice"]

    def test_query_fallback_without_header(self, client, fake_store):
        """非门户通道（本机脚本/服务间直连）无注入头 → 回退 query，兼容旧调用。"""
        r = client.get("/orchestrator/v1/conversation/list", params={"user_id": "bob"})
        assert r.status_code == 200
        assert fake_store["list"] == ["bob"]


class TestConversationOwnership:
    def test_other_users_conversation_is_404(self, client, fake_store):
        """会话属于 alice，mallory 带着自己的注入头来读 → 404（不泄露存在性）。"""
        r = client.get("/orchestrator/v1/conversation/c1",
                       headers={"X-User-Id": "mallory"})
        assert r.status_code == 404

    def test_owner_can_read(self, client, fake_store):
        r = client.get("/orchestrator/v1/conversation/c1",
                       headers={"X-User-Id": "alice"})
        assert r.status_code == 200
        assert r.json()["user_id"] == "alice"

    def test_no_header_keeps_legacy_behavior(self, client, fake_store):
        """无注入头（本机脚本回放）不校验归属，行为与改造前一致。"""
        r = client.get("/orchestrator/v1/conversation/c1")
        assert r.status_code == 200

    def test_orphan_conversation_is_404_for_portal_user(self, client, fake_store, monkeypatch):
        """无归属会话（历史/内部调用产生，user_id 为空）对门户用户也 404 —— 不给"猜 id"留口子。"""
        monkeypatch.setattr(server.store, "get_conversation",
                            lambda cid, limit=200: {"conversation_id": cid, "title": "", "messages": []})
        r = client.get("/orchestrator/v1/conversation/c2", headers={"X-User-Id": "mallory"})
        assert r.status_code == 404


class _FakeGraph:
    """假装编译好的图：只记录入口状态，推一帧 updates 就收工（不跑真实节点）。"""

    def __init__(self):
        self.initial: dict | None = None

    def stream(self, initial, config=None, stream_mode=None):
        self.initial = initial
        yield ("updates", {"respond": {"agent_response": "ok", "cards": [],
                                       "strategy": "", "route": "kb"}})


class TestChatStreamIdentity:
    def test_header_overrides_query(self, client, fake_store, monkeypatch):
        """改 query 参数放大不了数据范围：门户口径由注入头决定。"""
        fake = _FakeGraph()
        monkeypatch.setattr(server, "build_graph", lambda: fake)
        r = client.post("/orchestrator/v1/chat/stream",
                        params={"query": "hi", "user_id": "mallory",
                                "warehouse_code": "9B00"},
                        headers={"X-User-Id": "alice", "X-User-Warehouse": "9BZA"})
        assert r.status_code == 200
        assert fake.initial["meta"]["user_id"] == "alice"
        assert fake.initial["meta"]["warehouse_code"] == "9BZA"

    def test_query_fallback_without_header(self, client, fake_store, monkeypatch):
        fake = _FakeGraph()
        monkeypatch.setattr(server, "build_graph", lambda: fake)
        r = client.post("/orchestrator/v1/chat/stream",
                        params={"query": "hi", "user_id": "demo"})
        assert r.status_code == 200
        assert fake.initial["meta"]["user_id"] == "demo"


class TestAlertScanIdentity:
    def test_header_warehouse_wins(self, client, monkeypatch):
        """「扫描本仓」以注入头的仓位为准，query 里的 warehouse_code 被忽略。"""
        seen: dict = {}

        def fake_scan(warehouse_code=None):
            seen["wh"] = warehouse_code
            return {"ok": True}

        monkeypatch.setattr(server, "run_alert_scan", fake_scan)
        r = client.post("/orchestrator/v1/alert/scan",
                        params={"warehouse_code": "9B00"},
                        headers={"X-User-Id": "alice", "X-User-Warehouse": "9BZA"})
        assert r.status_code == 200
        assert seen["wh"] == "9BZA"