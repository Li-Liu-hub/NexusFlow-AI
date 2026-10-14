# 03 · SSE 事件协议

> 本文件定义**对话流的服务端推送（SSE）协议**，是前端渲染与后端产出的唯一约定。
> 实现位置：`agent-orchestrator/server.py`（帧格式与推流）+ `agent-orchestrator/graph.py`（载荷组装）。
>
> 数据来源为**实测代码**，非设计稿。

---

## 一、端点与请求

```
POST /api/orchestrator/v1/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

请求体：

```json
{
  "query": "帮我看看 9BZA 的库存健康情况",
  "conversation_id": "orch-a1b2c3d4",
  "suggestion_id": "sug-20261011-001"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `query` | string | ✅ | 用户问题 |
| `conversation_id` | string | 否 | 缺省服务端生成 `orch-<8位hex>`；**传入旧值则续接该会话** |
| `suggestion_id` | string | 否 | 从补货建议卡「提交调拨工单」进入时携带，供 `order` 节点读取 |

请求头（可选，网关无注入时作为回退）：

| 头 | 说明 |
|---|---|
| `X-User-Id` | 用户 ID |
| `X-User-Warehouse` | 仓码 |
| `X-User-Role` | 业务角色 |

响应头：`Content-Type: text/event-stream`。

---

## 二、帧格式

标准 SSE 三段式，UTF-8，**中文不转义**：

```
event: <事件名>\n
data: <JSON 或字符串>\n
\n
```

实现（`server.py:89`）：

```python
def sse(event: str, data) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
```

---

## 三、事件类型总览

| 事件 | 时机 | `data` 类型 | 出现次数 |
|---|---|---|---|
| `start` | 接流后立刻 | object | 1（首帧） |
| `progress` | 每完成一个图节点 | object | N（节点数） |
| `agent_step` | 节点旁路推流（工具调用等中间态） | object/any | 0~N |
| `result` | 全部完成 | object | 1（终帧） |
| `error` | 任一步异常 | string | 0~1（**出现即终止**） |

**正常流的骨架**（以补仓调拨路线为例）：

```
start → progress(supervise) → progress(fetch) → progress(algorithm)
      → progress(report) → progress(verify_numbers) → progress(critic)
      → progress(respond) → result
```

---

## 四、各事件载荷

### 4.1 `start`

```json
{ "conversation_id": "orch-a1b2c3d4" }
```

前端应在此记下 `conversation_id`，后续请求带回以续接会话。

### 4.2 `progress`

```json
{ "node": "algorithm", "produced": ["algo_result", "strategy"] }
```

| 字段 | 说明 |
|---|---|
| `node` | 节点名，取值见第五节 |
| `produced` | 本节点新写入 State 的字段名列表 |

**用途**：前端据此显示「思考过程」进度。**注意**：这是节点级进度，不等于 LLM 逐字输出。

### 4.3 `agent_step`

节点内旁路推流的原始 chunk，结构随节点而异（如工具调用记录）。前端可折叠展示「思考过程」。

### 4.4 `result`（终帧）

```json
{
  "agent_response": "根据 9BZA 当前库存……",
  "cards": [ /* 见第六节 */ ],
  "strategy": "transfer_only",
  "route": "replenish",
  "suggestion_id": "sug-20261011-001",
  "conversation_id": "orch-a1b2c3d4"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `agent_response` | string | **Markdown 正文**（前端需渲染 md） |
| `cards` | array | 富卡片列表，可能为空数组 |
| `strategy` | string | `transfer_only` / `procure_only` / `mixed` / `""`，**仅补仓路线有值** |
| `route` | string | 命中的业务路线，见下表 |
| `suggestion_id` | string | 建议 ID，Redis 缓存 30 分钟 |
| `conversation_id` | string | 会话 ID（与 `start` 一致） |

**`route` 取值**（三条业务路线）：

| `route` | 路线 | 节点序列 |
|---|---|---|
| `replenish` | 补仓调拨 | `supervise → fetch → algorithm → report → verify_numbers → critic → respond` |
| `chart` | 图表 | `supervise → chart_plan → chart_build → respond` |
| `kb` | 知识问答 | `supervise → digital_human → respond` |

> `order`（建单）**不是独立意图**，而是补货建议卡上「提交调拨工单」按钮的落点，
> 由 `suggestion_id` 触发，复用 `replenish` 的会话上下文。

### 4.5 `error`

```json
"event: error\ndata: \"Java agent 调用失败：...\"\n\n"
```

⚠️ **`data` 是 JSON 字符串，不是对象** —— 前端需按字符串解析（见第七节陷阱）。

三类错误文案：

| 文案前缀 | 触发原因 |
|---|---|
| `Java agent 调用失败：` | `aismp-agent` / `digital-human` 返回异常 |
| `终端业务接口调用失败：` | 终端业务 REST 调用异常 |
| `编排异常：<类型>: ` | 兜底（任何未预期异常） |

收到 `error` 后**连接即关闭**，不会再发 `result`。前端必须复位「流进行中」状态。

---

## 五、图节点清单

`progress.node` 的可能取值：

| 节点 | 所属路线 | 职责 |
|---|---|---|
| `supervise` | 全部 | LLM 结构化分类，定 `route` |
| `fetch` | replenish | 取数智能体（工具循环 ≤3 轮） |
| `algorithm` | replenish | 确定性算法（**数字全在代码里算**） |
| `report` | replenish | 组装卡片 + `verify_numbers` 自检 |
| `verify_numbers` | replenish | 代码 diff 闸门：卡片数字 === 算法输出 |
| `critic` | replenish | 双闸门：数字不一致 / 语义不合理 → 回炉 |
| `chart_plan` | chart | LLM 选「维度 + 样式」 |
| `chart_build` | chart | 按维度取数组装 `labels` / `series` |
| `digital_human` | kb | 转发数字员工内核 |
| `respond` | 全部 | 纯组装，推 `result` |

**回炉环**：`critic` 判定不合格时回流（数字不一致 → 回 `report`；取数不全 → 回 `fetch`），
上限 2 次（`recursion_limit=20` 双保险），超限则降级放行。

---

## 六、卡片协议（`cards` 数组元素）

所有卡片都有 `type` 字段，前端按 `type` 分发渲染。

### 6.1 `transfer_list` · 调拨建议清单

```json
{
  "type": "transfer_list",
  "title": "调拨建议",
  "columns": ["型号", "缺口", "调出仓", "可调"],
  "rows": [
    { "modelCode": "HG6543A", "modelName": "运营商光猫 HG6543A", "gap": 28, "fromWarehouse": "9B00", "canTransfer": 28 }
  ]
}
```

### 6.2 `procurement_list` · 采购清单

```json
{
  "type": "procurement_list",
  "title": "采购清单",
  "columns": ["型号", "缺口", "建议采购"],
  "rows": [
    { "modelCode": "HG6543A", "gap": 28, "needProcure": 28 }
  ]
}
```

> `strategy=mixed` 时，`cards` 会**同时**包含 `transfer_list` 与 `procurement_list`。

### 6.3 `replenish_reminder` · 补货提醒

```json
{ "type": "replenish_reminder", "title": "库存告警", "text": "以下型号低于水位线", "level": "warn" }
```

### 6.4 `replenish_unavailable` · 无需补货

```json
{ "type": "replenish_unavailable", "title": "库存充足", "text": "当前无型号低于水位线" }
```

> **空清单合法** —— 无缺口时返回此卡片，不是错误。

### 6.5 `transfer_order` · 调拨工单

```json
{
  "type": "transfer_order",
  "title": "已提交调拨申请",
  "orderNo": "TR20261011001",
  "fromWarehouse": "9B00",
  "fromWarehouseName": "东宁市分公司",
  "toWarehouse": "9BZA",
  "toWarehouseName": "东宁市新区分公司",
  "status": "APPLIED",
  "statusLabel": "待确认出库",
  "items": [{ "modelCode": "HG6543A", "quantity": 28 }]
}
```

失败/信息不足时同一 `type` 下 `title` 会变为「还差一点信息」或「提交申请失败」，此时可能无 `orderNo`。

> **前端渲染约定**：`orderNo` 作为**第一行主标题**（放大 + 等宽字体），
> 仓名/仓码降为副行（这是 2026-10-11 与用户确认的改版）。

### 6.6 `chart` · 图表

```json
{
  "type": "chart",
  "chartType": "bar",
  "title": "各仓库存分布",
  "labels": ["9B00", "9BZA"],
  "series": [{ "name": "库存量", "data": [23460, 4894] }],
  "note": "数据来自库存聚合接口原值"
}
```

| `chartType` | 渲染方式 |
|---|---|
| `bar` | 条形图 |
| `pie` | 饼图 |
| `line` | 折线图 |
| `table` | 表格 |
| `metric` | 单值指标卡 |

> **图型选择权在前端**：后端只给「初始样式」，门户卡片头提供
> 「条形 / 饼图 / 折线 / 表格」切换按钮，**同一份 `labels`/`series` 纯前端换图**，
> 不发请求、不重跑 LLM（数据仍是接口原值）。

---

## 七、实现陷阱（已踩过，务必遵守）

### 7.1 `error` 的 `data` 是字符串

因为 `sse()` 对 data 做 `json.dumps`，传入的字符串会被再包一层引号：

```
data: "Java agent 调用失败：..."     ← 注意外层引号
```

前端若按对象解析会抛错。**正确做法**：`try { JSON.parse(raw) }`，失败则直接当文本用。

### 7.2 `progress` ≠ 逐字输出

`progress` 是**节点级**事件。LLM 的中间推理通过 `agent_step` 旁路推流，
但**不保证逐 token**。想要打字机效果，只能对 `agent_response` 做前端分字动画。

### 7.3 流未结束时切会话 → 状态泄漏（严重）

前端若在 `result`/`error` 到达前切换会话，必须：
1. **主动 abort** 当前 `fetch` 的 `ReadableStream`，否则连接泄漏
2. **复位「忙碌」标志**，否则该会话永久锁死无法再提问

> 对应修复 commit `fb29eb9`（「流未结束切会话导致 aBusy 永不复位 + SSE 连接泄漏」）。

### 7.4 避免 `useEffect` 依赖闭环

若在 `useEffect` 中加载元数据 → setState → 触发依赖变化 → 再次加载，会形成**请求风暴**，
实测会撞上网关限流（10/s）刷 429。

> 对应修复 commit `adeb0b5`（「useEffect 依赖闭环导致请求风暴」）。
> 解法：标签映射经 `ref` 传入，加载用 `didInit` 单次保护。

### 7.5 会话粘版本

每个会话在**创建时**固化 `orch_conversation.graph_version`。
之后管理端在画布上发布新版图，**只对新会话生效**，进行中会话继续用旧版本跑完。
回滚的实现是「复制旧版为新的 published」，不修改历史记录。

---

## 八、前端最小消费示例

```js
const res = await fetch('/api/orchestrator/v1/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
  body: JSON.stringify({ query, conversation_id: convId }),
  signal: abortController.signal,          // ← 支持中途取消（见 7.3）
});

