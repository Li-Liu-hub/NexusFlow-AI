# db · 数据库结构与种子

本目录存放**项目所需数据库的建表脚本与配置类种子数据**，用于新环境快速搭起一套可运行的库。
**不含任何业务数据、账号口令或密钥。**

## 目录结构

```
db/
├── mysql/
│   ├── 01-schema-api_db.sql          # 平台库 api_db —— 16 张表（仅结构）  ← 生产库
│   ├── 02-schema-terminal_db.sql     # 旧业务库 terminal_db —— 36 张表（仅结构，已废弃参考）
│   └── 10-seed-api_db.sql            # api_db 配置种子：网关接口登记 + 仓位
├── pg/
│   ├── 01-schema-terminal_kb.sql     # 向量/编排库 terminal_kb —— 6 张表（仅结构）
│   ├── 10-seed-terminal_kb.sql       # 配置种子：画布节点定义 28 条
│   └── 99-drop-oa-legacy.sql         # 可选：清理已废弃的 OA 场景残留表
└── README.md
```

## 两个 MySQL 库的关系（重要，别搞混）

| 库 | 角色 | 说明 |
|---|---|---|
| **`api_db`** | ✅ **当前生产库** | 终端业务（`terminal` / `warehouse` / `transfer_order`）+ 平台（`interface_info` / `user`）+ 账务与配额 |
| `terminal_db` | ⚠️ 已废弃 | 早期业务库，已被 `api_db` 取代。结构保留仅供对照 |

> 判断依据：`api_db.terminal` 有真实数据、`api_db.interface_info` 是网关实际匹配的表。

## 快速建库

### MySQL

```bash
MYSQL="mysql -h 127.0.0.1 -u root -p --default-character-set=utf8mb4"

# 1) 建库 + 建表
$MYSQL < db/mysql/01-schema-api_db.sql

# 2) 灌配置种子（网关接口登记，缺少会导致 403）
$MYSQL < db/mysql/10-seed-api_db.sql
```

### PostgreSQL（需先装 pgvector）

```bash
PSQL="psql -h 127.0.0.1 -U postgres -d terminal_kb"

# 1) 建库（在 postgres 库执行）
psql -h 127.0.0.1 -U postgres -d postgres -c "CREATE DATABASE terminal_kb;"
psql -h 127.0.0.1 -U postgres -d terminal_kb -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 2) 建表
$PSQL -f db/pg/01-schema-terminal_kb.sql

# 3) 灌画布节点定义
$PSQL -f db/pg/10-seed-terminal_kb.sql
```

## 业务数据怎么办

本目录**不带业务数据**。业务数据来源：

| 数据 | 位置 | 说明 |
|---|---|---|
| 终端 28354 条 + 仓位 | `local_data/terminal-data-desensitized.sql`（不入库） | 由 `local-init/import-terminal.py` 从 Excel 生成 |
| 原始 Excel | 工作区根目录 `长春市二级库.xlsx` / `九台市分公司三级库.xlsx` | 需求方提供，不入 git |

## 已在结构层面处理的问题

- **OA 场景残留**：`terminal_kb` 中 `base_user` / `tm_*` 属已删除的 OA 域，`01-schema` 已排除；线上若仍有，跑 `99-drop-oa-legacy.sql`。
- **字符集**：MySQL 侧统一 `utf8mb4` / `utf8mb4_0900_ai_ci`；导出脚本已带 `SET NAMES utf8mb4`，避免中文乱码。
- **向量维度**：`vector_store` 按 `BAAI/bge-m3` 的 **1024 维**建表，换嵌入模型需同步改维度。
- **注释乱码已修**：源库 `api_db` / `terminal_db` 的少数表是历史用错字符集建的，
  `COMMENT` 里存的是**双重编码**结果（例：`账号` 的 UTF-8 字节被误按 GBK 解码成 `璐﹀彿` 再存为 UTF-8）。
  已还原 **46 处**（`账号`/`名称`/`更新时间`/`是否删除`/`审核状态`…）；一次性修复脚本用完即删。
  其中带 `?` 的属原始字节已丢失，按字段语义定向重建。

## 与 `local-init/` 的关系

`local-init/*.sql` 是**按开发批次累积的增量脚本**（39 个，含 registry 登记、表结构演进、演示数据），
保留了完整的演进过程；`db/` 则是**当前生效状态的收敛快照**，用于新环境一次性对齐。
两者不冲突，新环境用 `db/`，追溯改动看 `local-init/`。
