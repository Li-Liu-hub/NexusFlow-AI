# NexusFlow AI

> 面向运营商固网终端的 **AI 智能体平台**：在 API 开放平台地基上，统一网关管理 REST API / AI Agent /
> MCP 三类服务的鉴权、限流与计量；落地场景是终端「补货建议 → 调拨闭环」的全流程自动化。
>
> 核心主张：**业务数字全部来自查库 + 确定性算法，LLM 不参与算数。**

后端 Java 21 / Spring Boot 4，编排层 Python + LangGraph，前端 React + 原生 JS，全套容器化部署。

---

## 一、这个平台解决什么

运营商的终端库存分散在多级仓库里。「哪个仓库该补货」原本靠人盯表格发现，
NexusFlow AI 把它从**人工发现**变成**系统主动发现 → AI 给建议 → 数字落到工单**的闭环：

```
定时扫描库存（纯 SQL）
   ↓ 低于水位线
写告警通知给账管
   ↓ 点告警
数字员工给补货建议（卡片 + 数字来自算法）
   ↓ 点「提交调拨工单」
调拨单 APPLIED 待确认出库
   ↓ 调出方仓管导入串码确认出库
OUTBOUND 已出库（在途）
   ↓ 调入方仓管确认入库
DONE 已完成 —— 库存此时才真正 B 减 A 增
```

调拨状态机的关键约束：**在途不动库存**。只有 `DONE` 之后 B 仓减、A 仓增才原子发生；
任一环节驳回都回退到 `REJECTED`，且把已锁定量放回可用量。

---

## 二、架构总览

```
浏览器（业务门户 / 管理台）
        │
        ▼
┌──────────────────────────────────────────────────┐
│  nginx  :80   唯一对外端口                        │
│  /        → 业务门户    /admin/ → 管理台           │
│  /api/**  → 反代到网关                            │
└──────────────────────────────────────────────────┘
        │
        ▼
┌──────────────────────────────────────────────────┐
│  aismp-gateway  :8090    Spring Cloud Gateway    │
│  ① 剥离客户端伪造身份头 → ② 注入真实身份           │
│  ③ interface_info 精确匹配 → ④ 限流 → ⑤ 计量      │
└──────────────────────────────────────────────────┘
        │
        ├─► /api/terminal/**, /api/transferOrder/**  → aismp-agent :8124
        │      终端业务：库存聚合 / 调拨状态机 / 告警扫描
        │      ReAct 智能体 + RAG(pgvector) + 工具
        │
        ├─► /api/agent/**                            → aismp-digital-human :8126
        │      数字员工内核：装配（角色/技能/知识库/工具）+ ReAct
        │
        └─► /api/orchestrator/**                     → agent-orchestrator :8125
               Python FastAPI + LangGraph 编排（只管路由与质检）
               三条业务路线，见下表
```

**编排层三条业务路线**（`agent-orchestrator/graph.py`，1499 行）：

| 路线 | 节点序列 | 说明 |
|---|---|---|
| `replenish` 补仓调拨 | `supervise → fetch → algorithm → report → verify_numbers → critic → respond` | 含**回炉环**（critic 不合格回流重算） |
| `chart` 图表 | `supervise → chart_plan → chart_build → respond` | LLM 选维度 + 样式，数据取接口原值 |
| `kb` 知识问答 | `supervise → digital_human → respond` | 转发数字员工内核 |

**画布编排**（增量能力）：编排逻辑不写死，由管理端画布发布的图驱动（`GRAPH_SOURCE=dsl`），
支持版本固化与回滚、按会话粘版本。

---

## 三、几个关键设计

**1. 数字红线双闸门** —— LLM 会说错数字，所以数字不进 LLM 的嘴。

- `agent-orchestrator/tools/algorithms.py`：补货量、缺口、周转天数全由代码算，LLM 只决定「跑哪个算法、传什么参数」。
- `graph.py` 的 `verify_numbers` 节点：做代码 diff 闸门，卡片上的数字必须与算法输出逐项相等，不一致直接回炉重算。
- `canvas/loader.py`：发布期强制护栏——图里 `report` 节点不得直连 `respond`，绕开闸门直接 422 拒绝发布。

**2. 画布 DSL → LangGraph，不写 eval**。`loader.py` 全文件只有两个循环，节点定义白名单化；
图里的环靠条件边回指 + `MAX_REVISIONS=2` + `recursion_limit=20` 三重收口，
`tests/test_canvas_loader.py` 用拓扑等价性断言证明「画布上的图」和「代码里的图」一致。

**3. 网关身份「先剥离再注入」**。客户端传来的身份头一律当伪造剥掉，再由网关按 AK/SK 签名结果注入真实身份，
然后才匹配 `interface_info`；签名带 nonce + Redis SETNX 防重放，计量异步走 RabbitMQ 落库。

