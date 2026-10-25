# NexusFlow AI（Go 全栈版）

> [!IMPORTANT]
> **这是一个比赛演示项目（Competition Demo），不是生产级系统。**
> 数据为脱敏样例，演示账号口令固定为 `123456`；未做多副本、备份、审计合规与容量规划。
> **欢迎提 Issue 与 PR**（见[参与贡献](#参与贡献)）。
>
> 本分支 `feat/go-rewrite` 为 **Go 全栈重写**：五个服务全部 Go，线上零 JVM。
> Java 血统（Spring Boot 4 / Spring AI Alibaba Graph）见 `main-nexus` 分支，
> 两分支共用同一套数据库 schema 与前端，接口契约逐字段对齐。

> 面向运营商固网终端的 **AI 智能体平台**：统一管理 REST API / AI Agent / MCP
> 三类服务的鉴权、限流与计量；落地场景是终端「补货建议 → 调拨闭环」的全流程自动化。
>
> 核心主张：**业务数字全部来自查库 + 确定性算法，LLM 不参与算数。**
>
> 仓库：<https://github.com/Li-Liu-hub/NexusFlow-AI>

---

## 一、解决什么问题

终端库存分散在多级仓库里，「哪个仓该补货」原本靠人盯表格。NexusFlow AI 把它变成
**系统主动发现 → AI 给建议 → 数字落到工单**：

```
定时扫描库存（纯 SQL）
   ↓ 低于水位线
写告警通知给账管
   ↓ 点告警
数字员工给补货建议（卡片 + 数字来自算法）
   ↓ 点「提交调拨工单」
APPLIED 待确认出库 → OUTBOUND 在途 → DONE 已完成
   ↓ 任一环节驳回
REJECTED（已锁定量放回可用量）
```

关键约束：**在途不动库存**。只有 `DONE` 之后，B 仓减、A 仓增才原子发生。

---

## 二、架构（Go 全栈 · 零 JVM）

```mermaid
flowchart TB
    subgraph CLIENT["浏览器"]
        P["业务门户 portal.html"]
        A["管理台 React 18 + TypeScript"]
    end

    N["nginx :80 · 唯一对外端口<br/>/ → 门户　/admin/ → 管理台<br/>/admin/docs/openapi/ → 按项目粒度接口文档"]

    G["gateway :8090（Go）<br/>三通道鉴权（门户Bearer / AK-SK签名 / MCP令牌）<br/>身份注入 · interface_info 匹配 · 限流 · 计量"]

    BE["platform-backend :7529（Go）<br/>门户鉴权 · Token配额 · 网关内部接口 · yuapi管理三件套"]
    TA["terminal-agent :8124（Go）<br/>台账聚合 · 调拨状态机 · 库存告警 · RAG混合检索"]
    KH["kernel :8126（Go）<br/>数字员工内核：配置装配（按version热重建）+ ReactAgent 工具循环"]
    OR["orchestrator :8125（Go）<br/>画布编排：DSL校验/版本固化/会话粘版本/回滚 + 五帧SSE"]
    BZ["bizsuite :8130（Go）<br/>合同信抽取（9态/100批/双路LLM/21项）+ AI工单建单（7类/防重/补写）"]

    BZ --> MY
    MY[("MySQL<br/>api_db：平台+终端库存+auth<br/>terminal_db：数字员工配置+会话+审计")]
    PG[("PostgreSQL terminal_kb<br/>pgvector 1024 维 RAG + orch_graph 画布版本")]
    RD[("Redis<br/>门户令牌 / nonce 防重放 / AK-SK 缓存")]

    P --> N
    A --> N
    N --> G
    G -->|"/api/auth/** /api/admin/token/**"| BE
    G -->|"/api/terminal/** /api/transferOrder/** /oa/**"| TA
    G -->|"/api/agent/** /api/admin/** /digitalHuman/**"| KH
    G -->|"/api/orchestrator/**"| OR
    G -->|"/api/contract/** /api/ticket/**"| BZ
    OR -->|"画布节点：KB问答走内核<br/>fetch 走 agent（经网关签名）"| G
    KH -->|"知识检索工具 → /oa/chat/sync（网关签名）"| G
    TA --> MY
    TA --> PG
    KH --> MY
    KH -->|"对话配额 /inner/token/*"| BE
    G --> RD
    BE --> MY
```

两个「必须过网关」的约定：**服务方取库存/知识检索必须经网关签名**（身份注入与
interface_info 登记都在网关上）；**内核知识检索回调 agent**（切分与嵌入只有 agent 有）。

| 服务 | 端口 | 内存实测 | 职责 |
|---|---|---|---|
| `gateway` | 8090 | ~3.7 MiB | 三通道鉴权、AK/SK 验签+防重放、身份注入、限流、计量 |
| `platform-backend` | 7529 | ~3.5 MiB | 门户鉴权、Token 配额、网关内部接口、yuapi 管理三件套、管理端代理 |
| `terminal-agent` | 8124 | ~4.0 MiB | 台账聚合、调拨状态机、告警扫描、RAG 混合检索与向量库 |
| `kernel` | 8126 | ~9 MiB | 数字员工装配（version 热重建）、ReactAgent 工具循环、管理面、审计 |
| `orchestrator` | 8125 | ~2.8 MiB | 画布 DSL 解释器、版本固化/回滚、五帧 SSE |
| `bizsuite` | 8130 | ~4 MiB | 合同信 9 态抽取、AI 工单建单（防重/补写） |

> Java 版对照：仅 backend 一个 JVM 容器实测 244 MiB；Go 全栈五个服务合计 ~23 MiB。

---

## 三、三个核心特性（均已上线实测）

### 1. RAG 检索调优（terminal-agent `/oa/chat/sync`）

多查询扩展（LLM×1）→ **向量（pgvector 余弦）+ 关键词（2-gram 覆盖率）双路召回** →
**RRF 融合**（只用名次，k=60）→ **Rerank 精排**（bge-reranker-v2-m3，3s 超时降级）→ Top-5
→ 上下文增强回答。查询先经一次 LLM 完成「重写 + 分类」（intro/process/role/operation/trouble），
检索按分类过滤收口。**每一层可降级**：扩展失败退原问、关键词异常退纯向量、rerank 失败沿用
RRF 顺序。入库管线：段落优先切分（800 字符，句末标点回看 120 硬切）→ bge-m3 嵌入 → pgvector。
文档管理：管理台上传（txt/md/csv/json ≤10MB）→ 账本 + 重索引（原文存增量表）。

### 2. 自定义 Agent 与热更新（kernel）

管理台配置模型/提示词/温度/工具/知识库 → 按 version 缓存装配结果，**配置一变（version+1）
下次对话自动重建，无需重启**（已实测）。ReactAgent 工具循环统一接入四类工具：
`SQL_TEMPLATE`（SqlGuard 只读守卫+参数白名单）、`KB_RETRIEVAL`（回调 agent 混合检索）、
`API_CALL`（网关签名，params 模板 `${var}` 替换）、`MCP`（远端 tools/call）。统一审计
（a_tool_call_log）。对话入口/出口经 backend `/inner/token/check|charge` 做配额闸门与落账。

### 3. 画布编排与版本管理（orchestrator `/orchestrator/v1/graph/**`）

JSON DSL（entry/nodes/edges/conditional）→ 校验五规则（结构完整、type 白名单、边引用完整、
**数字红线护栏 verify_numbers/respond 画布删不掉**、report 不得直连 respond）→ 发布固化为
`published v{N}`（旧版本永久保留）→ **会话粘版本**（orch_conversation.graph_version，已有
会话继续用原版本，新会话用最新）→ 回滚 = 旧版本再发布。执行：最大步数 20（对齐
recursionLimit），单节点访问超限判业务流程回路。API：draft/publish/versions/rollback/debug
（草稿调试不发布直接执行）。

---

### 4. 合同信抽取与 AI 工单建单（bizsuite）

合同信：上传通知起 9 态状态机（UPLOADED→…→DONE），100 条/批推进；
双路 LLM 抽取（正文→集团端点、审签单 HTML→Qwen 端点）→ JSON Schema 校验
（21 项合同信息：未知字段拒绝/类型/必填）→ 非空合并（正文优先）回写。
工单：LLM 7 类分类 + 参数生成；requestId 唯一约束防重复提交（重复返回首次
结果）；远端建单成功本地失败只补写不重建；PC/APP 双端、语音转写文本同入口。

---

## 四、三条不妥协的设计

1. **数字红线**：库存数字全部来自 SQL 聚合与确定性算法（健康建议、告警判定、校验比对），
   LLM 只负责组织语言。`verify_numbers` 节点在画布上删不掉。
2. **在途不动库存**：调拨出库只改状态到「在途」，入库确认才原子换仓换状态，计数不符整单回滚。
3. **契约冻结**：JSON 字段名与前端/旧消费方逐字段对齐（含 Jackson 的时间格式），
   数据库 schema 只做纯增量。

---

## 五、快速开始

### 5.1 服务器（Docker，全 Go）

五个二进制放 `/opt/agent-platform/go-bin/`，步骤见
[deploy/server-docker/全Go切换手册-2026-10-24.md](deploy/server-docker/全Go切换手册-2026-10-24.md)。

### 5.2 本机

```bash
cd go
export MYSQL_DSN='api_user:***@tcp(127.0.0.1:3306)/api_db?parseTime=true&loc=Local&charset=utf8mb4&timeout=5s'
export TERMINAL_DSN='api_user:***@tcp(127.0.0.1:3306)/terminal_db?...'
export PG_DSN='postgres://my_user:***@127.0.0.1:5432/terminal_kb?sslmode=disable'
export MIMO_API_KEY=... SILICONFLOW_API_KEY=... REDIS_ADDR=127.0.0.1:6379

go run ./cmd/gateway &          # :8090
go run ./cmd/platform-backend & # :7529
go run ./cmd/terminal-agent &   # :8124
go run ./cmd/kernel &           # :8126
go run ./cmd/orchestrator &     # :8125
```

环境变量逐项见各 `cmd/*/main.go` 头注释与 `internal/config`。

### 5.3 接口文档（OpenAPI，按项目名称粒度）

```
GET /admin/docs/openapi/index.json                  项目目录（新项目丢 <project>.json 即接入）
GET /admin/docs/openapi/{gateway,platform-backend,terminal-agent,kernel,orchestrator}.json
```

---

## 六、测试

```bash
cd go
go vet ./... && go test ./... -count=1   # 8 包全绿
```

覆盖：鉴权契约（400/401/403 语义）、调拨状态机四重校验、SqlGuard 只读守卫、
Ant 通配匹配、RRF/关键词/切分、画布校验五规则与图解释器（回路/最大步数）、
**MiMo 工具名全量重发回归**（流式解析不拼接）、会话存取、签名跨语言基准向量。

---

## 七、目录结构

```
go/
├── cmd/
│   ├── gateway/            统一网关
│   ├── platform-backend/   平台后端
│   ├── terminal-agent/     终端业务 + 知识检索
│   ├── kernel/             数字员工内核
│   └── orchestrator/       画布编排器
├── internal/
│   ├── config/ httpkit/ store/    公共：配置 / BaseResponse / MySQL
│   ├── gateway/                   过滤链 + 反代
│   ├── platform/
│   │   ├── auth/ market/ tokenadmin/ innerapi/ dhproxy/   backend 领域
│   ├── terminal/                  台账 + 调拨 + KB 端点
│   ├── kb/                        RAG：混合检索/RRF/切分/embedding/rerank/pgvector
│   ├── kernel/                    ReactAgent/工具/管理面/会话/审计
│   ├── orchestrator/              五帧 SSE + 画布接入
│   └── canvas/                    DSL 校验/图解释器/版本存储
docs/openapi/               按项目名称粒度的接口文档（nginx 静态服务）
deploy/server-docker/       部署：docker-compose.go.yml + 全Go切换手册
db/ local-init/             数据库 schema 与种子（与 Java 分支共用）
aismp-frontend-admin/       管理台（TypeScript + React 18）
aismp-frontend-user/        门户
```

---

## 八、参与贡献（欢迎 Issue / PR）

1. Fork → 从 `feat/go-rewrite` 切分支
2. 遵循 [docs/开发规范-Go技术栈.md](docs/开发规范-Go技术栈.md)（分层依赖、service 不碰
   gin、SQL 只在 repo、业务错误 200+code、一个功能点一个提交）
3. 提交前：`go vet ./... && go test ./... -count=1` 全绿
4. PR 描述关联 Issue，说明验证方式

## License

MIT
