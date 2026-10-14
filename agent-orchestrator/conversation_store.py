# 会话 / 消息持久化（2026-10-07）：历史对话要能查、上下文要能接续
#
# 为什么编排层要自己落库：
#   编排层本身无状态（建议凭据 30 分钟 TTL），但「发送过的对话要能查找」是用户明确要求的能力。
#   所以每轮问答落进 PG 的**会话表 + 消息表**（orch_conversation / orch_message，
#   与内核 RAG 同实例、不同表），门户的「最近对话」读本表。
#
# 与内核的关系：
#   kb 路线同时把 conversationId 透传给内核 —— 内核有自己的会话表与记忆顾问，
#   两边各记一份、同 id 对齐；门户历史读本表（三条路线齐全，kb 只是其中一条）。
#
# 设计取舍：
#   - **降级优先**：PG 不可用/落库失败只记日志，绝不阻塞对话 —— 历史是旁路，不能影响主链路
#   - 建表在首次使用时 CREATE TABLE IF NOT EXISTS（这个规模不需要迁移工具）
#   - 函数一律不抛异常（返回 False/[]），调用方无需 try
import logging
import time
from typing import Any

from config import settings

log = logging.getLogger("orchestrator.store")

_READY = {"ok": False, "checked_at": 0.0}
_RETRY_SECONDS = 30  # 连接失败后 30 秒内不再重试，避免每轮对话都卡 5 秒超时

_DDL = (
    """CREATE TABLE IF NOT EXISTS orch_conversation (
         id varchar(40) PRIMARY KEY,
         user_id varchar(64),
         warehouse_code varchar(32),
         title varchar(200),
         message_count int NOT NULL DEFAULT 0,
         created_at timestamptz NOT NULL DEFAULT now(),
         updated_at timestamptz NOT NULL DEFAULT now(),
         graph_version int
       )""",
    """CREATE TABLE IF NOT EXISTS orch_message (
         id bigserial PRIMARY KEY,
         conversation_id varchar(40) NOT NULL,
         role varchar(16) NOT NULL,
         content text,
         route varchar(16),
         cards jsonb,
         created_at timestamptz NOT NULL DEFAULT now()
       )""",
    "CREATE INDEX IF NOT EXISTS idx_orch_message_conv ON orch_message(conversation_id, id)",
    "CREATE INDEX IF NOT EXISTS idx_orch_conversation_user ON orch_conversation(user_id, updated_at DESC)",
    # 会话粘版本（画布方案）：旧库兼容 —— 已存在的表补列（PG 支持 IF NOT EXISTS）
    "ALTER TABLE orch_conversation ADD COLUMN IF NOT EXISTS graph_version int",
)


def _connect():
    import psycopg
    return psycopg.connect(
        host=settings.PG_HOST, port=settings.PG_PORT,
        user=settings.PG_USER, password=settings.PG_PASSWORD,
        dbname=settings.PG_DATABASE, connect_timeout=5,
        autocommit=True)


def _ready() -> bool:
    """确保表已建好（首次成功连接时执行 DDL）；失败按 30 秒退避重试。"""
    now = time.time()
    if _READY["ok"]:
        return True
    if now - _READY["checked_at"] < _RETRY_SECONDS:
        return False
    _READY["checked_at"] = now
    try:
        with _connect() as con:
            for ddl in _DDL:
                con.execute(ddl)
        _READY["ok"] = True
        log.info("会话存储就绪：%s@%s/%s", settings.PG_USER, settings.PG_HOST, settings.PG_DATABASE)
        return True
    except Exception as exc:  # noqa: BLE001 —— 存储不可用不能影响对话
        log.warning("会话存储不可用（历史将暂不记录）：%s", exc)
        return False


# ---------------------------------------------------------------------------
# 写
# ---------------------------------------------------------------------------

def ensure_conversation(conv_id: str, user_id: str, warehouse_code: str | None,
                        first_query: str | None = None,
                        graph_version: int | None = None) -> bool:
    """会话不存在则建（标题取首问前 30 字）。

    graph_version：创建时绑定当时的图版本（画布方案「会话粘版本」）——
    中途发布新图不打断进行中的确认流（两段式 suggestion_id 跨两个请求）。
    """
    if not conv_id or not _ready():
        return False
    title = (first_query or "").strip().replace("\n", " ")[:30] or "新对话"
    try:
        with _connect() as con:
            con.execute(
                """INSERT INTO orch_conversation (id, user_id, warehouse_code, title, graph_version)
                   VALUES (%s, %s, %s, %s, %s)
                   ON CONFLICT (id) DO NOTHING""",
                (conv_id, user_id, warehouse_code or None, title, graph_version))
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("建会话失败：%s", exc)
        return False


