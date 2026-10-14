# M4-S2 评估：40 题评估集跑图（真链路：LLM rewrite → 签名网关 → Java ReAct agent → critic）
# 用法:
#   uv run python scripts/m4_eval.py 1        # 第 1 遍：基线（critic off）
#   uv run python scripts/m4_eval.py 2        # 第 2 遍：自纠环（critic on）
#   uv run python scripts/m4_eval.py 3        # 第 3 遍：M5 换正式 rewrite 提示词后（critic on）
# 产出: docs/m4_eval_pass{N}.csv（逐题明细）+ 控制台汇总
# 设计: 并发 8 路（ThreadPoolExecutor）；基线免 LLM 判官（keywords 客观分即可，
#       "不合格率"主观指标只对 critic 开启的遍次有意义——判官本身就开着）
import csv
import json
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from graph import build_graph

# Java 侧现成评估集（20 题，带 expected_doc/type/keywords）
# 相对本脚本定位到项目根，避免硬编码本机绝对路径（原写法指向已废弃目录，脚本会直接崩）
_PROJ_ROOT = Path(__file__).resolve().parent.parent.parent
EVAL_SET = _PROJ_ROOT / "aismp-agent/src/main/resources/eval/eval-set.json"
OUT_DIR = Path(__file__).resolve().parent.parent / "docs"
WORKERS = 5  # 三遍统一 5 路：8 路触发模型端限流长尾（300s 超时×2），降到 5 路换数据干净


def _require_eval_set() -> Path:
    """评估集缺失时给出可操作的报错，而不是抛 FileNotFoundError。"""
    if not EVAL_SET.exists():
        raise SystemExit(
            "[错误] 找不到评估集：%s\n"
            "       请确认 aismp-agent/src/main/resources/eval/eval-set.json 存在。" % EVAL_SET
        )
    return EVAL_SET


def keyword_hits(answer: str, keywords: list[str]) -> tuple[int, list[str]]:
    """客观锚：expected_doc 的关键词在回答中的命中数（纯字符串匹配，零 LLM 调用）。"""
    hits = [k for k in keywords if k in answer]
    return len(hits), hits


def run_one(item: dict, pass_n: int, critic_enabled: bool) -> dict:
    """跑一题：独立会话（会话 id 唯一到 遍次×题目，防跨题记忆污染）；回炉轮共享会话。
    单题异常不炸整遍——记错误行（error=1），统计时可见、可比。"""
    conv = f"m4p{pass_n}q{item['id']}-{uuid.uuid4().hex[:4]}"
    start = time.perf_counter()
    error = ""
    final: dict = {}
    try:
        final = build_graph(critic_enabled=critic_enabled).invoke(
            {"query": item["question"], "revision_count": 0,
             "meta": {"conversation_id": conv}})
    except Exception as e:  # noqa: BLE001——评估脚本要活下来而不是完美
        error = f"{type(e).__name__}: {e}"[:120]
    duration = time.perf_counter() - start
    answer = final.get("agent_response", "")
    hits, hit_words = keyword_hits(answer, item.get("keywords", []))
    meta = final.get("meta") or {}
    return {
        "answer": answer,  # 全文——CSV 不放，main 里分流到 JSONL 侧车（判官/归因要全文）
        "id": item["id"],
        "type": item.get("type", ""),
        "question": item["question"],
        "expected_doc": item.get("expected_doc", ""),
        "keywords_total": len(item.get("keywords", [])),
        "keyword_hits": hits,
        "hit_words": " ".join(hit_words),
        "answer_len": len(answer),
        "duration_s": round(duration, 1),
        "revision_count": final.get("revision_count", 0),
        "critic_degraded": meta.get("critic_degraded", "") if critic_enabled else "",
        "critic_reason": (meta.get("critic_reason", "") or "")[:60] if critic_enabled else "",
        "rewritten_query": final.get("rewritten_query", "")[:60],
        "answer_excerpt": (answer or error)[:80].replace("\n", " "),
        "error": error,
        "conversation_id": conv,
    }


