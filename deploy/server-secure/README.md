# 服务器重建与加固手册

> 适用对象：`49.234.49.192`（腾讯云**轻量应用服务器**，不是 CVM；Ubuntu 24.04 / 2 核 3.6G）

## 零、为什么有这套脚本

**2026-09-30，这台服务器被打穿了。** 完整取证记录：

| 项 | 事实 |
|---|---|
| 入侵时间 | `2026-09-30 12:32:56`（中间件部署完成后约 2 小时） |
| 入口一 | **MySQL**：`root` 弱密码 `123456` + 建了 `root@'%'` + 公网开放 |
| 入口二 | **Redis**：无 `requirepass` + `bind 0.0.0.0` + `protected-mode no` |
| MySQL 后果 | 所有库被 `DROP`，留下勒索库 `RECOVER_YOUR_DATA`（要 0.0102 BTC，DATAID `2QQFH`） |
| Redis 后果 | 被写入 4 个后门 key（`backup1`~`backup4`），内容是 base64 编码的 crontab 行，全部指向恶意域名 `kiss.a-dog.top` |
| PostgreSQL | **未被破坏**（8 张表数据完整）——只是运气，不是安全 |
| SSH | 无后门（`authorized_keys` 干净），但**累计 5594 次爆破尝试** |
| 真实业务数据 | **无损失**——28354 条终端库存只在本机 xlsx，从未上过服务器 |

原始证据存档：`D:\env\security-incident-20260930\redis-backdoor-keys.txt`

**根因不是"哪一步配错了"，而是整套默认配置本来就是"裸奔版"**：`deploy/server/01..06` 为了让本机能远程连库调试，把每个中间件都调成了"对公网开放 + 弱密码/无鉴权"。这套配置在公网存活时间是**小时级**。

---

## 一、你要在腾讯云控制台做的（只有你能做）

1. 控制台 → **轻量应用服务器** → 找到实例 `lhins-efltpzqo8`
   （别去 CVM 的安全组页面找，轻量应用服务器用的是「防火墙」，两个入口不互通）
2. 关机 → **重装系统** → 选 `Ubuntu 24.04 LTS` → 设置登录密码
   - 登录密码请用强密码（这台机器被爆破 5594 次，`ubuntu` 是首选目标）
   - **公网 IP 重装后不变**，所以本机 `D:\env\ssh_helper.py` 里的 `PASSWORD` 要改成新密码
3. 防火墙规则**不用动**（按你的要求保持对外）

> 重装完成后告诉我，我接着往下跑。

---

## 二、重装后的重建流程（我来做）

> **2026-10-01 实测：重装后的系统是 Ubuntu 26.04 LTS**（不是原计划的 24.04）。
> 26.04 的默认软件版本比 24.04 高一个大版本，`deploy/server/01-install.sh` 已相应适配：

| 组件 | 24.04 的版本 | **26.04 的版本（当前）** | 影响 |
|---|---|---|---|
| PostgreSQL | 16.15 | **18.6** | 包名 `postgresql-18*`；配置路径 `/etc/postgresql/18/main/` |
| pgvector | 0.6.0 | **0.8.1** | `vector(1024)`、HNSW 用法不变 |
| MySQL | 8.0.46 | **8.4.11** | 移除了 `mysql_native_password`，脚本用的是 `caching_sha2_password`，不受影响 |
| Redis | 7.0.15 | **8.0.5** | 见下方「Redis 加固的版本差异」 |
| RabbitMQ | 3.12.1 | **4.0.5** | 配置文件格式不变 |
| JDK | 17 | 8 / 17 / 21 都装 | JDK 8 给 backend 系，17 给 Nacos（**必须显式指定**，否则解析到 21） |

```
第 0 步  系统层加固（装中间件之前，让机器从第一刻受保护）
         deploy/server-secure/10-system-harden.sh
         → SSH 禁 root 直登 + fail2ban + ufw(只放行22/8000) + 自动安全更新
           + 内核参数 + swap 扩到 4G

第 1 步  装中间件（复用现有脚本，仍是默认弱密码）
         deploy/server/01-install.sh → 02-postgres.sh → 03-mysql.sh
         → 04-redis.sh → 05-rabbitmq.sh → 06-nacos.sh

第 2 步  生成强密码（在本机跑）
         # 凭据文件由部署者手工写入（生成脚本已移除）：credentials.env 需 600 权限
         → 产出 credentials.env（已被 .gitignore 忽略）

第 3 步  上传并执行中间件加固
         ssh_upload.py credentials.env /opt/agent-platform-secure/credentials.env
         ssh_run_script.py 90-harden.sh --root
         → 换掉全部弱密码 + 开鉴权

第 4 步  初始化数据
         建 api_db + 4 张平台元数据表 + 接口登记 + 终端 5 张业务表

第 5 步  部署应用（6 个模块）+ nginx 反代，只对外暴露 8000
```

---

## 三、加固做了什么

