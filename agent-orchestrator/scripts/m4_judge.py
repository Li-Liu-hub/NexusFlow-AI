# M4 判官补判：用图内同一份 CRITIC_PROMPT 对某遍的完整回答逐条判分
# 用途: 基线遍图内没跑 critic（省调用决策），事后补判得到"判官口径不合格率"——
#       与 critic on 遍的图内判定同口径，前后对比才成立
# 用法: uv run python scripts/m4_judge.py 1   # 判第 1 遍（基线）的回答
#       uv run python scripts/m4_judge.py 2   # 判第 2 遍（自纠环）的回答
# 产出: docs/m4_eval_pass{N}_judged.jsonl（逐题判定）+ 控制台不合格率
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from graph import CRITIC_PROMPT, CriticVerdict
from llm import get_llm

OUT_DIR = Path(__file__).resolve().parent.parent / "docs"
WORKERS = 5


def judge_one(row: dict) -> dict:
    """对一条已有回答打分：同一判官、同一提示词、同一 temperature——口径与图内 critic 一致。
    单题异常不炸整遍——记不合格+原因（评估脚本要活下来而不是完美，同 m4_eval.py）。"""
    if row.get("error"):
        return {"id": row["id"], "passed": False, "reason": f"运行错误: {row['error']}"}
    try:
        verdict: CriticVerdict = get_llm(temperature=0.2).with_structured_output(
            CriticVerdict, method="function_calling").invoke(
            CRITIC_PROMPT.format(query=row["rewritten_query"], answer=row["answer"]))
    except Exception as e:  # noqa: BLE001
        return {"id": row["id"], "passed": False, "reason": f"判官异常: {type(e).__name__}"[:100]}
    return {"id": row["id"], "passed": verdict.passed, "reason": verdict.reason[:100]}


def main() -> int:
    pass_n = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    # 子集补判: 第二个参数逗号分隔 id——只判这些题，结论按 id 合并回已有文件
    # （不重判其他题：判官 temperature=0.2 非完全确定，重跑可能翻旧案污染已定数据）
    only_ids = ({int(x) for x in sys.argv[2].split(",")} if len(sys.argv) > 2 else None)
    src = OUT_DIR / f"m4_eval_pass{pass_n}_answers.jsonl"
    rows = [json.loads(line) for line in src.read_text(encoding="utf-8").splitlines()]
    if only_ids:
        rows = [r for r in rows if r["id"] in only_ids]
    print(f"[配置] 判官补判 第 {pass_n} 遍 | {len(rows)} 条 | 并发 {WORKERS}")

    t0 = time.perf_counter()
    verdicts: list[dict] = []
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = {pool.submit(judge_one, r): r for r in rows}
        for fut in as_completed(futures):
            v = fut.result()
            verdicts.append(v)
            print(f"  [{v['id']:>2}] {'合格' if v['passed'] else '不合格'} {v['reason'][:50]}")
    verdicts.sort(key=lambda v: v["id"])

    out = OUT_DIR / f"m4_eval_pass{pass_n}_judged.jsonl"
    if only_ids and out.exists():
        prev = {json.loads(l)["id"]: json.loads(l)
                for l in out.read_text(encoding="utf-8").splitlines()}
        prev.update({v["id"]: v for v in verdicts})
        verdicts = sorted(prev.values(), key=lambda v: v["id"])
    with out.open("w", encoding="utf-8") as f:
        for v in verdicts:
            f.write(json.dumps(v, ensure_ascii=False) + "\n")

    passed = sum(1 for v in verdicts if v["passed"])
    print(f"\n[汇总] 判官口径不合格率: {len(verdicts) - passed}/{len(verdicts)} "
          f"= {(len(verdicts) - passed) / len(verdicts):.1%}（耗时 {time.perf_counter() - t0:.0f}s）")
    print(f"  明细: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
