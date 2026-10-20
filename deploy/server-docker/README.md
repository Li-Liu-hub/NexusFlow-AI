# 服务器 Docker 化部署包

> **整平台容器化**：5 个中间件 + 5 个应用 + nginx 全部跑在 Docker 里，
> 数据落服务器本地 bind mount，**唯一对外端口 80**。

---

## 目录结构

```
server-docker/
├── docker-compose.full.yml      # ★ 唯一的 compose 定义（11 个服务）
├── .env.example                 # 凭据模板 → 复制为 .env 填写（chmod 600）
├── Dockerfile.jdk21             # 五个 Java 服务共用（backend / gateway / agent / digital-human / orchestrator）
│                                # 编排层原 Python 镜像（orchestrator-image/）已于 2026-10-20 移除
├── nginx/nginx.conf             # 唯一对外端口 80（门户 + 管理台 + /api 反代）
├── init/
│   ├── mysql-init/01-init.sh    # 容器首次初始化（建库 / 账号 / 授权）
│   └── pg-init/01-init.sh       # 建库 + 装 pgvector 扩展
├── migrate/
│   ├── export-from-server.sh    # 在旧服务器导出数据
│   └── import-to-docker.sh      # 在新容器导入数据
├── backup/
│   ├── save-all-images.sh       # 本机：11 个镜像一次 save（离线迁移用）
│   └── load-all-images.sh       # 服务器：load 回镜像
├── collect-artifacts.sh         # 本机：构建 jar + 前端产物
├── 75-prepare-server.sh         # 服务器：铺目录与配置（不启动容器）
├── bootstrap.sh                 # 服务器：一键部署（装 Docker → up → 健康检查）
├── 80-update-apps.sh            # 服务器：更新应用镜像并滚动重启
├── deploy-from-registry.sh      # 服务器：从镜像仓库部署
├── functional_test.sh           # 部署后功能冒烟
├── inventory.sh                 # 服务器数据盘点（凭据运行时输入）
└── jars/ web/                   # collect 产物（不入 git）
```

> **只有一份 compose**。曾并存的 `docker-compose.yml` / `docker-compose.apps.yml`
> 已删除 —— 三份文件版本漂移，旧版曾用 `redis:7` / `nacos:v2.2.3` 的错误版本。
> 服务器上会把它归位为 `compose/docker-compose.yml`（仅改文件名）。

---

## 快速部署（四步）

### ① 本机：收集产物

```bash
bash deploy/server-docker/collect-artifacts.sh
# → 构建 5 个 jar + 门户 + 管理台 + 编排层源码
```

> ⚠️ 本机前置：JDK 21 在 `/d/env/jdk-21`；`ESBUILD_BINARY_PATH` 指向纯 ASCII 路径
> （奇安信会拦中文路径下的 exe）；管理台构建 `base` 必须是 `/admin/`。

### ② 本机：推送镜像（任选其一）

```bash
# A. 在线推送（推荐，服务器直接 pull）
bash scripts/push-dockerhub.sh --tag 20261011

# B. 离线迁移（服务器拉不到 Docker Hub 时）
bash deploy/server-docker/backup/save-all-images.sh
# → 一次 save 出 ~1.9G（共享层单份），scp 上传后再 load
```

### ③ 服务器：铺配置 + 起容器

```bash
# 先传 4 个文件到 /tmp（见 75-prepare-server.sh 头部注释）
sudo -E REGISTRY=jsyl6 TAG=20261011 bash 75-prepare-server.sh   # 铺目录与配置
sudo docker compose -f /opt/agent-platform/compose/docker-compose.yml \
     --env-file /opt/agent-platform/compose/.env up -d
```

或直接一键：

```bash
sudo bash bootstrap.sh
```

### ④ 验证

```bash
bash functional_test.sh          # 功能冒烟
curl -I http://localhost/        # 门户
curl -I http://localhost/admin/  # 管理台
```

---

## 访问入口

