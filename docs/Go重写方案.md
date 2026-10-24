# Go 重写方案

> 分支：`feat/go-rewrite`（从 `main` @ `7655013` 切出）
> 立项：2026-10-24
> 状态：P0、P1 已落地（见 `go/README.md`），P2 起待排期

---

## 一、先说清楚「整个项目」有多大

下面的数字是 `git ls-files` 现场统计的，不是估的。

| 组件 | 语言 | 文件数 | 行数 | 控制器 | Go 重写 |
|---|---|---:|---:|---:|---|
| `aismp-backend`（含 `aismp-gateway`） | Java | 79 | 5,477 | 8 | ✅ 计划 |
| `aismp-agent`（终端业务 + RAG） | Java | 72 | 8,016 | 5 | ✅ 计划 |
| `aismp-digital-human`（内核） | Java | 56 | 7,328 | 10 | ⚠️ 见 §五 |
| `agent-orchestrator` | Python | 53 | 9,075 | — | ⚠️ **已删除**（见 §五修正） |
| `aismp-orchestrator`（Java 图编排，NexusFlow-AI） | Java | 43 | 4,652 | 1 | ⚠️ 新增范围，见 §五 |
| **小计（服务端）** | | **260** | **29,896** | **23** | |
| `aismp-frontend-master/admin/user` | JSX | — | — | — | ❌ 不做 |

其他要一并对齐的契约：

- **HTTP 接口**：`@GetMapping` 74 + `@PostMapping` 51 + `@PutMapping` 6 + `@DeleteMapping` 11 = **142 个方法级映射**，分布在 **25 个控制器类**上
- **数据库**：MySQL **52 张表**（`api_db` 16 + `terminal_db` 36）+ PostgreSQL **6 张表**（`terminal_kb`）
- **统一响应体**：`BaseResponse{code, data, message}` + `ErrorCode` 8 个错误码
- **鉴权**：门户登录 `a_user` + `a_auth_token`（SHA-256 口令、UUID 不透明令牌、24h）；网关 AK/SK 签名

> **这意味着什么**：29,896 行不是「一个周末」的量。瓶颈不在敲代码，在**逐条验证行为等价**——142 个接口 + 52 张表的查询语义，每一个都要有对照测试。

---

## 二、为什么值得做（以及唯一真正的理由）

服务器是 **腾讯云轻量 4C / 3.63 GiB，且不升配**（见项目长期记忆）。当前 11 个容器里跑着：

- 3 个 JVM（backend / agent / digital-human）
- 1 个 Python（orchestrator）+ 1 个 Nacos（本身也是 JVM）
- MySQL + PostgreSQL + Redis + RabbitMQ + nginx

**Spring Boot 单实例常驻 RSS 通常在 350–500 MB**，三个就是 1.1–1.5 GB。Go 同功能服务通常 **30–60 MB**。也就是说这条重写最硬的收益是**在 3.63 GiB 的机器上腾出 ~1.2 GB**，其次是冷启动（JVM 15–40s → Go < 200ms）和部署形态（单个静态二进制 vs jar + JRE）。

**这不是「换个语言更爽」的重构，是一次以内存为目标的移植。** 如果目标其实是「代码更好写/更好看」，那这笔账不划算——风险见 §六。

---

## 三、目标架构

```
go/
├── go.mod                      单一 module，避免多仓库
├── cmd/
│   ├── platform-backend/       ← aismp-backend
│   ├── terminal-agent/         ← aismp-agent
│   ├── kernel/                 ← aismp-digital-human
│   └── gateway/                ← aismp-gateway
└── internal/
    ├── config/                 环境变量配置（12-factor，先不接 Nacos）
    ├── httpkit/                BaseResponse / ErrorCode / gin 中间件
    ├── store/                  database/sql 连接池
    ├── platform/               ← backend 领域
    │   ├── auth/               登录 / me / logout
    │   └── user/
    ├── terminal/               ← agent 领域（台账/补货/调拨）
    └── kernel/                 ← 内核领域
```

选型（都是「迁移成本最低」而非「最潮」）：

| 关注点 | 选择 | 理由 |
|---|---|---|
| HTTP | `gin` | 中间件模型最接近 Spring Filter/Interceptor，团队上手快 |
| DB | `database/sql` + 原生 SQL | **现有 Java 代码本来就是 JdbcTemplate 裸 SQL**，1:1 对照可验证；上 ORM 会引入二次语义偏差 |
| 配置 | 环境变量 | 现有 `application.yml` 的值本来就大量走 `${ENV:default}` |
| 日志 | `log/slog` | 标准库，结构化，无依赖 |
| 服务发现 | 暂不做 | 先跑通单服务；Nacos 客户端留到 P5 |

**冻结项**：数据库 schema 一行不改；HTTP 路径 / 请求体 / 响应体字段名一律不变。前端和脚本无需改动即可对着 Go 版跑。

---

## 四、分期与验收

每一期的验收标准都是「**可执行命令 + 期望输出**」，不是「代码写完了」。