const reader = res.body.getReader();
const decoder = new TextDecoder();
let buf = '';

while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  buf += decoder.decode(value, { stream: true });

  const frames = buf.split('\n\n');
  buf = frames.pop();                       // 末段可能不完整，留到下轮

  for (const frame of frames) {
    const ev = /^event: (.+)$/m.exec(frame)?.[1];
    const raw = /^data: ([\s\S]*)$/m.exec(frame)?.[1];
    if (!ev || raw === undefined) continue;

    let payload;
    try { payload = JSON.parse(raw); } catch { payload = raw; }  // ← 见 7.1

    switch (ev) {
      case 'start':      setConvId(payload.conversation_id); break;
      case 'progress':   pushStep(payload.node); break;
      case 'agent_step': pushTrace(payload); break;
      case 'result':     renderResult(payload); break;   // cards + markdown 正文
      case 'error':      showError(String(payload)); break;
    }
  }
}
```

---

## 九、关联文件

| 内容 | 位置 |
|---|---|
| 帧格式与推流实现 | `agent-orchestrator/server.py`（`sse()` / `gen()`） |
| 终帧载荷组装 | `agent-orchestrator/graph.py`（`respond()`） |
| 卡片结构定义 | `agent-orchestrator/graph.py`（各路线组装处） |
| REST 接口契约 | `docs/contracts/02-业务接口契约.md` |
| SSE 链路验收 | `deploy/server-docker/canvas_link_test2.py`（9/9 拓扑级实验） |