def main() -> int:
    pass_n = int(sys.argv[1]) if len(sys.argv) > 1 else 1
    critic_enabled = pass_n >= 2  # 第 1 遍基线关质检；2/3 遍开
    items = json.loads(_require_eval_set().read_text(encoding="utf-8"))
    # 子集补测模式: 第二个参数逗号分隔题目 id——只跑这些题，结果按 id 合并回已有
    # 文件（不覆盖全量、不动其他题的已定数据）
    only_ids = ({int(x) for x in sys.argv[2].split(",")} if len(sys.argv) > 2 else None)
    if only_ids:
        items = [it for it in items if it["id"] in only_ids]
    label = "自纠环(critic on)" if critic_enabled else "基线(critic off)"
    scope = f"补测 {sorted(only_ids)}" if only_ids else "全量"
    print(f"[配置] 第 {pass_n} 遍 | {label} | {scope} {len(items)} 题 | 并发 {WORKERS}")

    rows: list[dict] = []
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = {pool.submit(run_one, it, pass_n, critic_enabled): it for it in items}
        for fut in as_completed(futures):
            r = fut.result()
            rows.append(r)
            flag = " [错误]" if r["error"] else ""
            print(f"  [{r['id']:>2}] {r['duration_s']:>5}s 命中{r['keyword_hits']}/{r['keywords_total']}"
                  f" 回炉{r['revision_count']}{' 降级' if r['critic_degraded'] is True else ''}{flag} {r['question']}")
    elapsed = time.perf_counter() - t0

    rows.sort(key=lambda r: r["id"])
    # JSONL 侧车：完整回答 + 判官/归因所需全字段；补测模式按 id 合并回已有文件
    answers_path = OUT_DIR / f"m4_eval_pass{pass_n}_answers.jsonl"
    if only_ids and answers_path.exists():
        merged = {json.loads(l)["id"]: json.loads(l)
                  for l in answers_path.read_text(encoding="utf-8").splitlines()}
        merged.update({r["id"]: r for r in rows})
        rows = sorted(merged.values(), key=lambda r: r["id"])
    with answers_path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    # CSV：人读明细（answer 字段太长不进 CSV）
    csv_rows = [{k: v for k, v in r.items() if k != "answer"} for r in rows]
    # fieldnames 取并集（补测合并进来的老行可能带 merged 等额外字段，首行未必有）
    fieldnames: list[str] = []
    for cr in csv_rows:
        fieldnames += [k for k in cr if k not in fieldnames]
    out = OUT_DIR / f"m4_eval_pass{pass_n}.csv"
    with out.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(csv_rows)

    total_kw = sum(r["keywords_total"] for r in rows)
    total_hits = sum(r["keyword_hits"] for r in rows)
    full = sum(1 for r in rows if r["keyword_hits"] == r["keywords_total"] and not r["error"])
    zero = sum(1 for r in rows if r["keyword_hits"] == 0 and not r["error"])
    errors = sum(1 for r in rows if r["error"])
    reworked = sum(1 for r in rows if r["revision_count"] > 0)
    degraded = sum(1 for r in rows if r["critic_degraded"] is True)
    print(f"\n[汇总] 第 {pass_n} 遍（{label}，{scope}）耗时 {elapsed/60:.1f} 分钟"
          + ("（部分数据，命中率仅本子集口径）" if only_ids else ""))
    print(f"  关键词命中率: {total_hits}/{total_kw} = {total_hits/total_kw:.1%}")
    print(f"  全命中题数:   {full}/{len(rows)}    零命中题数: {zero}    错误题数: {errors}")
    if critic_enabled:
        print(f"  触发回炉:     {reworked} 题    降级放行: {degraded} 题")
    print(f"  明细: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
