# S1 验收：LLM 接通 —— 打印 GLM 真实回答
# 用法: uv run python scripts/s1_llm_test.py
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import settings
from llm import get_llm


def main() -> int:
    print(f"model={settings.LLM_MODEL}  base_url={settings.LLM_BASE_URL}")
    resp = get_llm().invoke("用一句话介绍你自己")
    print(f"回答: {resp.content}")
    # response_metadata 里带模型回显，可核对服务端实际用的模型
    print(f"服务端回显模型: {resp.response_metadata.get('model', '未知')}")
    if not resp.content:
        print("[FAIL] LLM 返回空内容")
        return 1
    print("[PASS] S1 验收通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
