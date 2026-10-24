# 开发规范（Go 技术栈）

硬约束，非建议。冲突时改代码，不改规范。

**零信任**：不依赖 agent 自觉。可机械检查者，一律脚本化（`scripts/audit-*.sh`，挂于 `go test` 前置）。

适用范围：`NexusFlow-AI/go/` 下全部 Go 服务（gateway / platform-backend / terminal-agent / kernel / orchestrator）。

## 工作方式（XP）

小步、结对、持续集成、简单设计、随时重构。

### 结对

| 角色  | 职责  | 约束  |
| --- | --- | --- |
| Writer | 依功能点文档实现 | 仅读需求与规范；存疑即停机 |
| Reviewer | 独立判定实现正确性 | 不看 Writer 推理过程；不重写业务实现 |

Reviewer 的独立性是本流程唯一价值来源。知情推理则沿同一思路审查，「需求理解偏差」不可发现。

Reviewer 职责：核对验收清单、核对本规范、独立验证（测试、检查器、自撰反例测试）。输出须为具体问题（文件 + 行 + 失败原因）或「未发现问题 + 检查范围」，不接受主观判断。

Reviewer 可撰写反例测试、修改命名与注释；禁止重写业务实现。逻辑缺陷须打回 Writer，附「文件 + 行 + 反例测试」。同一功能点往返逾 3 轮未全绿，熔断，交人类裁决。

### 停机上报

需求歧义、边界未定义、或与本规范冲突时，Writer 立即停止并列出阻塞点，禁止自行补全需求。

### 节奏

* 一个功能点一个提交，绿灯即推送；红灯不进入下一步
* 提交前：`go vet ./...` 通过、`go test ./... -count=1` 全绿、验收清单逐条确认
* 仅实现当前功能点所需，不为「以后可能要」预留抽象
* 绿灯后如发现重复或结构问题，先重构再继续，重构须保持测试绿

## 分层

    cmd/<service>/ → internal/platform/<domain>（HTTP 层，gin） → service 层 → repository 层 → MySQL / PostgreSQL

仅允许向下依赖。

* `cmd/**` 只做装配（读配置 → 连库 → 注册路由 → 优雅退出），不写业务逻辑
* `internal/platform/<domain>` 的 handler 是请求上下文适配层：只做「解析请求 → 调 service/repo → 写响应」；业务判断写在 handler 即无人验证（该层只允许冒烟级测试），判断一律下沉 service
* service 层禁止 import `github.com/gin-gonic/gin`、`net/http`（ mechanically checked；service 只吃参数、吐结果/错误）
* repository 层持有 SQL 与 `database/sql`；SQL 字符串仅允许出现于 repo 文件与 `internal/*/repository*.go`
* 事务边界仅在 service 层（`db.BeginTx` 传入 repo），repo 不自行 `Begin`
* 跨服务调用只允许经 HTTP 契约（如 kernel → backend `/inner/token/*`），禁止直连对方的库——服务边界即数据边界

## 命名

| 对象  | 规则  | 例   |
| --- | --- | --- |
| 文件  | snake_case，按职责分段 | `transfer.go`、`handler_test.go` |
| 类型  | PascalCase | `TransferOrder` |
| 常量  | 驼峰（Go 惯例）+ 具名 | `maxTurns`、`surplusThreshold` |
| 数据库字段 | 新表 snake_case | `tenant_id`、`created_at` |
| 领域字段 | PascalCase（导出）/ camelCase（私有） | `OrderNo`、`orderNo` |

* 行与领域类型分离：`UserRow`（DB 原样）与 `User`（领域，可空字段已规整）分离，于 repo 层转换
* JSON 字段名 = 对外契约（与前端/旧 Java 消费方逐字段对齐），改动即破坏兼容，须在提交信息注明
* 历史表（api_db / terminal_db 存量表）列名为驼峰，属**遗留契约**，禁顺手改名；新表一律 snake_case

