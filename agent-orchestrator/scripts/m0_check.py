# M0 验收脚本：检查依赖版本 + 全部环境变量可读
# 用法: uv run python scripts/m0_check.py
import importlib.metadata
import sys
from pathlib import Path

from dotenv import load_dotenv
import os

REQUIRED_PKGS = [
    "langgraph",
    "langchain-openai",
    "langchain-core",
    "httpx",
    "fastapi",
    "uvicorn",
    "pydantic-settings",
]

REQUIRED_ENVS = [
    "GATEWAY_BASE_URL",
    "GATEWAY_ACCESS_KEY",
    "GATEWAY_SECRET_KEY",
    "LLM_API_KEY",
    "LLM_BASE_URL",
    "LLM_MODEL",
]


def mask(v: str) -> str:
    if len(v) <= 8:
        return "*" * len(v)
    return v[:4] + "*" * (len(v) - 8) + v[-4:]


def main() -> int:
    # .env 位于项目根目录（scripts/ 的上一级）
    root = Path(__file__).resolve().parent.parent
    load_dotenv(root / ".env")

    print("== 依赖版本 ==")
    missing_pkgs = []
    for pkg in REQUIRED_PKGS:
        try:
            print(f"  {pkg:20s} {importlib.metadata.version(pkg)}")
        except importlib.metadata.PackageNotFoundError:
            missing_pkgs.append(pkg)
            print(f"  {pkg:20s} MISSING")

    print("\n== 环境变量 ==")
    missing_envs = []
    for key in REQUIRED_ENVS:
        val = os.getenv(key)
        if val:
            print(f"  {key:20s} {mask(val)}")
        else:
            missing_envs.append(key)
            print(f"  {key:20s} MISSING")

    print()
    if missing_pkgs or missing_envs:
        print(f"[FAIL] 缺依赖: {missing_pkgs or '无'}；缺环境变量: {missing_envs or '无'}")
        return 1
    print("[PASS] M0 验收通过：依赖齐全，环境变量全部可读")
    return 0


if __name__ == "__main__":
    sys.exit(main())
