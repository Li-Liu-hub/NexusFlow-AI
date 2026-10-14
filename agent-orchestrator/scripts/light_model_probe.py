# 轻量模型探针（M6 遗留优化：路由/critic 降级到小模型的前置验证）
# 验证三件事，全过才改配置：
#   ① with_structured_output(function_calling) 在候选模型上可用（z.ai 有"兼容≠全功能"前科）
#   ② 路由正确率：4 题含 q20 边界题（知识库有重装系统文档，期望 rag）
#   ③ 判分区分度：好答案判合格 + 烂答案判不合格（判官不能变成老好人）
# 用法: uv run python scripts/light_model_probe.py
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from graph import CRITIC_PROMPT, ROUTE_PROMPT, CriticVerdict, RouteDecision
from llm import get_llm

BASELINE = "glm-5.2"
CANDIDATES = ["glm-4.5-air", "glm-5-turbo"]

# 路由用例：2 道 rag + 1 道 direct + q20 边界题（M6 首跑翻过车的那个）
ROUTE_CASES = [
    ("报表导不出来", "rag"),
    ("怎么修改数据库里的数据", "rag"),
    ("今天天气怎么样", "direct"),
    ("怎么重装操作系统", "rag"),  # 边界：知识库有对应工单文档
]

# 判分用例：好答案取自 m6_router_on.jsonl 真实直答（判官已判合格），
# 烂答案是构造的拒答/空泛（必须判不合格——区分度是 critic 的存在意义）
_q105 = next(r for r in map(json.loads, (Path(__file__).parent.parent /
             "docs/m6_router_on.jsonl").read_text(encoding="utf-8").splitlines())
             if r["id"] == 105)
CRITIC_CASES = [
    ("推荐一本好书", _q105["answer"], True),   # 真实合格样本
    ("怎么煮鸡蛋", "嗯 不知道呢。", False),      # 空泛拒答，必须不合格
]


def probe_one(model: str) -> dict:
    llm = get_llm(temperature=0, model=model)
    result = {"model": model, "route_ok": 0, "route_total": 0, "route_s": [],
              "critic_ok": 0, "critic_total": 0, "critic_s": [],
              "structured_broken": False}
    for q, expect in ROUTE_CASES:
        try:
            t0 = time.perf_counter()
            d = llm.with_structured_output(
                RouteDecision, method="function_calling").invoke(
                ROUTE_PROMPT.format(query=q))
            result["route_s"].append(time.perf_counter() - t0)
            result["route_total"] += 1
            mark = "✓" if d.route == expect else "✗"
            if d.route == expect:
                result["route_ok"] += 1
            print(f"    [路由 {q}] 期望{expect} 实际{d.route} {mark} "
                  f"{result['route_s'][-1]:.1f}s")
        except Exception as e:  # noqa: BLE001——探针要活下来，失败也是数据
            result["structured_broken"] = True
            print(f"    [路由 {q}] [异常] {type(e).__name__}: {str(e)[:80]}")
    for q, ans, expect in CRITIC_CASES:
        try:
            t0 = time.perf_counter()
            v = llm.with_structured_output(
                CriticVerdict, method="function_calling").invoke(
                CRITIC_PROMPT.format(query=q, answer=ans[:500]))
            result["critic_s"].append(time.perf_counter() - t0)
            result["critic_total"] += 1
            mark = "✓" if v.passed == expect else "✗"
            if v.passed == expect:
                result["critic_ok"] += 1
            print(f"    [判分 {q}] 期望{'合格' if expect else '不合格'} "
                  f"实际{'合格' if v.passed else '不合格'} {mark} "
                  f"{result['critic_s'][-1]:.1f}s | {v.reason[:40]}")
        except Exception as e:  # noqa: BLE001
            result["structured_broken"] = True
            print(f"    [判分 {q}] [异常] {type(e).__name__}: {str(e)[:80]}")
    return result


def main() -> int:
    print(f"[探针] 基线 {BASELINE} + 候选 {CANDIDATES} | "
          f"路由 {len(ROUTE_CASES)} 题 + 判分 {len(CRITIC_CASES)} 题")
    summary = []
    for model in [BASELINE] + CANDIDATES:
        print(f"  === {model} ===")
        r = probe_one(model)
        summary.append(r)
    print("\n[汇总]")
    print(f"{'模型':<14}{'结构化':<6}{'路由':<8}{'路由均耗时':<10}{'判分':<8}{'判分均耗时':<10}")
    for r in summary:
        st = "异常" if r["structured_broken"] else "可用"
        rt = f"{r['route_ok']}/{r['route_total']}" if r["route_total"] else "-"
        ct = f"{r['critic_ok']}/{r['critic_total']}" if r["critic_total"] else "-"
        rs = f"{sum(r['route_s'])/len(r['route_s']):.1f}s" if r["route_s"] else "-"
        cs = f"{sum(r['critic_s'])/len(r['critic_s']):.1f}s" if r["critic_s"] else "-"
        print(f"{r['model']:<14}{st:<6}{rt:<8}{rs:<10}{ct:<8}{cs:<10}")
    print("\n判定标准：结构化可用 + 路由 4/4（q20 必须对）+ 判分 2/2 → 该模型可接管对应岗位")
    return 0


if __name__ == "__main__":
    sys.exit(main())