**4. 调拨 CAS 整单回滚**。出库确认按串码批量更新，`updated != distinct.size()` 时整单事务回滚——
避免「一半出库、一半在途」的脏状态。

**5. 工具沙箱**。SQL 工具用词边界正则拦写操作（避免 `create_time` 这类列名误伤），
API 调用工具按身份校验接口白名单，越权直接拒绝。

---

## 四、模块与技术栈

| 模块 | 技术栈 | 端口 | 说明 |
|---|---|---|---|
| `aismp-backend` | **Spring Boot 4.0.7 · JDK 21** · MyBatis-Plus | 7529 | 管理后端：用户 / 接口元数据 / 计量账本 |
| `aismp-gateway` | Spring Cloud Gateway（WebFlux） | 8090 | 鉴权 + 限流 + 计量（HTTP + Nacos lb，无 Dubbo） |
| `aismp-agent` | **Spring Boot 4.0.7 · JDK 21** · Spring AI | 8124 | 终端业务 + RAG + 工具 + 调拨状态机 |
| `aismp-digital-human` | **Spring Boot 4.0.7 · JDK 21** | 8126 | 数字员工内核（装配 + ReAct） |
| `agent-orchestrator` | Python 3.12 · LangGraph · FastAPI | 8125 | 编排层（路由 / 质检 / 画布 DSL 加载） |
| `aismp-frontend-user` | Vite（原生 JS 门户） | — | 业务门户（对话 / 库存 / 工单 / 通知） |
| `aismp-frontend-admin` | React 18 · Vite · reactflow 11 | — | 管理台（Agent / 技能 / KB / MCP / 画布 / 配额 / 审计） |

> `aismp-frontend-master` 是早期 Umi 工程，**已非主战场**，保留仅作参考。

**基础设施**：

| 服务 | 版本 | 用途 |
|---|---|---|
| MySQL | 8.4 | 业务数据（库 `api_db`） |
| PostgreSQL + pgvector | 18 + 0.8.x | 知识库向量（库 `terminal_kb`，**1024 维**）+ 编排图存储 |
| Redis | 8 | 会话、nonce 防重放、建议缓存（30 分钟） |
| RabbitMQ | 4 | 计量消息异步落库 |
| Nacos | **3.2.4** | 服务注册与配置（**Nacos 3.x 天生重，容器内存至少 768m**） |

**模型**：对话 **MiMo `mimo-v2.6-flash`**；嵌入 **硅基流动 `BAAI/bge-m3`（1024 维）**。

---

## 五、快速开始

### 5.1 容器化部署

```bash
# ① 本机：构建产物（jar + 前端静态文件）
bash deploy/server-docker/collect-artifacts.sh

# ② 本机：推送镜像到 Docker Hub
bash scripts/push-dockerhub.sh --tag 20261011

# ③ 服务器：铺配置 + 起容器 + 验证
bash scripts/deploy.sh
```

部署后入口：

| 入口 | 地址 |
|---|---|
| 业务门户 | `http://<服务器IP>/` |
| 管理台 | `http://<服务器IP>/admin/` |

内置账号（口令统一 `123456`）：`xn_zg` 账管 / `xn_kg`、`dn_kg` 仓管 / `admin` 管理员。

### 5.2 本机开发

```bash
# 1) 建库（结构 + 配置种子，不含业务数据）
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < db/mysql/01-schema-api_db.sql
mysql -h127.0.0.1 -uroot -p --default-character-set=utf8mb4 < db/mysql/10-seed-api_db.sql
psql -h127.0.0.1 -U postgres -d terminal_kb -f db/pg/01-schema-terminal_kb.sql
psql -h127.0.0.1 -U postgres -d terminal_kb -f db/pg/10-seed-terminal_kb.sql

# 2) 导入业务数据（终端 + 仓位）：local-init/gen-terminal-sql.py 从 xlsx 生成导入 SQL

# 3) 一键启动全部服务
bash scripts/start-all.sh
```

`scripts/start-all.sh` 已封装本机三个必要动作：

1. `unset SERVER_PORT SERVER__PORT` —— 这两个环境变量会**劫持 `server.port`**
2. `export ESBUILD_BINARY_PATH=D:/env/esbuild/esbuild.exe` —— 中文路径下的 exe 会被终端安全软件拦
   （现象：`Cannot read file "package.json": winapi error #5`）
3. `export NODE_OPTIONS=` —— 避免外部注入的 safe-delete 守卫拦 `rimraf`

### 5.3 环境变量

**Java 侧**（仓库不含真值，由环境或 `.env` 提供）：

```powershell
setx MIMO_API_KEY "<MiMo 对话模型 Key>"
setx SILICONFLOW_API_KEY "<硅基流动嵌入 Key>"
setx MYSQL_APP_PW "<MySQL 应用账号口令>"
setx REDIS_PW "<Redis 口令>"
```