| 期 | 范围 | 验收 | 状态 |
|---|---|---|---|
| **P0** | 地基 + 平台鉴权切片：`/auth/login`、`/auth/me`、`/auth/logout`、`/health` | `go test ./...` 全绿；`xn_zg/123456` 换到真 token；400/401/403 语义与 `7655013` 一致 | ✅ **已完成** |
| **P1** | `aismp-backend` 其余 7 个控制器：用户 CRUD、接口管理、配额、令牌、管理端代理 | 门户 + 管理台**不改一行前端**能登录并打开用户列表 | ✅ **已完成**（MQ 通知暂不接入，见 go/README.md 偏差清单） |
| **P2** | `aismp-agent` 业务：终端台账、补货建议、调拨闭环状态机 | `deploy/server-docker/functional_test.sh` 中 agent 相关用例全 PASS | ⬜ |
| **P3** | `aismp-digital-human` 内核：Agent 装配、工具 API_CALL、RAG 检索 | 知识问答端到端返回带引用 | ⬜ |
| **P4** | 网关：反向代理 + AK/SK 签名 + JWT + 配额计量 + Redis 归属 | 142 个接口经 Go 网关转发成功率 100% | ⬜ |
| **P5** | 灰度切换：nginx upstream 指到 Go，Java 版并行保留 | 连续 24h 无 5xx，内存实测下降 ≥ 800 MB | ⬜ |

排序原则：**先做没有下游依赖、又能被独立验证的**。P0→P1 是平台层自底向上；P2 业务层价值最高但需要 P1 的鉴权；网关（P4）**最后做**——它要代理所有路径，实现早了会被 P1–P3 的接口变更反复打回。

真实工期（按「一个人 + AI 辅助、每期都要写对照测试」估）：**P1 ≈ 3–4 天，P2 ≈ 1.5 周，P3 ≈ 2 周，P4 ≈ 1 周，P5 ≈ 2 天**。合计 **5–6 周**。任何低于这个量级的计划都会在 P2 的调拨状态机上崩掉。

---

## 五、编排层：Python 已删，现在是 Java（Spring AI Alibaba Graph）

> **2026-10-24 修正**：本节初版基于本仓库的旧快照写作（当时 `agent-orchestrator` 还是
> Python + LangGraph，9,075 行）。实际上编排层已在 **NexusFlow-AI 仓库**完成迁移并上线：
>
> - `c41435c` 用 Spring AI Alibaba Graph 重建编排层；`a63ee09` 删除 Python 层；
>   `05053ad` replenish / chart 两条路线迁入 Java 图
> - 新的 `aismp-orchestrator`：**43 个 Java 文件 / 4,652 行**，节点含
>   Kb / ChartPlan / ChartBuild / Fetch / Algorithm / Critic / Supervise /
>   VerifyNumbers / Report / Respond（见 `graph/nodes/`）
> - 服务器 2026-10-21 起跑的就是 Java 版：镜像 `jsyl6/ap-orchestrator:20261021-graph4`
> - 内核（aismp-digital-human）也在 spring-ai-alibaba 2.0.0-M1.1 上（ReactAgent）

对 Go 重构的影响：

- Go 生态**同样没有 Spring AI Alibaba Graph 的等价物**——StateGraph、条件边、
  SSE 五种事件帧（契约逐字保留、前端零改动）、记忆压缩、graph 版本粘滞，
  在 Go 里都要手写。原估计不变：**自研状态机约 2–3 周**，且流式契约必须逐帧对照。
- 编排层现在是一个普通的 Java 服务（不再是「不能动的 Python」），纳入重写范围
  变为一个新分期（P6 或并入 P3），收益是再省一个 JVM（容器实测约 200–400 MB）。

---

## 六、风险（写在这里是为了将来能对照）

| 风险 | 影响 | 缓解 |
|---|---|---|
| **双实现漂移** | Java 版继续在 `main` 上修 bug，Go 版不知情 | 重写期间 `main` 的每个 commit 都要过一遍「是否影响 Go 侧」；或约定 P0 起 `main` 只接受 hotfix |
| **调拨状态机语义偏差** | 状态机有 5 个状态、3 条迁移边和「驳回退可用」的副作用，最容易移植错 | P2 必须先把现有 Java 行为写成表驱动测试，再照测试实现 Go |
| **RAG 质量下降** | Spring AI 的切片/召回参数散落在配置里 | P3 前先固化「同一批问句 + 期望引用」的评测集 |
| **银弹幻觉** | 以为 Go 自动更快，实际慢在 SQL 和 nginx | 只在 P5 用真实压测数据判定成败 |

---

## 七、P0 已交付内容

见 `go/README.md`。核心事实：

- `go/internal/platform/auth` 完整复刻 `AuthController` 的三段行为，含 `7655013` 修的 400/401/403 语义
- 口令校验走 **SHA-256 无盐 hex**，与 `a_user.password_hash`（`8d969eef…`）逐字节一致，有单测钉住
- 令牌是 **UUID 不透明串**落 `a_auth_token`，24h，登录时顺手清过期——与 Java 版同构
- 不引入 ORM，SQL 与 `AuthRepository.java` 逐句对应，便于 diff 审查
