#!/usr/bin/env python
"""多租户 schema 的可重复验证（不需要部署、不需要起服务）。

做四件事，任何一条不满足就 exit 1：
  1. 在临时库里建基线（01-schema-api_db.sql，只改库名）
  2. 连续执行 05-tenant-schema.sql 三次 —— 验证幂等（迁移脚本必须能重复跑）
  3. 断言结构：5 张新表 + a_user.ent_code + 索引
  4. 断言种子：两家公司、两条 TENANT 额度、16 条页面配置
最后删掉临时库（--keep 可保留现场排查）。

用法：
    MYSQL_PASSWORD=xxx  python db/mysql/verify-tenant-schema.py
    （可选 MYSQL_HOST / MYSQL_PORT / MYSQL_USER；默认 127.0.0.1:3306 root）
    MySQL 客户端不在 PATH 时用 MYSQL_BIN 指到目录，例如
        MYSQL_BIN="C:/Program Files/MySQL/MySQL Server 8.0/bin"
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA = os.path.join(HERE, "01-schema-api_db.sql")
TENANT = os.path.join(HERE, "05-tenant-schema.sql")
SCRATCH = "nexusflow_tenant_verify"

HOST = os.environ.get("MYSQL_HOST", "127.0.0.1")
PORT = os.environ.get("MYSQL_PORT", "3306")
USER = os.environ.get("MYSQL_USER", "root")
PASSWORD = os.environ.get("MYSQL_PASSWORD", "")

NEW_TABLES = {
    "a_tenant",
    "a_tenant_feature",
    "a_tenant_page",
    "a_feature_ticket",
    "a_feature_ticket_log",
}

failures = []


def mysql_bin() -> str:
    bin_dir = os.environ.get("MYSQL_BIN", "")
    exe = "mysql.exe" if os.name == "nt" else "mysql"
    if bin_dir:
        return os.path.join(bin_dir, exe)
    found = shutil.which("mysql")
    return found or exe


def run_sql(sql: str, db: str | None = None, stdin_file: str | None = None) -> str:
    args = [mysql_bin(), "-h", HOST, "-P", PORT, "-u", USER,
            "--default-character-set=utf8mb4", "-N", "-B"]
    if PASSWORD:
        args.append(f"-p{PASSWORD}")
    if db:
        args.append(db)
    if stdin_file:
        with open(stdin_file, "rb") as fh:
            proc = subprocess.run(args, stdin=fh, capture_output=True)
    else:
        proc = subprocess.run(args, input=sql.encode("utf-8"), capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode("utf-8", "replace").strip())
    return proc.stdout.decode("utf-8", "replace").strip()


def check(name: str, cond: bool, detail: str = "") -> None:
    print(("  PASS  " if cond else "  FAIL  ") + name + (f"  [{detail}]" if detail and not cond else ""))
    if not cond:
        failures.append(name)


def main() -> int:
    print(f"MySQL {USER}@{HOST}:{PORT}  client={mysql_bin()}")

    # 1) 临时库 + 基线结构（只替换库名，不动别的字节）
    run_sql(f"DROP DATABASE IF EXISTS `{SCRATCH}`; CREATE DATABASE `{SCRATCH}` "
            f"DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
    with open(SCHEMA, encoding="utf-8") as fh:
        base_sql = fh.read().replace("`api_db`", f"`{SCRATCH}`")
    tmp = tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8")
    tmp.write(base_sql)
    tmp.close()
    run_sql("", stdin_file=tmp.name)
    os.unlink(tmp.name)

    base_tables = int(run_sql(
        "SELECT COUNT(*) FROM information_schema.tables "
        f"WHERE table_schema='{SCRATCH}';"))
    print(f"  基线条数：{base_tables} 张表")

    # 2) 迁移脚本跑三次（幂等）
    ok_idempotent = True
    for i in range(3):
        try:
            run_sql("", stdin_file=TENANT, db=SCRATCH)
        except RuntimeError as exc:
            ok_idempotent = False
            print(f"  第 {i + 1} 次执行失败：{exc}")
            break
    check("05-tenant-schema.sql 可重复执行（连跑 3 次）", ok_idempotent)

    # 3) 结构断言
    tables = set(run_sql(
        "SELECT table_name FROM information_schema.tables "
        f"WHERE table_schema='{SCRATCH}';").split())
    check("新增表齐全", NEW_TABLES <= tables,
          f"缺少 {sorted(NEW_TABLES - tables)}")
    check("总表数 = 基线 + 5", len(tables) == base_tables + 5,
          f"实际 {len(tables)}")

    ent_col = run_sql(
        "SELECT COUNT(*) FROM information_schema.columns "
        f"WHERE table_schema='{SCRATCH}' AND table_name='a_user' AND column_name='ent_code';")
    check("a_user.ent_code 存在", ent_col == "1")

    idx = run_sql(
        "SELECT COUNT(*) FROM information_schema.statistics "
        f"WHERE table_schema='{SCRATCH}' AND table_name='a_user' AND index_name='idx_user_ent';")
    check("a_user.idx_user_ent 存在", int(idx) >= 1)

    uniq = run_sql(
        "SELECT COUNT(*) FROM information_schema.statistics "
        f"WHERE table_schema='{SCRATCH}' AND table_name='a_tenant_page' "
        "AND index_name='uk_ent_page';")
    check("a_tenant_page.uk_ent_page 唯一键存在", int(uniq) >= 1)

    # 4) 种子断言
    tenants = run_sql(f"SELECT COUNT(*) FROM `{SCRATCH}`.a_tenant;")
    check("种子公司 2 家", tenants == "2", tenants)

    quotas = run_sql(
        f"SELECT COUNT(*) FROM `{SCRATCH}`.a_token_quota WHERE scope='TENANT';")
    check("TENANT 维度 token 额度 2 条", quotas == "2", quotas)

    pages = run_sql(f"SELECT COUNT(*) FROM `{SCRATCH}`.a_tenant_page;")
    check("页面配置 16 条（两家公司各 8 页）", pages == "16", pages)

    # 页面差异：九台不该看到报表（visible=0），长春应可见
    hidden = run_sql(
        f"SELECT COUNT(*) FROM `{SCRATCH}`.a_tenant_page "
        "WHERE ent_code='JLJT' AND visible=0;")
    check("九台有隐藏页（体现两家公司页面不同）", int(hidden) >= 1, hidden)

    # 中文写入必须是 UTF-8（历史上踩过客户端编码导致乱码）
    hexname = run_sql(
        f"SELECT HEX(ent_name) FROM `{SCRATCH}`.a_tenant WHERE ent_code='JLCC';")
    expected = "联通长春分公司".encode("utf-8").hex().upper()
    check("中文按 UTF-8 落库", hexname == expected, f"{hexname} != {expected}")

    if "--keep" not in sys.argv:
        run_sql(f"DROP DATABASE IF EXISTS `{SCRATCH}`;")

    print()
    if failures:
        print(f"FAILED：{len(failures)} 项")
        for f in failures:
            print("  -", f)
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