**编排层**：`cp agent-orchestrator/.env.example agent-orchestrator/.env` 后填值
（网关三元组 / LLM 三项 / `GRAPH_SOURCE=dsl`）。

---

## 六、目录结构

```
NexusFlow-AI/
├── aismp-backend/           # 管理后端（含 aismp-gateway / aismp-common / aismp-client-sdk）
├── aismp-agent/             # 终端业务 + RAG
├── aismp-digital-human/     # 数字员工内核
├── agent-orchestrator/      # LangGraph 编排层
│   ├── graph.py             # 三条业务路线
│   ├── canvas/              # 画布 DSL → StateGraph
│   ├── tools/algorithms.py  # 确定性算法（数字红线所在）
│   └── tests/               # 单测
├── aismp-frontend-user/     # 业务门户
├── aismp-frontend-admin/    # 管理台
├── db/                      # 建库 SQL（结构 + 配置种子，无业务数据）
│   ├── mysql/               # api_db
│   └── pg/                  # terminal_kb（向量 + 编排图）
├── deploy/                  # 部署
│   ├── server-docker/       # 全量容器化（当前方案）
│   ├── server-secure/       # 服务器加固脚本
│   └── acceptance.py        # 全链路端到端验收
├── local-init/              # 本机启动 + 分批次 registry SQL + E2E 脚本
└── scripts/                 # 运维脚本
    ├── push-dockerhub.sh    # 推镜像到 Docker Hub
    ├── deploy.sh            # 部署到服务器
    ├── push-gitee.sh        # 推送代码到 Gitee
    └── start-all.sh         # 本机一键启动
```

---

## 七、运维脚本

| 脚本 | 用途 | 常用方式 |
|---|---|---|
| `scripts/push-dockerhub.sh` | 构建并推送镜像到 Docker Hub | `bash scripts/push-dockerhub.sh --tag 20261011` |
| `scripts/deploy.sh` | 部署到远端服务器（可只更应用） | `bash scripts/deploy.sh [--apps-only] [--check]` |
| `scripts/push-gitee.sh` | 推送代码到 Gitee | `bash scripts/push-gitee.sh [branch]` |
| `scripts/start-all.sh` | 本机一键启动全部服务 | `bash scripts/start-all.sh [--stop]` |

---

## 八、验收与测试

| 脚本 | 覆盖 | 结果 |
|---|---|---|
| `deploy/acceptance.py` | 全链路端到端 | **50/50** |
| `local-init/transfer-flow-e2e.py` | 调拨闭环 | **24/24** |
| `local-init/stock-alert-closed-loop-e2e.py` | 告警 → 建议 → 工单闭环 | **18/18** |
| `deploy/server-secure/_r12/_r14/_r15-*.py` | 门户公网 / 写入授权 / 数据范围 | **12/12 · 6/6 · 15/15** |
| `deploy/server-docker/canvas_link_test2.py` | 画布编排拓扑级实验 | **9/9** |
| `agent-orchestrator/tests/` | 编排层单测 | **117 passed** |
| 知识库评估（20 题 × 3 遍） | 关键词命中 + LLM 判官 | **92.9% / 90.0%** |

---

## 九、开发约定

- 密钥（API Key / `.env` / 令牌 / 凭据文件）**任何情况下不进仓库**（`.gitignore` 已覆盖）
- 提交信息说清「做了什么」，中文即可
- **前端改动必须亲眼验证渲染** —— 「接口 200 + 构建通过」不等于页面正常
  （曾有 vite `base` 配错导致管理台白屏、布局算法发散导致画布空白，均只有真机可发现）
- **查库前先用 `information_schema.columns` 核实列名** —— 用错列名会**静默返回空**而不报错

---

## 十、常见问题

| 症状 | 原因 | 解法 |
|---|---|---|
| 网关 403 但签名没错 | 接口未登记，或用了 `localhost`（网关只听 IPv4） | 查 `interface_info`（**列名是 `url`**）；改用 `127.0.0.1` |
| 查询一直返回空但不报错 | 列名写错 | 先查 `information_schema.columns` |
| 管理台白屏 | vite `base` 不是 `/admin/` | 见 `collect-artifacts.sh` 注释 |
| 应用注册成 127.0.0.1 | jar 里写死 `discovery.ip` | compose 里显式设固定 IP `172.28.0.11~15` |
| `esbuild winapi error #5` | 中文路径下的 exe 被拦 | 设 `ESBUILD_BINARY_PATH` 到纯 ASCII 路径 |
| 端口与配置不符 | `SERVER_PORT` / `SERVER__PORT` 劫持 | `unset` 后显式 `--server.port=` |
| PG 容器起不来 | postgres 18 改了 PGDATA 约定 | 挂 `/var/lib/postgresql`（不是 `/data`） |
| 推送 Gitee 挂死 | credential helper 弹 GUI | 用 `scripts/push-gitee.sh`（已绕过） |

---

## 十一、License

见仓库根目录 `LICENSE`。