| 入口 | 地址 | 说明 |
|---|---|---|
| 业务门户 | `http://<服务器IP>/` | 对话、库存、调拨工单、通知 |
| 管理台 | `http://<服务器IP>/admin/` | Agent / 技能 / 知识库 / MCP / 画布 / 配额 / 审计 |
| 统一 API | `http://<服务器IP>/api/**` | 反代到网关 8090 |

> **只对外暴露 80**。中间件与应用的端口一律不 publish；
> Docker 会绕过 ufw，已用 `DOCKER-USER` 链 + `docker-user-firewall.service` 收口。

---

## 内存预算（3.63 GiB 硬约束）

| 容器 | mem_limit | 说明 |
|---|---|---|
| mysql | 400m | buffer pool 128m |
| nacos | 768m | standalone + RocksDB，**Nacos 3.x 天生重，低于此值会起不来** |
| agent / digital-human | 480m ×2 | Xmx360m |
| backend | 384m | Xmx192m |
| gateway | 340m | Xmx256m |
| postgres / rabbitmq / orchestrator | 256m / 192m / 384m | 编排层是 JVM，堆 256m + 元空间余量 |
| redis / nginx | 96m / 32m | |
| **合计** | **~3.2G** | 留 ~400M 给系统，6G swap 兜底 |

**实测**：调优后 `MemAvailable` 885 → 1379 MB，swap 已用 1371 → 419 MB。
排障请用 `/sys/fs/cgroup/.../memory.stat` 的 `anon` 字段 —— `systemd-cgtop` 的 memory 列
含可回收 page cache，会把 22MB 的服务显示成 110MB。

---

## 网络与安全

- **只有 nginx :80 对外**，中间件端口零映射
- 凭据只存 `compose/.env`（chmod 600，**不入 git**）
- Nacos 开鉴权；MySQL root 不做远程；应用账号最小权限
- 应用注册 Nacos 必须用**固定 IP** `172.28.0.11~15`
  （jar 里 `spring.cloud.nacos.discovery.ip` 写死 `127.0.0.1`，不覆盖会注册成回环，
  网关按 `lb://aismp-backend` 永远找不到）

---

## 常用运维

```bash
cd /opt/agent-platform/compose

# 查看状态 / 日志
sudo docker compose --env-file .env -f docker-compose.yml ps
sudo docker compose --env-file .env -f docker-compose.yml logs -f --tail=100 ap-gateway

# ⚠️ 改配置后必须 up -d，restart 不生效
sudo docker compose --env-file .env -f docker-compose.yml up -d

# 停止
sudo docker compose --env-file .env -f docker-compose.yml down
```

---

## 回滚

- **配置回滚**：`compose/` 下所有文件改动前先备份，直接还原 + `up -d`
- **应用回滚**：`docker compose pull ap-<服务>` 指定旧 TAG，再 `up -d`
- **画布编排回滚**：管理台画布页「版本管理」→ 任一历史版本「设为当前」（复制旧版为新 published，不篡改历史）

---

## 已知坑速查

| 现象 | 原因 | 解法 |
|---|---|---|
| 应用注册成 127.0.0.1 | 未覆盖 `discovery.ip` | compose 里显式设固定 IP |
| 管理台白屏 | 构建时 `base` 不是 `/admin/` | 见 `collect-artifacts.sh` 注释 |
| 门户 / 管理台 403 | 接口漏登记 `interface_info` | **注意列名是 `url` 不是 `path`** |
| PG 容器起不来 | postgres 18 改了 PGDATA 约定 | 必须挂 `/var/lib/postgresql`（不是 `/data`） |
| nacos 容器起不来 | 缺 `NACOS_AUTH_TOKEN`（即使关鉴权也要） | 显式提供；注意 `JVM_XMN` 默认 512m 比 Xmx 还大 |
| 端口被 Docker 绕过 ufw 暴露 | Docker 直接改 iptables | 用 `DOCKER-USER` 链收口 |
| 关键文件 | `deploy/server-docker/容器化完成报告.md`、`画布部署记录-2026-10-11.md` | |
