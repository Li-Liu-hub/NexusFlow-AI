# 图定义存储（PG）：orch_graph 表 —— draft / published 双态 + 版本固化
#
# 模式与 conversation_store 完全一致：psycopg 直连、首次建表、失败降级不阻塞。
# 版本语义（第十二节）：
#   - save_draft：编辑中自动保存，status=draft（同表 upsert，画布只有一个草稿位）
#   - publish：校验通过后固化为 published v{N}，旧版本永久保留（回滚 + 进行中会话引用）
#   - load_published：运行时取「最新 published」；新会话即用新图（实时生效）
from __future__ import annotations

import json
import logging
import uuid

from config import settings

log = logging.getLogger("orchestrator.canvas_store")

_DDL = (
    """CREATE TABLE IF NOT EXISTS orch_graph (
         id bigserial PRIMARY KEY,
         version int NOT NULL,
         status varchar(16) NOT NULL,
         dsl jsonb NOT NULL,
         note varchar(200),
         created_at timestamptz NOT NULL DEFAULT now()
       )""",
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_orch_graph_version ON orch_graph(version)",
    """CREATE UNIQUE INDEX IF NOT EXISTS idx_orch_graph_draft
       ON orch_graph((version)) WHERE status = 'draft'""",
    # 节点库（2026-10-11，Dify 式节点面板的数据源）：
    #   impl_type = 写进 DSL 的 type（须过 loader 白名单）；内置节点元数据入库供 UI，
    #   接口节点/自定义节点 impl_type=http + params_default 预填 —— 不写代码即可用。
    """CREATE TABLE IF NOT EXISTS orch_node_def (
         id varchar(96) PRIMARY KEY,
         impl_type varchar(64) NOT NULL,
         label varchar(64) NOT NULL,
         category varchar(32) NOT NULL,
         description varchar(240),
         params_schema jsonb NOT NULL DEFAULT '[]'::jsonb,
         params_default jsonb NOT NULL DEFAULT '{}'::jsonb,
         builtin boolean NOT NULL DEFAULT false,
         enabled boolean NOT NULL DEFAULT true,
         sort_order int NOT NULL DEFAULT 100,
         created_at timestamptz NOT NULL DEFAULT now()
       )""",
)


def _seed_node_defs(con) -> None:
    """幂等导入内置节点种子：只更新 builtin=true 的行，用户自定义节点不受影响。"""
    from canvas.node_defs import full_seed
    seed = full_seed()
    ids = [n["id"] for n in seed]
    # 清理：种子改版后移除的旧内置节点（自定义节点 builtin=false 不受影响）
    con.execute("DELETE FROM orch_node_def WHERE builtin = true AND id <> ALL(%s)", (ids,))
    for n in seed:
        con.execute(
            """INSERT INTO orch_node_def (id, impl_type, label, category, description,
                   params_schema, params_default, builtin, sort_order)
               VALUES (%s, %s, %s, %s, %s, %s, %s, true, %s)
               ON CONFLICT (id) DO UPDATE SET
                 impl_type = EXCLUDED.impl_type, label = EXCLUDED.label,
                 category = EXCLUDED.category, description = EXCLUDED.description,
                 params_schema = EXCLUDED.params_schema, sort_order = EXCLUDED.sort_order
               WHERE orch_node_def.builtin = true""",
            (n["id"], n["impl_type"], n["label"], n["category"], n["description"],
             json.dumps(n["params_schema"], ensure_ascii=False),
             json.dumps(n["params_default"], ensure_ascii=False), n["sort_order"]))

_READY = {"ok": False, "checked_at": 0.0}
_RETRY_SECONDS = 30


def _connect():
    import psycopg
    return psycopg.connect(
        host=settings.PG_HOST, port=settings.PG_PORT,
        user=settings.PG_USER, password=settings.PG_PASSWORD,
        dbname=settings.PG_DATABASE, connect_timeout=5,
        autocommit=True)


def _ready() -> bool:
    import time
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
            _seed_node_defs(con)   # 节点库种子（幂等；只更新 builtin=true 的行）
        _READY["ok"] = True
        return True
    except Exception as exc:  # noqa: BLE001 —— 存储不可用不阻塞对话
        log.warning("orch_graph 存储不可用：%s", exc)
        return False


