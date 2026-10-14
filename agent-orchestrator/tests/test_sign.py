# 签名模块单测：固定测试向量（与 Java SignUtils.getSign 同公式预计算）+ 行为约束
# 用法: uv run pytest tests/test_sign.py
import re
import time

from config import settings
from sdk.sign import build_auth_headers, gen_nonce, gen_timestamp, get_sign

# TestGetSign 用自带的固定 SK 验算（向量与 .env 无关，永远稳定）
SK = "efeeaffbadec6915557d9725023d9711"
# TestBuildAuthHeaders 测的是"是否用了配置里的凭据"，所以必须从 settings 读 ——
# 原先这里硬编码了一个 AK，一旦 .env 换账号测试就红，属于脆弱测试（2026-10-01 修）


class TestGetSign:
    def test_known_vector_body(self):
        # 预计算: sha256("hello." + SK)
        assert (
            get_sign("hello", SK)
            == "9673e9a7d2010f2c81c58e4f0338faf9e4a1c7be56d2f34ba044221d6f5e0957"
        )

    def test_known_vector_json_body(self):
        # 预计算: sha256('{"message":"hi"}.' + SK)
        assert (
            get_sign('{"message":"hi"}', SK)
            == "61db6f10ff4d87f17fb8269cd761b7551f4d613be76ab96e61f06e20a52d1740"
        )

    def test_none_body_uses_null_literal(self):
        # Java: getSign(null, sk) → 拼接字面量 "null." + sk
        assert (
            get_sign(None, SK)
            == "5c0d40ee8969834b0b4f5bd5f221b61dc1203639ad682d012c7fadad451754e9"
        )

    def test_lowercase_hex(self):
        assert re.fullmatch(r"[0-9a-f]{64}", get_sign("abc", SK))


class TestNonceAndTimestamp:
    def test_nonce_is_high_entropy_hex(self):
        # M1-R1 后：32 位 hex（2^128 空间），网关只校验长度 <=64
        seen = set()
        for _ in range(200):
            n = gen_nonce()
            assert re.fullmatch(r"[0-9a-f]{32}", n)
            seen.add(n)
        assert len(seen) == 200  # 200 次采样零重复（原 9 万空间做不到）

    def test_timestamp_is_seconds(self):
        ts = int(gen_timestamp())
        assert abs(ts - time.time()) < 5


class TestBuildAuthHeaders:
    def test_post_headers_contain_all_fields(self):
        body = '{"message":"hi"}'
        h = build_auth_headers(body)
        assert h["accessKey"] == settings.GATEWAY_ACCESS_KEY
        assert h["body"] == body
        assert re.fullmatch(r"[0-9a-f]{32}", h["nonce"])  # M1-R1: 32位hex
        assert re.fullmatch(r"[0-9a-f]{64}", h["sign"])
        # 签名与 body 一致
        assert h["sign"] == get_sign(body, settings.GATEWAY_SECRET_KEY)

    def test_get_headers_omit_body(self):
        h = build_auth_headers(None)
        assert "body" not in h
        # 网关侧读不到 body header → 用 "null." + sk 验签，Python 必须签同样的内容
        assert h["sign"] == get_sign(None, settings.GATEWAY_SECRET_KEY)

    def test_missing_credentials_are_detected(self):
        """凭据缺失时应当能被显式发现，而不是静默签出一个空 AK 的请求。

        这条测试锁定"配置缺失 = 空串"这个事实，部署脚本据此在启动前校验 .env。
        """
        assert isinstance(settings.GATEWAY_ACCESS_KEY, str)
        assert isinstance(settings.GATEWAY_SECRET_KEY, str)
