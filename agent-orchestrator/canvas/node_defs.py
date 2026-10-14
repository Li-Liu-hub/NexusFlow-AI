# 节点库元数据 + 种子（画布「节点面板」的数据源，Dify 式操作逻辑）
#
# 设计（2026-10-11）：
#   节点库里每个条目 = {id, impl_type, label, category, description, params_schema,
#                       params_default, builtin}；
#   - impl_type 是真正写进 DSL 的 type（必须能过 loader 白名单：具名节点 / http 工厂）；
#   - 内置节点：builtin=true，实现来自代码注册表（canvas/registry.py），元数据入库只为
#     前端展示与参数表单；
#   - 接口节点 / 自定义节点：impl_type=http + params_default 预填（path/method/body），
#     用户可在画布上「像搭积木一样」直接使用平台已登记接口 —— 不写代码。
#
# 能力边界（与设计文档一致）：图=数据、能力=代码。节点库管理的是**元数据与参数预设**，
# 新能力实现仍需在注册表写代码（防 SSRF / 护栏锁死）。

# 参数字段 schema：type ∈ text | textarea | json | number | select | prompt
HTTP_PARAMS_SCHEMA = [
    {"key": "path", "label": "接口路径", "type": "text", "required": True,
     "placeholder": "/api/terminal/inventory/snapshot"},
    {"key": "method", "label": "请求方法", "type": "select",
     "options": ["GET", "POST", "PUT", "DELETE"], "default": "GET"},
    {"key": "body", "label": "请求体（支持 {{节点.字段}} 变量映射）", "type": "json", "default": {}},
    {"key": "timeout", "label": "超时（秒）", "type": "number", "default": 10},
]
PROMPT_PARAMS_SCHEMA = [
    {"key": "prompt", "label": "提示词覆盖（留空 = 现网默认）", "type": "prompt", "advanced": True},
]
# 说明（2026-10-11 定稿）：**Agent 节点不入库表**——Agent 的人设/技能/工具在管理端
# 「Agent」页可视化配置，画布侧由前端拉取该列表**动态生成** Agent 节点（零参数，拖用即得）。
# 本文件只放"需要参数预设"的节点（http / 接口节点）与内置能力节点元数据。

# 内置节点种子：id = impl_type（保持稳定），分类见 CATEGORY_LABEL
BUILTIN_NODES = [
    # category, id/impl_type, label, description, params_schema
    ("entry", "intent_router", "意图路由", "识别用户意图并分流（补货/图表/知识问答/建单）", PROMPT_PARAMS_SCHEMA),
    ("integration", "http", "HTTP 调用", "调用平台已登记接口（走网关：签名/路由/计量自动）", HTTP_PARAMS_SCHEMA),
    ("data", "fetch", "库存取数", "按数据权限拉取库存快照与聚合（补货链路起点）", []),
    ("analysis", "algorithm", "补货算法", "按策略计算调拨建议（确定性算法，不经 LLM）", []),
    ("analysis", "chart_plan", "图表选型", "判定图表维度与样式（规则优先 + LLM 兜底）", PROMPT_PARAMS_SCHEMA),
    ("analysis", "chart_build", "图表构建", "取数并生成图表卡片", []),
    ("analysis", "report", "建议卡总结", "汇总补货建议并生成建议卡（含申请按钮）", PROMPT_PARAMS_SCHEMA),
    ("analysis", "order", "建单节点", "两段式第二段：用户点「申请调拨」后创建调拨单", PROMPT_PARAMS_SCHEMA),
    ("analysis", "critic", "质量质检", "数字与语义双闸门的语义侧质检（不合格回炉）", PROMPT_PARAMS_SCHEMA),
    ("output", "respond", "卡片组装", "把链路结果组装成门户卡片（护栏节点，不可删）", []),
    ("output", "digital_human", "数字员工透传", "把问答转发给数字员工内核（ReAct + 知识库）", []),
    ("output", "kb", "知识库通道", "纯 KB 问答通道（保留未删，兜底旧链路）", []),
    ("guard", "verify_numbers", "数字自检", "校验回答数字与源数据一致（护栏节点，不可删、不经 LLM）", []),
    ("internal", "on_rework", "回炉处理", "质检不通过时的回炉路由（内部节点）", []),
    ("internal", "on_degrade", "降级处理", "质检不通过且超限时降级为保守回答（内部节点）", []),
]

CATEGORY_LABEL = {
    "entry": "入口与路由",
    "agent": "AI 智能体",
    "integration": "接口调用",
    "data": "数据获取",
    "analysis": "分析与生成",
    "output": "输出",
    "guard": "护栏（内置锁定）",
    "internal": "内部流转",
}
CATEGORY_ORDER = ["entry", "agent", "integration", "data", "analysis", "output", "guard", "internal"]


def builtin_seed() -> list[dict]:
    """内置节点种子（store 首次启动时幂等导入）。"""
    out = []
    for i, (cat, impl, label, desc, schema) in enumerate(BUILTIN_NODES):
        out.append({
            "id": impl, "impl_type": impl, "label": label, "category": cat,
            "description": desc, "params_schema": schema, "params_default": {},
            "builtin": True, "sort_order": i * 10,
        })
    return out


def interface_seed() -> list[dict]:
    """接口节点种子：把 canvas/interfaces.py 的 13 个已登记接口做成可直接用的节点。"""
    from canvas.interfaces import INTERFACES
    out = []
    for i, it in enumerate(INTERFACES):
        out.append({
            "id": "iface_" + it["path"].strip("/").replace("/", "_") + "_" + it["method"].lower(),
            "impl_type": "http",
            "label": it["name"],
            "category": "integration",
            "description": f'{it["method"]} {it["path"]} · {it["desc"]}' + (
                f'（{it["params"]}）' if it.get("params") else ""),
            "params_schema": HTTP_PARAMS_SCHEMA,
            "params_default": {"method": it["method"], "path": it["path"]},
            "builtin": True,
            "sort_order": 100 + i * 10,
        })
    return out


def full_seed() -> list[dict]:
    return builtin_seed() + interface_seed()