def _as_dsl(raw) -> dict | None:
    if raw is None:
        return None
    return raw if isinstance(raw, dict) else json.loads(raw)


# ---------------------------------------------------------------------------
# 对外 API（全部不抛异常，降级返回 None/False —— 与 conversation_store 同风格）
# ---------------------------------------------------------------------------

def save_draft(dsl: dict, note: str = "") -> bool:
    """编辑中自动保存：同表单行 upsert（画布只有一个草稿位）。"""
    if not _ready():
        return False
    try:
        with _connect() as con:
            con.execute("DELETE FROM orch_graph WHERE status = 'draft'")
            con.execute(
                "INSERT INTO orch_graph(version, status, dsl, note) "
                "VALUES (0, 'draft', %s, %s)",
                (json.dumps(dsl, ensure_ascii=False), note[:200]))
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("保存画布草稿失败：%s", exc)
        return False


def load_draft() -> dict | None:
    if not _ready():
        return None
    try:
        with _connect() as con:
            row = con.execute(
                "SELECT dsl FROM orch_graph WHERE status = 'draft' "
                "ORDER BY created_at DESC LIMIT 1").fetchone()
        return _as_dsl(row[0]) if row else None
    except Exception as exc:  # noqa: BLE001
        log.warning("读取画布草稿失败：%s", exc)
        return None


def clear_draft() -> bool:
    """清空草稿位（联调/测试用；已发布版本不受影响）。"""
    if not _ready():
        return False
    try:
        with _connect() as con:
            con.execute("DELETE FROM orch_graph WHERE status = 'draft'")
        return True
    except Exception as exc:  # noqa: BLE001
        log.warning("清空画布草稿失败：%s", exc)
        return False


def publish(dsl: dict, note: str = "") -> int | None:
    """固化为 published v{N}（N = 当前最大版本 + 1）。返回新版本号；失败 None。"""
    if not _ready():
        return None
    try:
        with _connect() as con:
            row = con.execute("SELECT COALESCE(MAX(version), 0) FROM orch_graph WHERE status = 'published'").fetchone()
            version = int(row[0]) + 1
            con.execute(
                "INSERT INTO orch_graph(version, status, dsl, note) "
                "VALUES (%s, 'published', %s, %s)",
                (version, json.dumps(dsl, ensure_ascii=False), note[:200]))
        log.info("画布图已发布：v%s", version)
        return version
    except Exception as exc:  # noqa: BLE001
        log.warning("发布画布图失败：%s", exc)
        return None


def list_versions(limit: int = 20) -> list[dict]:
    if not _ready():
        return []
    try:
        with _connect() as con:
            rows = con.execute(
                "SELECT version, status, note, created_at FROM orch_graph "
                "WHERE status = 'published' ORDER BY version DESC LIMIT %s", (limit,)).fetchall()
        return [{"version": r[0], "status": r[1], "note": r[2], "created_at": str(r[3])} for r in rows]
    except Exception as exc:  # noqa: BLE001
        log.warning("查询图版本失败：%s", exc)
        return []


def load_published(version: int | None = None) -> dict | None:
    """运行时读取：缺省 = 最新 published；指定 version = 会话粘版本的回读。"""
    if not _ready():
        return None
    try:
        with _connect() as con:
            if version is None:
                row = con.execute(
                    "SELECT dsl, version FROM orch_graph WHERE status = 'published' "
                    "ORDER BY version DESC LIMIT 1").fetchone()
            else:
                row = con.execute(
                    "SELECT dsl, version FROM orch_graph "
                    "WHERE status = 'published' AND version = %s", (version,)).fetchone()
        return _as_dsl(row[0]) if row else None
    except Exception as exc:  # noqa: BLE001
        log.warning("读取已发布图失败：%s", exc)
        return None


def latest_published_version() -> int | None:
    """最新 published 版本号（会话粘版本用）；无/存储不可用返回 None。"""
    if not _ready():
        return None
    try:
        with _connect() as con:
            row = con.execute(
                "SELECT MAX(version) FROM orch_graph WHERE status = 'published'").fetchone()
        return int(row[0]) if row and row[0] is not None else None
    except Exception as exc:  # noqa: BLE001
        log.warning("查询最新图版本失败：%s", exc)
        return None


