# 接口清单 + 预定义节点集（画布「写节点」的数据源）
#
# 数据来源（真实登记/实现，非虚构）：
#   - tools/terminal_api.py 的 11 个 PATH（网关侧 /api/** 前缀，登记见 local-init/72 号 SQL）
#   - tools/java_agent.py（oa 通道，RAG agent）
#   - tools/digital_human_api.py（数字员工内核，专用协议非裸 http，单列说明）
#
# 用途：
#   GET /orchestrator/v1/graph/interfaces → 画布页「接口清单」面板：
#   用户照着写 http 节点（path + body），或一键用 PRESET_NODES 生成预填节点。
#
# 路由/鉴权事实（第十六/十七节）：path 即路由依据（网关 yml routes），
# 权限/计量按 (path, method) 查 interface_info —— path 必须与登记一致，否则网关 403。

INTERFACES: list[dict] = [
    {"name": "库存快照", "method": "GET", "path": "/api/terminal/inventory/snapshot",
     "desc": "全量终端库存快照（含仓位/库龄/在途），补货与图表的数据源",
     "params": "warehouseCode 可选 · model 可选"},
    {"name": "库存聚合", "method": "GET", "path": "/api/terminal/inventory/aggregate",
     "desc": "按维度聚合的库存分布（图表/对比用）",
     "params": "warehouseCode 可选"},
    {"name": "库龄分布", "method": "GET", "path": "/api/terminal/inventory/aging",
     "desc": "库龄分段分布（长库龄预警数据源）",
     "params": "warehouseCode 可选"},
    {"name": "型号汇总", "method": "GET", "path": "/api/terminal/inventory/byModel",
     "desc": "按终端型号汇总库存",
     "params": "无"},
    {"name": "统计卡片", "method": "GET", "path": "/api/terminal/stat/cards",
     "desc": "门户统计卡四项总数（终端总数/仓位/在途/预警）",
     "params": "无"},
    {"name": "仓位列表", "method": "GET", "path": "/api/terminal/warehouse/list",
     "desc": "仓位树/列表（数据权限范围来源）",
     "params": "无"},
    {"name": "创建调拨单", "method": "POST", "path": "/api/transferOrder/create",
     "desc": "创建调拨单（补货建议的执行落点，AI 只用这一步写操作）",
     "params": "operationType · fromWarehouse · toWarehouse · items[]"},
    {"name": "调拨单列表", "method": "GET", "path": "/api/transferOrder/list/page",
     "desc": "调拨单分页列表",
     "params": "current · pageSize · status 可选 · orderNo 可选"},
    {"name": "调拨单详情", "method": "GET", "path": "/api/transferOrder/get",
     "desc": "按单号查调拨单详情",
     "params": "orderNo"},
    {"name": "通知写入", "method": "POST", "path": "/api/transferOrder/notice",
     "desc": "写站内通知（同 receiver+type 未读只刷新不新增，告警闭环投递用）",
     "params": "receiver · type · content"},
    {"name": "库存告警扫描", "method": "POST", "path": "/api/terminal/stockAlert/scan",
     "desc": "触发一轮库存告警扫描",
     "params": "warehouseCode 可选"},
    {"name": "Java RAG 问答（同步）", "method": "POST", "path": "/oa/chat/sync",
     "desc": "aismp-agent 的 RAG 问答（同步返回完整回答）",
     "params": "message · conversationId 可选"},
    {"name": "Java RAG 问答（SSE）", "method": "GET", "path": "/oa/agent/chat/sse",
     "desc": "aismp-agent 的 RAG 问答（SSE 流式）",
     "params": "message · conversationId 可选"},
]

# 预定义节点集：每个条目可直接作为一个画布节点（type=http + 预填 params）
# —— 这是「节点定义」的正式产出；前端「用作节点」/ 添加下拉直接消费。
PRESET_NODES: list[dict] = [
    {"key": "preset_inventory_snapshot", "label": "库存快照查询",
     "node": {"id": "inventory_snapshot", "type": "http",
              "params": {"method": "GET", "path": "/api/terminal/inventory/snapshot",
                         "body": {"warehouseCode": "{{meta.warehouse_code or ''}}"}}}},
    {"key": "preset_inventory_aggregate", "label": "库存聚合查询",
     "node": {"id": "inventory_aggregate", "type": "http",
              "params": {"method": "GET", "path": "/api/terminal/inventory/aggregate"}}},
    {"key": "preset_aging", "label": "库龄分布查询",
     "node": {"id": "aging_distribution", "type": "http",
              "params": {"method": "GET", "path": "/api/terminal/inventory/aging"}}},
    {"key": "preset_by_model", "label": "型号库存汇总",
     "node": {"id": "inventory_by_model", "type": "http",
              "params": {"method": "GET", "path": "/api/terminal/inventory/byModel"}}},
    {"key": "preset_stat_cards", "label": "统计卡片数据",
     "node": {"id": "stat_cards", "type": "http",
              "params": {"method": "GET", "path": "/api/terminal/stat/cards"}}},
    {"key": "preset_warehouse_list", "label": "仓位列表",
     "node": {"id": "warehouse_list", "type": "http",
              "params": {"method": "GET", "path": "/api/terminal/warehouse/list"}}},
    {"key": "preset_order_create", "label": "创建调拨单",
     "node": {"id": "transfer_create", "type": "http",
              "params": {"method": "POST", "path": "/api/transferOrder/create",
                         "body": {"operationType": "ALLOCATE",
                                  "fromWarehouse": "{{fetch_result.fromWarehouse}}",
                                  "toWarehouse": "{{fetch_result.toWarehouse}}",
                                  "items": "{{fetch_result.items}}"}}}},
    {"key": "preset_order_list", "label": "调拨单列表",
     "node": {"id": "transfer_list", "type": "http",
              "params": {"method": "GET", "path": "/api/transferOrder/list/page",
                         "body": {"current": 1, "pageSize": 10}}}},
    {"key": "preset_notice", "label": "写站内通知",
     "node": {"id": "notice_upsert", "type": "http",
              "params": {"method": "POST", "path": "/api/transferOrder/notice",
                         "body": {"receiver": "{{meta.warehouse_code or ''}}",
                                  "type": "STOCK_ALERT",
                                  "content": "库存告警：请关注本仓低水位终端"}}}},
    {"key": "preset_alert_scan", "label": "库存告警扫描",
     "node": {"id": "alert_scan", "type": "http",
              "params": {"method": "POST", "path": "/api/terminal/stockAlert/scan"}}},
    {"key": "preset_rag_sync", "label": "RAG 问答（同步）",
     "node": {"id": "rag_chat", "type": "http",
              "params": {"method": "POST", "path": "/oa/chat/sync",
                         "body": {"message": "{{query}}"}}}},
]
