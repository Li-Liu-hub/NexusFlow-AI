#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量探测镜像源能否代理指定命名空间（默认 jsy16）。
用法: python3 mirror_probe2.py [namespace] [repo] [tag]
"""
import json
import ssl
import sys
import urllib.error
import urllib.request

CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
ACCEPT = ", ".join([
    "application/vnd.docker.distribution.manifest.v2+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.oci.image.index.v1+json",
])

NS = sys.argv[1] if len(sys.argv) > 1 else "jsy16"
REPO = sys.argv[2] if len(sys.argv) > 2 else "ap-gateway"
TAG = sys.argv[3] if len(sys.argv) > 3 else "20261010"


def req(url, headers=None, want_headers=False):
    r = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(r, timeout=18, context=CTX) as resp:
            return resp.status, (dict(resp.headers) if want_headers else resp.read())
    except urllib.error.HTTPError as e:
        return e.code, (dict(e.headers) if want_headers else e.read())
    except Exception as e:
        return 0, str(e).encode()


def probe(mirror, repo, tag):
    st, hdrs = req(f"https://{mirror}/v2/", want_headers=True)
    chal = ""
    if isinstance(hdrs, dict):
        for k, v in hdrs.items():
            if k.lower() == "www-authenticate":
                chal = v
    if not chal:
        st2, _ = req(f"https://{mirror}/v2/{repo}/manifests/{tag}", {"Accept": ACCEPT})
        if st2 == 200:
            return "✅ 可代理(匿名)", 200
        if st2 == 0:
            return "❌ 连不上", 0
        return f"? HTTP={st2}(无挑战头)", st2

    def pick(n):
        i = chal.lower().find(n + '="')
        if i < 0:
            return ""
        s = i + len(n) + 2
        return chal[s:chal.find('"', s)]

    realm, service = pick("realm"), pick("service")
    if not realm:
        return "❌ 挑战头无法解析", None
    if "auth.docker.io" in realm:
        return "❌ token 服务指向 auth.docker.io（本机不可达）", None

    st, body = req(f"{realm}?service={service}&scope=repository:{repo}:pull")
    token = ""
    if st == 200:
        try:
            j = json.loads(body)
            token = j.get("token") or j.get("access_token") or ""
        except Exception:
            pass
    if not token:
        return f"❌ 取 token 失败 HTTP={st}", st

    st2, _ = req(f"https://{mirror}/v2/{repo}/manifests/{tag}",
                 {"Accept": ACCEPT, "Authorization": "Bearer " + token})
    if st2 == 200:
        return "✅ 可代理", 200
    if st2 == 404:
        return "✅ 会代理(已认证,404=暂无此 tag)", 404
    if st2 in (401, 403):
        return "❌ 拒绝该命名空间", st2
    return f"? HTTP={st2}", st2


MIRRORS = [
    "docker.1ms.run",
    "docker.m.daocloud.io",
    "docker.1panel.live",
    "hub.rat.dev",
    "docker.ketches.cn",
    "dockerpull.org",
    "docker.mirrors.ustc.edu.cn",
    "mirror.ccs.tencentyun.com",
]

print(f"目标: {NS}/{REPO}:{TAG}\n")
good = []
for m in MIRRORS:
    verdict, code = probe(m, f"{NS}/{REPO}", TAG)
    print(f"  {m:32s} {verdict}")
    if code in (200, 404):
        good.append(m)
print()
print("可用于拉你自己镜像的源:", good or "(无)")
