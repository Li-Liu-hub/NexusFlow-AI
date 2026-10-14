#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成一套强密码凭据，写入 credentials.env（已被 .gitignore 忽略，禁止提交）。

用法（在本机跑，不是服务器）：
    python generate-credentials.py
    python generate-credentials.py --force     # 覆盖已存在的文件

为什么用纯字母数字：密码会出现在 JDBC URL、shell 变量、systemd Environment 里，
带 @ : / # 等字符容易在转义上出错，24 位 [A-Za-z0-9] 的熵已经足够（约 143 bit）。
"""
import argparse
import base64
import os
import secrets
import string
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "credentials.env")

ALPHABET = string.ascii_letters + string.digits


def pw(n: int = 24) -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(n))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="覆盖已存在的 credentials.env")
    args = ap.parse_args()

    if os.path.exists(OUT) and not args.force:
        print(f"已存在：{OUT}\n如要重新生成请加 --force（注意：重新生成后所有服务都要重新配密码）")
        return 1

    # Nacos 2.2+ 要求 token.secret.key 为 base64 且解码后 >= 32 字节
    nacos_secret = base64.b64encode(secrets.token_bytes(48)).decode()

    lines = [
        "# 由 generate-credentials.py 生成 —— 禁止提交到 git",
        "# 重新生成会让所有已配置的服务失联，必须先停服再重跑部署",
        "",
        "# ---- MySQL ----",
        f"MYSQL_ROOT_PW={pw()}",
        "MYSQL_APP_USER=api_user",
        f"MYSQL_APP_PW={pw()}",
        "",
        "# ---- PostgreSQL ----",
        "PG_USER=my_user",
        f"PG_PW={pw()}",
        "",
        "# ---- Redis ----",
        f"REDIS_PW={pw()}",
        "",
        "# ---- RabbitMQ ----",
        "RABBIT_USER=aismp",
        f"RABBIT_PW={pw()}",
        "",
        "# ---- Nacos ----",
        f"NACOS_TOKEN_SECRET={nacos_secret}",
        "NACOS_IDENTITY_KEY=serverIdentity",
        f"NACOS_IDENTITY_VALUE={pw(32)}",
        "",
        "# ---- 服务器 ----",
        "SERVER_HOST=49.234.49.192",
        "",
    ]

    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))

    try:
        os.chmod(OUT, 0o600)
    except OSError:
        pass

    print(f"已生成：{OUT}")
    print("预览（只显示前 4 位）：")
    for ln in lines:
        if "=" in ln and not ln.startswith("#"):
            k, v = ln.split("=", 1)
            print(f"  {k} = {v[:4]}{'*' * max(0, len(v) - 4)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