def rollback(version: int) -> int | None:
    """回滚：把历史版本 v{version} 的 DSL 重新发布为新版本（版本链不破坏）。

    语义与前端「设为当前」一致 —— 不是时间倒流，而是复制旧图为新 published，
    进行中会话与版本列表完全无感。
    """
    dsl = load_published(version)
    if dsl is None:
        log.warning("回滚失败：版本 v%s 不存在", version)
        return None
    return publish(dsl, note=f"rollback from v{version}")


# ---------------------------------------------------------------------------
# 节点库（orch_node_def）：Dify 式节点面板的数据源
#   内置节点（builtin=true）：元数据入库供 UI，实现仍在代码注册表；
#   接口节点/自定义节点：impl_type=http + params_default 预填 —— 用户不写代码即用。
# ---------------------------------------------------------------------------

def list_node_defs() -> dict:
    """节点库：按分类分组返回（分类顺序 = node_defs.CATEGORY_ORDER）。"""
    if not _ready():
        return {"categories": []}
    try:
        with _connect() as con:
            rows = con.execute(
                "SELECT id, impl_type, label, category, description, params_schema, "
                "params_default, builtin FROM orch_node_def WHERE enabled = true "
                "ORDER BY sort_order, label").fetchall()
    except Exception as exc:  # noqa: BLE001
        log.warning("读取节点库失败：%s", exc)
        return {"categories": []}

    from canvas.node_defs import CATEGORY_LABEL, CATEGORY_ORDER
    groups: dict[str, list] = {}
    for r in rows:
        groups.setdefault(r[3], []).append({
            "id": r[0], "implType": r[1], "label": r[2], "category": r[3],
            "description": r[4],
            "paramsSchema": r[5] if isinstance(r[5], list) else json.loads(r[5] or "[]"),
            "paramsDefault": r[6] if isinstance(r[6], dict) else json.loads(r[6] or "{}"),
            "builtin": r[7],
        })
    cats = [{"key": k, "label": CATEGORY_LABEL.get(k, k), "items": groups.pop(k)}
            for k in CATEGORY_ORDER if k in groups]
    for k, items in groups.items():   # 兜底：未知分类（如自定义扩展的分类）
        cats.append({"key": k, "label": CATEGORY_LABEL.get(k, k), "items": items})
    return {"categories": cats}


def create_node_def(label: str, description: str, path: str, method: str = "GET",
                    body: dict | None = None) -> str | None:
    """创建自定义节点（impl_type 固定 http：调用平台已登记接口，不写代码）。

    边界（设计一致）：节点库管理参数预设；**新能力实现仍需在代码注册表**——
    所以这里只允许 http 形态，不允许自定义任意执行语义（防 SSRF）。
    """
    if not _ready():
        return None
    from canvas.node_defs import HTTP_PARAMS_SCHEMA
    nid = "custom_" + uuid.uuid4().hex[:10]
    try:
        with _connect() as con:
            con.execute(
                """INSERT INTO orch_node_def (id, impl_type, label, category, description,
                       params_schema, params_default, builtin, sort_order)
                   VALUES (%s, 'http', %s, 'custom', %s, %s, %s, false, 900)""",
                (nid, (label or "自定义节点")[:64], (description or "")[:240],
                 json.dumps(HTTP_PARAMS_SCHEMA, ensure_ascii=False),
                 json.dumps({"method": method or "GET", "path": path or "",
                             "body": body or {}}, ensure_ascii=False)))
        return nid
    except Exception as exc:  # noqa: BLE001
        log.warning("创建自定义节点失败：%s", exc)
        return None


def delete_node_def(nid: str) -> bool:
    """删除自定义节点（builtin 不可删 —— 内置节点是能力注册表的一部分）。"""
    if not _ready() or not nid:
        return False
    try:
        with _connect() as con:
            cur = con.execute(
                "DELETE FROM orch_node_def WHERE id = %s AND builtin = false", (nid,))
        return cur.rowcount > 0
    except Exception as exc:  # noqa: BLE001
        log.warning("删除自定义节点失败：%s", exc)
        return False
