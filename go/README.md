# Go 重写（platform-backend）

> 分支：`feat/go-rewrite`。方案与分期见 `docs/Go重写方案.md`。
> 本文件是交付记录：每一期做了什么、验证命令、与 Java 版的偏差清单。

## 运行

```bash
# 本机开发（MySQL 需可达，账号见 deploy/server-docker）
export MYSQL_DSN='api_user:***@tcp(127.0.0.1:3306)/api_db?parseTime=true&loc=Local&charset=utf8mb4&timeout=5s'
go run ./cmd/platform-backend     # 监听 :7529，context-path /api

# Linux 二进制（部署到服务器）
GOOS=linux GOARCH=amd64 go build -o platform-backend ./cmd/platform-backend
```

环境变量（缺省值=本机开发）：`HTTP_ADDR`、`MYSQL_DSN`、`DB_MAX_OPEN/IDLE`、
`GATEWAY_BASE_URL`、`DIGITAL_HUMAN_GW_PREFIX`、`DIGITAL_HUMAN_BASE_URL`、
`AISMP_CLIENT_ACCESS_KEY/SECRET_KEY`。

## P0：门户鉴权切片（已完成 2026-10-24）

- `internal/platform/auth`：`/api/auth/login|me|logout`，行为逐条对照 `AuthController`
  （含 7655013 的 400/401/403 真实状态码语义；`/auth/me` 保持「200 + error」的老行为）。
- 口令 SHA-256 无盐 hex，令牌 UUID 落 `a_auth_token`（24h、登录顺手清过期）。
- 主动偏差仅一处：登录时清理过期令牌失败降级为告警（Java 直接 500），不挡登录。

## P1：backend 其余 7 个控制器（已完成 2026-10-24）

| 包 | 对照 Java | 端点 |
|---|---|---|
| `tokenadmin` | TokenAdminController + InnerTokenController | `/api/admin/token/**`（11 个）、`/api/inner/token/check·charge` |
| `innerapi` | InnerApiController | `/api/inner/user/byAccessKey`、`/api/inner/interfaceInfo/byPathMethod`、`/api/inner/userInterfaceInfo/invokeCount` |
| `market` | UserController / InterfaceinfoController / UserInterfaceinfoController | `/api/user/**`、`/api/interfaceInfo/**`、`/api/userInterfaceInfo/**`（session 鉴权） |
| `dhproxy` | DigitalHumanAdminProxyController | `/api/admin/digitalHuman/**`（登录态→网关 AK/SK 签名→内核） |

关键对照点（都有单测钉住）：

- 守卫语义：`/admin/token/**` 的 Bearer + ADMIN/SERVICE 检查、`@AuthCheck(admin)` 的
  40100/40101、代理的「未登录 200+code 40100」（对齐 deploy/acceptance.py 的 C1–C4）。
- 网关三接口：`byPathMethod` 精确优先 + Ant 通配兜底（`*`/`**`/`?`，见 innerapi 测试）。
- 签名：`SHA256(body + "." + secretKey)` 小写 hex；body 为 null 时拼 `"null."`（网关对 GET 同口径）。
- MyBatis-Plus 语义：逻辑删除（`isDelete=0`）、`updateById` 只更新非空字段、
  `QueryWrapper(entity)` 非空字段等值、分页缺省 current=1/pageSize=10。
- 口令摘要：`MD5("aismp-platform" + password)`（跨语言基准向量测试）。
- session：内存存储（Java `store-type: none` 同构），JSESSIONID cookie，24h 不活跃过期。

### P1 有意偏差（除注明外均为 P5 切换前要补齐或接受的）

1. **RabbitMQ 暂不接入**（最大偏差）：
   - 缓存失效通知（user/interface 变更 → 网关）不发送，只打 `WARN`；
     网关 Redis 缓存 30 分钟 TTL 自愈，等价于 Java 版「MQ 发送失败仅记日志」的降级形态。
   - `invoke.count.queue` 消费者不实现——该队列的消息是 **Java 原生序列化**（见
     `RabbitMqConfig` 的血泪注释），Go 侧无法干净解析；P5 切换时需生产端（网关）与
     消费端同时改 JSON，只换一端会更糟。
2. 排序字段白名单化：Java 版 MP `orderBy` 直拼列名（有注入面），Go 版只放行
   `[A-Za-z0-9_]{1,64}`。
3. `/interfaceInfo/list/page` 的描述模糊搜索沿用 Java 的 `LIKE content`（该列并不存在，
   传描述会报 SQL 错）——保留同款错误以便对照，前端本就不传。
4. NPE → 错误码映射：Java 若干拆箱 NPE（如额度 leftNum 为 null）落 500 SYSTEM_ERROR，
   Go 版给等价的 `code:50000` 文案，行为面一致。
5. `/user/register` 缺字段时 Java `return null`（200 空 body），Go 同样空 body。

## 验证

```bash
cd go && go vet ./... && go test ./... -count=1
# 全部包 ok（auth / tokenadmin / innerapi / market / dhproxy）
```

## 尚未移植（P2+）

- `aismp-agent` 业务（终端台账/补货/调拨）→ P2
- `aismp-digital-human` 内核 → P3
- 网关 → P4；灰度切换（nginx upstream 指到 Go）→ P5