## 数据

* 时间：`DATETIME` 列，数据库会话时区统一 Asia/Shanghai（DSN `loc=Local` 固化）；时间格式化唯一实现于 `httpkit`（`"2006-01-02 15:04:05"`）与 repo 的 `isoTime`（`"2006-01-02T15:04:05"`，Jackson 契约），其他地方禁止手写 layout 常量
* 日期：`DATE` 列，后缀 `_date`；写入时固化，查询期不换算
* ID：UUID v4（`uuid.NewString()`），不透明串；自增主键仅用于既有表的内部关联
* 区间：半开 `[start, end)`；重叠判定 `a.start < b.end && a.end > b.start`
* 租户谓词：`terminal_db` 配置表的业务查询须含 `tenant_code`（管理路径传空串且显式注释）；`api_db` 的 `a_user/a_auth_token` 按平台账号隔离，无需租户谓词，但须含 `isDelete = 0`（MyBatis-Plus 逻辑删除语义的 Go 等价物）

## 错误

* 业务错误统一 `*ErrBiz{Code, Msg}`（或等价的领域错误类型），禁用裸 `errors.New` 表达业务失败——`errors.New` 仅用于内部哨兵（如 `ErrNotFound`）
* 跨租户/跨仓访问返回 404 语义（`40400`），禁用 403（403 确认资源存在性）；仅权限角色不足才用 `40101`
* 用户可见 `Msg` 用中文，不含表名、SQL、堆栈
* MySQL 错误码须于 repo 层译为领域错误：1062 → 已存在冲突，1452 → 关联不存在；不得把 driver 错误直接透给 handler
* 404 与 409 判定顺序：先判存在性与归属，再判状态
* HTTP 状态码语义沿用现状：业务失败 = HTTP 200 + `code` 非 0（`GlobalExceptionHandler` 行为）；仅鉴权握手（/auth/login、网关 401/403）用真实状态码——改此约定须全前端联动，属人类决策

## 状态跃迁

正常路径以单条 SQL 原子完成：

```sql
UPDATE transfer_order SET status = 'OUTBOUND', outbound_at = NOW()
 WHERE orderNo = ? AND status = 'APPLIED';
```

`RowsAffected() == 1` 视为成功。`== 0` 时原因不明，允许于同一事务内补查一次：

```go
var exists bool
err := tx.QueryRowContext(ctx,
    `SELECT COUNT(*) > 0 FROM transfer_order WHERE orderNo = ?`, orderNo).Scan(&exists)
if exists {
    return biz(50001, "当前状态不允许该操作")
}
return nil, ErrNotFound
```

补查仅在失败分支执行。禁止以「先查再改」替代原子更新，非禁止判定失败原因。库存扣减类（`leftNum > 0`、`terminalStatus = '可用'` 的条件 UPDATE）同此规则，计数不符必须回滚事务。

## 代码

* 禁 `interface{}`/`any` 承载数据，用具体类型或泛型；`map[string]any` 仅允许出现在「列名动态未知」的通用行扫描（`queryRows`/`rowsToMaps`）与测试
* 禁用带状态的包级可变量（`var x = ...` 全局可写）；配置经 `config` 显式传入，缓存经 struct 持有
* service 签名：`fn(ctx, repo, input) (Result, error)`
* repo 签名：`fn(ctx, tx *sql.Tx | nil, ...) (Row, error)`；`tx == nil` 时用自身连接（单语句便利路径），多语句必须传 tx
* 多表写入须于同一事务内，审计与业务同事务；例外：计量/落账类旁路写（token charge、审计失败告警）失败不得影响主流程，只打 WARN——计量是旁路，不能因为记账失败让对话报错
* 魔法数字提为具名常量并注明单位（如 `surplusThreshold = 200 // 台`）
* 可空列统一 `sql.NullXxx` 扫描 + 包级 `ns/ni/nf/ts` 规整为指针；禁止把 `[]byte` 时间直接塞进 JSON