def append_message(conv_id: str, role: str, content: str,
                   route: str | None = None, cards: list | None = None) -> bool:
    """追加一条消息并刷新会话（计数 + 更新时间）。"""
    if not conv_id or not _ready():
        return False
    import json
    try:
        with _connect() as con:
            con.execute(
                "INSERT INTO orch_message (conversation_id, role, content, route, cards) "
                "VALUES (%s, %s, %s, %s, %s)",
                (conv_id, role, content or "", route or None,
                 json.dumps(cards, ensure_ascii=False) if cards else None))
            con.execute(
                "UPDATE orch_conversation SET message_count = message_count + 1, "
                "updated_at = now() WHERE id = %s", (conv_id,))
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("落消息失败：%s", exc)
        return False


# ---------------------------------------------------------------------------
# 读
# ---------------------------------------------------------------------------

def list_conversations(user_id: str, limit: int = 20) -> list[dict]:
    """某用户的会话列表（按最近更新倒序）——门户「最近对话」。"""
    if not user_id or not _ready():
        return []
    try:
        with _connect() as con:
            cur = con.execute(
                """SELECT id, title, message_count, created_at, updated_at
                   FROM orch_conversation WHERE user_id = %s
                   ORDER BY updated_at DESC LIMIT %s""",
                (user_id, max(1, min(limit, 100))))
            return [{"conversation_id": r[0], "title": r[1], "message_count": r[2],
                     "created_at": _iso(r[3]), "updated_at": _iso(r[4])}
                    for r in cur.fetchall()]
    except Exception as exc:  # noqa: BLE001
        log.warning("查会话列表失败：%s", exc)
        return []


def get_conversation(conv_id: str, limit: int = 200) -> dict:
    """会话详情（按时间正序的消息列表）——门户回放。"""
    if not conv_id or not _ready():
        return {"conversation_id": conv_id, "title": "", "messages": []}
    try:
        with _connect() as con:
            head = con.execute(
                "SELECT title, user_id, warehouse_code, created_at FROM orch_conversation WHERE id = %s",
                (conv_id,)).fetchone()
            cur = con.execute(
                """SELECT role, content, route, cards, created_at FROM orch_message
                   WHERE conversation_id = %s ORDER BY id LIMIT %s""",
                (conv_id, max(1, min(limit, 500))))
            messages = [{"role": r[0], "content": r[1], "route": r[2],
                         "cards": r[3], "created_at": _iso(r[4])}
                        for r in cur.fetchall()]
        return {"conversation_id": conv_id,
                "title": head[0] if head else "",
                "user_id": head[1] if head else "",
                "warehouse_code": head[2] if head else "",
                "created_at": _iso(head[3]) if head else "",
                "messages": messages}
    except Exception as exc:  # noqa: BLE001
        log.warning("查会话详情失败：%s", exc)
        return {"conversation_id": conv_id, "title": "", "messages": []}


def rename_conversation(conv_id: str, user_id: str, title: str) -> bool:
    """重命名会话标题（归属不符时 WHERE 不命中，返回 False —— 与详情 404 同一信任模型）。"""
    title = (title or "").strip().replace("\n", " ")[:200]
    if not conv_id or not user_id or not title or not _ready():
        return False
    try:
        with _connect() as con:
            cur = con.execute(
                "UPDATE orch_conversation SET title = %s, updated_at = updated_at "
                "WHERE id = %s AND user_id = %s", (title, conv_id, user_id))
            return cur.rowcount > 0
    except Exception as exc:  # noqa: BLE001
        log.warning("重命名会话失败：%s", exc)
        return False


def delete_conversation(conv_id: str, user_id: str) -> bool:
    """删除会话及其全部消息（归属不符时一行都删不到，返回 False）。"""
    if not conv_id or not user_id or not _ready():
        return False
    try:
        with _connect() as con:
            cur = con.execute(
                "DELETE FROM orch_conversation WHERE id = %s AND user_id = %s",
                (conv_id, user_id))
            if cur.rowcount == 0:
                return False
            con.execute("DELETE FROM orch_message WHERE conversation_id = %s", (conv_id,))
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("删除会话失败：%s", exc)
        return False


def _iso(value: Any) -> str:
    try:
        return value.isoformat(sep=" ", timespec="seconds")
    except AttributeError:
        return str(value or "")