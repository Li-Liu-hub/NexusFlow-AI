# 网关签名模块：与 api-platform Java SDK 逐字段对齐
# 对照源: aismp-client-sdk/utils/SignUtils.java + client/OpenApiClient.java#getHashMap
#         网关校验: aismp-gateway/CustomGlobalFilter.java
import secrets
import time

import hashlib

# .env 注入后由 config 统一读取；此模块不直接读环境变量，保持纯函数
from config import settings


def get_sign(body: str | None, secret_key: str) -> str:
    """sign = sha256_hex(body + "." + secretKey)

    Java 侧 Hutool Digester.digestHex 输出小写 hex，UTF-8 编码。
    陷阱：body 为 null 时，Java 字符串拼接得到字面量 "null." + secretKey。
    """
    content = f"{body}.{secret_key}" if body is not None else f"null.{secret_key}"
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def gen_nonce() -> str:
    """高熵随机串（32 位 hex，空间 2^128）。

    原 5 位数字（9 万空间）在单用户 5 分钟窗口 100 次请求下撞车概率约 5.5%，
    撞车会被防重放误杀 403，已升级为 secrets.token_hex。
    网关配套改为长度 ≤64 校验 + key 带 ak 前缀（见开发文档 M1-R1）。
    """
    return secrets.token_hex(16)


def gen_timestamp() -> str:
    """秒级时间戳字符串（网关校验 5 分钟窗口）。"""
    return str(int(time.time()))


def build_auth_headers(body: str | None) -> dict[str, str]:
    """组装全套鉴权 headers。

    - 有 body：body 原文塞进 header（网关用 header 值验签），同时作为请求体发送
    - 无 body（GET）：不发送 body header，网关读到 null → 按 "null." + sk 验签
    """
    headers = {
        "accessKey": settings.GATEWAY_ACCESS_KEY,
        "nonce": gen_nonce(),
        "timestamp": gen_timestamp(),
        "sign": get_sign(body, settings.GATEWAY_SECRET_KEY),
    }
    if body is not None:
        headers["body"] = body
    return headers