## 注释

* 每个文件顶部须有文件头，说明职责与对应的 Java 原实现（便于对照审查）
* 非自明函数（逾 15 行、有副作用、含业务规则）以一行说明用途
* 关键选择写明「为何用此方案」；与 Java 版的有意偏差必须在文件头偏差清单中登记
* 一行可述者不写两行，删除形容词
* 禁：注释掉的代码、无主 TODO、与代码不符的过期注释、装饰性分隔线
* 破例须人类授权：`// rule-exception: <理由> —— 批准人 <who>`；Writer 禁自行添加，Reviewer 遇无授权破例一票否决

## 验证

六层，各司其职，任一缺失则该类问题无人负责。

| #   | 手段  | 职责  | 命令  |
| --- | --- | --- | --- |
| 1   | TDD | 业务规则正确性 | `go test ./... -count=1` |
| 2   | 边界检查 | 依赖方向 | `scripts/audit-imports.sh` |
| 3   | SQL 扫描 | 租户/逻辑删除谓词完整性 | `scripts/audit-sql.sh` |
| 4   | 变异验证 | 前三层有效性 | 每条核心规则手工变异一次（见「测试」） |
| 5   | 运行库检查 | 存量数据一致性 | `scripts/db-check`（连真库跑一致性断言） |
| 6   | 手工验收 | UI 手感与视觉 | —   |

另有 `scripts/audit-rules.sh`（挂于 `go test` 前置）：`rule-exception` 授权、repo 层自开事务、`.Only` 等价物（`t.Skip` 滥用）、`fmt.Println`/`print` 调试残留、业务路径裸 `errors.New`、跨租户 403 文案。

* 第 4 层不可省：全绿仅示实现与测试自洽，不示测试有效
* 第 5 层不可省：单测运行于内存替身/独立临时 schema，未触及真实库的真实数据形态（历史驼峰列、脏行）
* 第 6 层不可替代：交互与视觉无工具可测

## 测试

* 每用例独立数据：默认用 repo 接口的内存替身（仅 handler 冒烟与纯逻辑）；打真库的集成测试以 `//go:build integration` 标签隔离，`go test -tags=integration` 时对**每用例独立创建的临时 schema** 执行并在结束后删除——禁止用例间共享数据
* 禁 sqlmock：mock 的 SQL 语义是假的，测了等于没测
* 一个 `it` 一个断言，中文需求句式（测试名即需求句，如 `TestOutbound_串码不在调出仓位时拒绝出库`）
* 每条核心规则执行一次变异验证：翻转条件/改边界值/删谓词，测试必须变红；未变红视为未测；**改回后须重跑全绿确认**（恢复动作本身也会出错，曾把变异留在代码里过）
* 对照测试优先：移植类功能点（Java → Go）先固化「Java 版实际行为」为表驱动用例，再让 Go 实现过同一组用例

## 提交

* 一个功能点一个提交，对应功能点文档编号
* 格式：`<type>(<范围>): <描述>`（type: feat/fix/refactor/test/docs/deploy；范围用包名或分期号，如 `feat(go): P2 终端业务 —— …`）
* 提交前：`go vet ./...` 通过、无 `fmt.Println` 调试残留、无注释代码、验收清单逐条确认
* 绿灯即推送（`git push github feat/go-rewrite`），红灯不进入下一步

## 自查

* [ ] 业务查询含租户谓词（或 `isDelete = 0`，按表归属）
* [ ] 跨租户/越权返回 404 语义
* [ ] 多表写入同事务；旁路写失败不阻塞主流程
* [ ] 门户身份只信网关注入的 `X-User-*`，未读客户端自报参数（网关已剥离伪造头）
* [ ] service 未 import `gin`/`net/http`
* [ ] SQL 只出现在 repo 文件
* [ ] JSON 字段名未动（对外契约）
* [ ] 反直觉处已写明原因