| 组件 | 加固前 | 加固后 |
|---|---|---|
| **MySQL** | `root`/`123456`，`root@'%'` 全权限 | `root` 只留 `localhost`；应用改用 `api_user`，**只授权 `api_db`** |
| **PostgreSQL** | `my_user`/`123456`，`pg_hba` 放行 `all` 库 | 强密码；`pg_hba` **收紧到只允许连 `terminal_kb`** |
| **Redis** | 无密码、`protected-mode no` | `requirepass` 强密码；`protected-mode yes`；**`rename-command` 封掉 `CONFIG`/`FLUSHALL`/`SLAVEOF`/`DEBUG`** |
| **RabbitMQ** | `guest`/`guest` 且允许非本机登录 | 专用用户 `aismp` + 强密码；**删除 `guest`** |
| **Nacos** | 无鉴权（谁都能改注册表） | `auth.enabled=true` + token secret + server identity |
| **SSH** | 裸露（5594 次爆破） | **fail2ban**：10 分钟内失败 5 次封 1 小时 |
| **本机防火墙** | 无 | **ufw**：默认拒绝入站，只放行 22 + 应用端口 |

### 关于「数据库端口要不要对外」

你说控制台不改。**所以在 ufw 里做收口**——这不冲突：云防火墙管的是"包能不能到这台机器"，ufw 管的是"到了之后收不收"。

脚本默认 `ALLOW_DB_PUBLIC=no`：**数据库端口不对公网开，只走 localhost**。
既然应用也部署在同一台机器上（你的决策），数据库根本不需要对外——这是最安全的姿势，而且不用动控制台。

如果你确实需要从本机直连数据库调试，两种做法：
- 临时改：`ALLOW_DB_PUBLIC=yes` 重跑 90-harden.sh（不推荐，等于回到裸奔）
- **推荐**：用 SSH 隧道
  ```bash
  ssh -L 13306:127.0.0.1:3306 -L 15432:127.0.0.1:5432 ubuntu@49.234.49.192
  # 然后本机连 127.0.0.1:13306 / 127.0.0.1:15432
  ```

---

## 四、残余风险（必须知道）

1. **服务对外公开 = 攻击面永远存在。** 强密码 + fail2ban + 最小权限能把"自动化扫描机器人"挡在外面，但挡不住针对性的 0day。演示期可以接受，演示完建议收口。
2. **2 核 3.6G 装不下全套。** 现状实测：仅中间件就占了 **2060 MB / 3659 MB**，其中 Nacos 一家就 873 MB（`-Xmx512m`）。再叠 6 个应用必然 OOM。必须做内存预算：
   - 前端**必须 build 后静态托管**（`max dev` 开发模式要 500MB~1GB，生产构建产物几十 MB）
   - 各 JVM 显式限 `-Xmx`（backend/gateway/interface 各 256m，agent 512m）
   - Nacos 视情况降到 `-Xmx384m`，或用 SSH 隧道改成本机注册中心
   - **最直接的方案是给服务器升配**（轻量应用服务器支持在线升配）
3. **凭据管理**：`credentials.env` 一旦泄露等于全部防线失效。它已被 `.gitignore` 忽略，**任何情况下不要提交**。

---

## 五、文件清单

| 文件 | 作用 |
|---|---|
| `10-system-harden.sh` | **系统层加固**：SSH / fail2ban / ufw / 自动安全更新 / 内核参数 / swap |
| `90-harden.sh` | **中间件层加固**：MySQL/PG/Redis/RabbitMQ/Nacos 的密码与鉴权 |
| `95-bind-localhost.sh` | **监听收口**：让中间件只监听 `127.0.0.1`（深度防御第二层） |
| `30-init-data.sh` | **数据初始化**：建 api_db + 平台元数据表 + 账号 + 接口登记 |
| `credentials.env` | 部署者手工维护的强密码表（600 权限，**不进 git**） |
| `credentials.env` | **不入 git**，由上面脚本生成 |

**分工原则**：`10` 管操作系统 → `01..06` 装中间件 → `90` 管密码与鉴权 → `95` 管监听地址 → `30` 灌数据。
每个脚本都是**幂等**的，可以单独重跑。

**为什么 `95` 是必须的**：ufw 是第一层防线，但"服务监听 `0.0.0.0` + 只靠 ufw 挡"是单点依赖——
ufw 一旦被误关、或为了调试临时放行一个端口，服务就立刻回到被入侵前的暴露状态。
应用与中间件同机时，服务只需要 localhost，所以这一层应该做实。

---

## 六、下一次开工前的自检（每次连服务器前跑一遍）

```bash
# 1. 有没有新的勒索库
mysql -uroot -p<pw> -N -e "SHOW DATABASES;"

# 2. Redis 有没有被重新写入 key
redis-cli -a <pw> --no-auth-warning keys '*'

# 3. 有没有新增的计划任务 / 异常登录
sudo crontab -l; ls -la /var/spool/cron/crontabs/
sudo grep -c 'Failed password' /var/log/auth.log

# 4. fail2ban 拦了多少
sudo fail2ban-client status sshd
```
