# 1Panel 可视化纳管方案（全量容器化之后）

> 你的要求：**全部容器化之后，引入 1Panel 做服务器可视化管理。**

---

## 0. 前提变了：现在 1Panel 是合适的选择

我最早（今天上午）不建议 1Panel，理由是：

> 1Panel 的应用商店 / 数据库 / 网站三大块**全部建立在 Docker 之上**，它只管理「自己用 Docker 部署的东西」。
> 而你当时那套是**原生 apt + systemd**，1Panel 一个都认不出来，装完只是个仪表盘。

**全量容器化之后这个前提消失了** —— 11 个服务全是容器，正好是 1Panel 的一等公民。
所以顺序是 ① 全量容器化 → ② 装 1Panel，**这个顺序是对的，不能颠倒**。

---

## 1. ⚠️ 三个会直接踩坑的点（查官方文档 + 实测得出，不注意会装挂或装了不好用）

### 坑 1｜面板端口：默认是**随机 10000+**，但仍必须避开 8000

> ⚠️ **我第一版写错了**，这里按源码更正。网上很多博客（包括我参考的那几篇）说"1Panel 默认用 8000 端口"，
> 那是旧版信息。**v2.3.2 的实际源码**是：
> ```bash
> # install.sh 第 872 行
> DEFAULT_PORT=$(expr $RANDOM % 55535 + 10000)
> ```
> 即默认端口是 **10000–65535 之间的随机数**，并且它会 `panel_port_occupied()` 检查占用。

**所以真正的约束是**：8000 已经被我们门户占着，而 `--port` 你要是填 8000，脚本会提示端口被占用让你重选。
**处理：显式指定一个高位端口**（脚本里默认 `PANEL_PORT=23456`），别依赖随机值 —— 随机值你每次都得去查。

装完后：
```bash
# 关键：不要在 ufw 里放行它，用 SSH 隧道访问
ssh -L 23456:127.0.0.1:23456 ubuntu@49.234.49.192 -N
# 然后本机浏览器开 http://127.0.0.1:23456/<安全入口>
```

ufw 默认放行 `lo`（loopback），所以**即使端口被 ufw 挡住，SSH 隧道照样通** →
**零新增对外暴露面**，和现有加固方向一致。

### 坑 2｜⭐ 1Panel 会**自动改 ufw**，还会想**改写我们的 `daemon.json`**

这两条是读 `install.sh` 源码发现的，**都会破坏我们已经做好的加固配置**：

**(a) 它会自己放行面板端口**（源码 933~938 行）：
```bash
log "$TXT_FIREWALL_OPEN_PORT $PANEL_PORT"
ufw allow "$PANEL_PORT"/tcp
```
→ 所以 `--post` 步骤会**把这个 ufw 放行删掉**（脚本里已实现）。

**(b) 它会想替换 `/etc/docker/daemon.json`** —— 这是最危险的一条：
```bash
--configure-accelerator <y|n>     Choose whether to configure Docker registry mirrors
--replace-daemon-json <y|n>       Choose whether to replace existing Docker daemon.json
```
**1Panel 的默认加速器配置里很可能包含 `docker.m.daocloud.io`——而实测那个源拒绝你的命名空间 `jsyl6`。**
一旦它把 `daemon.json` 覆盖掉，**服务器会静默地拉不到你自己的镜像**，表现是 pull 报 403/404，
而你会以为是网络问题。

→ **必须传 `--configure-accelerator n --replace-daemon-json n`**（脚本里已做），
并且在安装完成后**校验 `daemon.json` 里还留着 `1ms.run`**（脚本 `--install` 的第 3 步会查）。
回滚办法：把 `registry-mirrors` 改回
`["https://docker.1ms.run","https://hub.rat.dev","https://mirror.ccs.tencentyun.com"]` 后 `systemctl restart docker`。

> **顺带更正我第一版的另一处错误**：我写"安装脚本想要 80/443"——**源码里没有这回事**。
> 基础安装不碰 80/443；80/443 是后来用「网站」功能装 OpenResty 时才会占。
> 我们本来就**不用它的网站功能**（nginx 是容器，且「只暴露 8000」已定），所以这条不构成障碍。

### 坑 3｜⭐ **命令行创建的 compose，1Panel 只能看、不能启停**（最容易白折腾的一条）

官方文档写得很明确，Compose 按来源分三类：

| 来源 | 说明 | 能否在面板里启停/编辑 |
|---|---|---|
| `Apps` | 从应用商店部署 | 部分 |
| `1Panel` | **在面板里创建** | ✅ **可以** |
| `Local` | **命令行 `docker compose up` 直接创建** | ❌ **不行** |

原文：*"Editing is only available for Composes deployed via 1Panel"*、
*"Starting/stopping operations for the Compose are only supported when the Compose is created by 1Panel."*

1Panel 是靠容器标签 `com.docker.compose.project` 发现项目的，所以「Local」项目**会出现在列表里**，
**但启停按钮是灰的** —— 你只能进去看容器列表和日志。

**→ 所以正确做法不是「CLI 起好再让 1Panel 看」，而是：**

> 用 1Panel 的「**容器 → 编排 → 创建 → 路径选择**」，选中我们放在服务器上的 `docker-compose.yml`。
> 这样该编排归属 `1Panel` 来源，才能在面板里**启停 / 重启 / 编辑 / 看日志**。

**这条直接改变了部署顺序**，详见 §3。

---

## 2. 内存账（这是唯一需要权衡的地方）

| 项 | 变化 |
|---|---:|
| 当前 `MemAvailable` | **1384 MB** |
| 全量容器化：dockerd + containerd | −150 ~ −220 |
| 全量容器化：中间件容器化溢价 | −100 ~ −200 |
| 1Panel（社区实测空载） | −150 ~ −200 |
| **预计剩余** | **约 800 ~ 900 MB** |

**结论：装得起，但安全垫从 1.3G 掉到 ~850MB。**

⚠️ 还有一个**容易被忽略的账**：`docker-compose.full.yml` 里 11 个容器的 `mem_limit` 合计 **3488m**，
但 **1Panel 和操作系统不在这份 compose 里**（1Panel 是原生 systemd 服务），加起来会**超过物理 3718m**。
一旦真发生内存争抢，内核会**随机** OOM-kill，而不是优先杀某个容器。

**处理：给 1Panel 单独加 `MemoryMax`**（`70-install-1panel.sh` 会自动做），
把它的占用也框住 —— 这样"任何一个组件失控都不会拖垮整机"，正是 Docker 化带来的护栏价值。

---

## 3. 落地顺序（顺序很重要，别颠倒）

```
① 本机构建 5 个镜像 → 推送 Docker Hub（jsyl6/ap-*）
② 服务器配镜像源（docker.1ms.run 等）→ 装 Docker
③ 准备服务器目录结构 /opt/agent-platform/{data,init,nginx,web} + compose + .env
④ 装 1Panel（脚本 70-install-1panel.sh）—— 【此时中间件还在原生跑，业务不中断】
⑤ 停原生中间件 + 迁移数据（MySQL dump / PG dump / RabbitMQ / Nacos）
⑥ 在 1Panel 面板里用「编排 → 路径选择」纳管 compose → 由面板启动
⑦ 验活（门户 200 / 11 个容器健康 / 内存）
```

**为什么 1Panel 放在第 ④ 步而不是最后**：安装脚本要装 Docker、要选端口，
这些**在业务不中断时做**更安全；等第 ⑤ 步真正切换时，你已经有一个可视化面板在看着了。

**为什么最后一步必须由面板启动**：见坑 3 —— 只有面板创建的编排才能被面板启停。

---

## 4. 边界：哪些用 1Panel，哪些坚决不要用

| 用 ✅ | 不用 ❌ | 原因 |
|---|---|---|
| 概览监控（CPU/内存/磁盘/网络） | **应用商店** | 它会装自己的 MySQL/PG/Redis，和我们的 compose **重复、抢端口、抢内存** |
| 容器列表、单个容器启停、日志 | **网站功能** | 我们的 nginx 是容器；且「只暴露 8000」已定，两套 Web 入口会打架 |
| 编排（纳管我们的 compose） | **数据库功能** | 1Panel 只管理**它自己装**的数据库，管不到我们 compose 里的 MySQL/PG |
| 终端、文件管理、计划任务 | 用它改 SSH/防火墙默认策略 | 我们已有 `10-system-harden.sh` 的加固基线，别让面板覆盖掉 |

---

## 5. 安装要点（**已读源码核实，可完全非交互**）

> ⚠️ **我第一版说"安装是交互式的、必须你自己在终端里回答"——这是错的。**
> v2.3.2 的 `install.sh` **支持 `--non-interactive`**，全部参数都能命令行指定，所以脚本可以一次装完。

### 版本与兼容性（已实测，非推测）

| 项 | 实测结果 |
|---|---|
| 最新稳定版 | **v2.3.2** |
| 包体 | `1panel-v2.3.2-linux-amd64.tar.gz`，**63MB** |
| CDN 速度 | **15.7 MB/s**（4.2 秒下完，网络不是问题） |
| 架构 | 脚本只认架构（`uname -a` → amd64），x86_64 ✅ |
| **发行版兼容性** | ✅ **风险已消除**：`install.sh` 里唯一的发行版分支是 `[ "$ID" = "debian" ] \|\| return`（只用于 Debian 的离线 iptables deb 包），**没有版本白名单**，Ubuntu 26.04 不会被拒 |
| 单元文件 | `1panel-core.service` + `1panel-agent.service`，**两个进程**（`/usr/bin/1panel-core`、`/usr/bin/1panel-agent`），当前都**没有** `MemoryMax` → 我们的加固脚本可加 |
| 内存检查 | `install.sh` 里**没有任何内存检查**，不会因为我们只有 3.7G 而拒绝 |

> ⚠️ 上一轮我用"脚本里没出现 `resolute`/`26.04`"推断"发行版识别可能失败"——**那是查错了文件**。
> `quick_start.sh` 只是个下载器，不做任何发行版检测。真正的逻辑在 tarball 里的 `install.sh`。

### 实际要用的命令

```bash
# 脚本 70-install-1panel.sh 会自动做：下载 → 校验 → 解包 → 非交互安装 → 生成随机凭据 → 校验 daemon.json
sudo bash 70-install-1panel.sh --install

# 它执行的等价命令（关键参数都是读源码确认的真实参数）：
cd /opt/1panel-installer/1panel-v2.3.2-linux-amd64
bash install.sh --non-interactive --lang zh \
  --port 23456 --entrance <随机10位> \
  --username admin --password <随机20位> \
  --install-docker n \            # 我们已装 Docker，不让它再装一套
  --configure-accelerator n \     # ★ 不配它的加速器
  --replace-daemon-json n         # ★ 绝不改写我们的 daemon.json（见坑 2b）
```

凭据（含安全入口和密码）会写到 **`/opt/agent-platform-secure/1panel-credentials.txt`**（chmod 600，不入 git），
也可以用 `1pctl user-info` 随时查。

### 安装后还会做什么（`--post`）

1. **删掉 `ufw allow <面板端口>`** —— 源码里它会自己放行，我们要收回去
2. 给 `1panel-core` / `1panel-agent` 加 **`MemoryMax=384M`**
3. 打印 SSH 隧道命令和安全入口

### 待验证（安装时才见分晓，不是我能静态确定的）

- `install.sh` 里若有依赖系统包管理器（apt）安装组件的逻辑，在 Ubuntu 26.04 上**具体包名**可能变化
- `--non-interactive` 模式下若某必填项缺失，脚本会用默认值 —— **装完我会立刻跑 `--verify` 核实端口、凭据、ufw、内存上限四项**

