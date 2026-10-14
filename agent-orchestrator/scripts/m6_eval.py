# M6-S2 supervisor 路由评估：分流正确率 + direct 分支延迟对比 + RAG 质量不回退
# 用法:
#   uv run python scripts/m6_eval.py a    # A 遍：router on，20 RAG + 10 direct = 30 题
#   uv run python scripts/m6_eval.py b    # B 遍：router off，10 direct 走全链（延迟对照组）
#   uv run python scripts/m6_eval.py j    # 判官：A 遍 direct 行补判（直答质量）
# 产出: docs/m6_router_{on,off}.jsonl + 控制台汇总；留痕体系同 M4（控制台日志归档 docs/evidence/）
import json
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from graph import CRITIC_PROMPT, CriticVerdict, RouteDecision, build_graph
from llm import get_llm

# 相对项目根定位（原为写死的 D:/Code/Java/... 绝对路径，换机器即崩）
EVAL_SET = Path(__file__).resolve().parent.parent.parent / \
    "aismp-agent/src/main/resources/eval/eval-set.json"
OUT_DIR = Path(__file__).resolve().parent.parent / "docs"
WORKERS = 5  # 与 M4 三遍统一：5 路是实测不触发 z.ai 限流长尾的上限

# M6-S2 通用类评估集（direct 预期路由）：覆盖闲聊/常识/天气/时事四类，
# 与 OA 知识库零交集——预期全部路由 direct、不走知识库
M6_DIRECT_QUESTIONS = [
    {"id": 101, "type": "通用-身份", "question": "你叫什么名字", "keywords": []},
    {"id": 102, "type": "通用-闲聊", "question": "给我讲个笑话", "keywords": []},
    {"id": 103, "type": "通用-常识", "question": "中国的首都是哪里", "keywords": []},
    {"id": 104, "type": "通用-常识", "question": "一加一等于几", "keywords": []},
    {"id": 105, "type": "通用-推荐", "question": "推荐一本好书", "keywords": []},
    {"id": 106, "type": "通用-生活", "question": "怎么煮鸡蛋", "keywords": []},
    {"id": 107, "type": "通用-时事", "question": "昨天世界杯谁赢了", "keywords": []},
    {"id": 108, "type": "通用-能力", "question": "你会说英语吗", "keywords": []},
    {"id": 109, "type": "通用-闲聊", "question": "周末去哪玩比较好", "keywords": []},
    {"id": 110, "type": "通用-常识", "question": "地球为什么是圆的", "keywords": []},
]

# 路由期望：eval-set 20 题里 17 题 RAG 类 + q19/q20 超纲类（知识库实际有对应工单文档，
# M5 实测 2/2 全命中）→ 期望 rag；q18 天气（0 关键词真闲聊）→ 期望 direct
EXPECT_DIRECT_IDS = {18}


def expected_route(item: dict) -> str:
    return "direct" if item["id"] in EXPECT_DIRECT_IDS or item["id"] >= 101 else "rag"


def run_one(item: dict, router_on: bool) -> dict:
    conv = f"m6{'on' if router_on else 'off'}q{item['id']}-{uuid.uuid4().hex[:4]}"
    start = time.perf_counter()
    error = ""
    final: dict = {}
    try:
        final = build_graph(critic_enabled=True, router_enabled=router_on).invoke(
            {"query": item["question"], "revision_count": 0,
             "meta": {"conversation_id": conv}})
    except Exception as e:  # noqa: BLE001——评估脚本要活下来而不是完美（同 m4_eval）
        error = f"{type(e).__name__}: {e}"[:120]
    duration = time.perf_counter() - start
    meta = final.get("meta") or {}
    answer = final.get("agent_response", "")
    keywords = item.get("keywords", [])
    hits = [k for k in keywords if k in answer]
    route = meta.get("route", "(router off)")
    return {
        "id": item["id"], "type": item.get("type", ""), "question": item["question"],
        "expected_route": expected_route(item), "route": route,
        "route_correct": route == expected_route(item),
        "answer": answer, "error": error,
        "keywords_total": len(keywords), "keyword_hits": len(hits),
        "hit_words": " ".join(hits),
        "duration_s": round(duration, 1),
        "router_ms": meta.get("router_duration_ms"),
        "executor_ms": meta.get("executor_duration_ms"),
        "revision_count": final.get("revision_count", 0),
        "conversation_id": conv,
    }


def judge_direct(rows: list[dict]) -> None:
    """A 遍 direct 行补判：直答分支不过图内 critic（闲聊豁免），事后用同一判官口径
    验证直答质量——豁免不等于放任不管，报告里要有直答合格率。
    rows 必须是全量行（含 rag），判官只处理 direct 行、原对象就地补判，最后回写全量——
    否则 rag 行会被冲掉（首跑踩过：30 行文件被覆写成 10 行）。"""
    for r in rows:
        if r.get("route") != "direct" or r.get("error") or r.get("judge_passed") is not None:
            continue
        try:
            v: CriticVerdict = get_llm(temperature=0.2).with_structured_output(
                CriticVerdict, method="function_calling").invoke(
                CRITIC_PROMPT.format(query=r["question"], answer=r["answer"]))
            r["judge_passed"], r["judge_reason"] = v.passed, v.reason[:60]
        except Exception as e:  # noqa: BLE001
            r["judge_passed"], r["judge_reason"] = False, f"判官异常: {type(e).__name__}"
        print(f"  [判官 {r['id']}] {'合格' if r['judge_passed'] else '不合格'} {r['judge_reason']}")
    _write(OUT_DIR / "m6_router_on.jsonl", rows)


def _write(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else "a"
    rag_items = json.loads(EVAL_SET.read_text(encoding="utf-8"))

    if mode == "a":
        items = rag_items + M6_DIRECT_QUESTIONS
        subset = sys.argv[2] if len(sys.argv) > 2 else ""  # 逗号分隔 id，补测模式（同 m4_eval）
        if subset:
            want = {int(i) for i in subset.split(",")}
            items = [it for it in items if it["id"] in want]
        print(f"[配置] A 遍 router on | {len(rag_items)} RAG + {len(M6_DIRECT_QUESTIONS)} direct"
              + (f" | 子集补测 {sorted(want)}" if subset else "") + f" | 并发 {WORKERS}")
        rows, t0 = [], time.perf_counter()
        with ThreadPoolExecutor(max_workers=WORKERS) as pool:
            futures = {pool.submit(run_one, it, True): it for it in items}
            for fut in as_completed(futures):
                r = fut.result()
                rows.append(r)
                flag = " [错误]" if r["error"] else ""
                ok = "✓" if r["route_correct"] else "✗路由错"
                print(f"  [{r['id']:>3}] {r['duration_s']:>5}s [{r['route']:>6}] {ok} "
                      f"命中{r['keyword_hits']}/{r['keywords_total']}{flag} {r['question']}")
        print(f"\n[A 遍完成] 耗时 {(time.perf_counter() - t0) / 60:.1f} 分钟")
        if subset:  # 子集补测：按 id 合并回全量文件（错误行被成功行覆盖，同 m4_eval 约定）
            path = OUT_DIR / "m6_router_on.jsonl"
            existing = {r["id"]: r for r in
                        (json.loads(l) for l in path.read_text(encoding="utf-8").splitlines())}
            existing.update({r["id"]: r for r in rows})
            rows = [existing[k] for k in sorted(existing)]
        rows.sort(key=lambda r: r["id"])
        _write(OUT_DIR / "m6_router_on.jsonl", rows)

        all_rows = rows
        correct = sum(1 for r in all_rows if r["route_correct"] and not r["error"])
        direct = [r for r in all_rows if r["route"] == "direct" and not r["error"]]
        rag = [r for r in all_rows if r["route"] == "rag" and not r["error"]]
        d_lat = sorted(r["duration_s"] for r in direct)
        rag_lat = sorted(r["duration_s"] for r in rag)
        total_kw = sum(r["keywords_total"] for r in all_rows if r["route"] == "rag")
        total_hits = sum(r["keyword_hits"] for r in all_rows if r["route"] == "rag")
        errors = [r for r in all_rows if r["error"]]
        print(f"  路由分流正确率: {correct}/{len(all_rows) - len(errors)} = "
              f"{correct / (len(all_rows) - len(errors)):.0%}"
              + (f"（另有 {len(errors)} 题运行错误未计入）" if errors else ""))
        if direct:
            print(f"  direct 分支: {len(direct)} 题, 中位 {d_lat[len(d_lat)//2]:.1f}s, "
                  f"max {d_lat[-1]:.1f}s")
        if rag:
            print(f"  rag 分支: {len(rag)} 题, 中位 {rag_lat[len(rag_lat)//2]:.1f}s, "
                  f"关键词命中 {total_hits}/{total_kw} = {total_hits / total_kw:.1%}")
        judge_direct(all_rows)
    elif mode == "b":
        items = M6_DIRECT_QUESTIONS
        subset = sys.argv[2] if len(sys.argv) > 2 else ""
        if subset:
            want = {int(i) for i in subset.split(",")}
            items = [it for it in items if it["id"] in want]
        print(f"[配置] B 遍 router off（direct 题走全链对照组） | {len(items)} 题"
              + (f" | 子集补测 {sorted(want)}" if subset else "") + f" | 并发 {WORKERS}")
        rows, t0 = [], time.perf_counter()
        with ThreadPoolExecutor(max_workers=WORKERS) as pool:
            futures = {pool.submit(run_one, it, False): it for it in items}
            for fut in as_completed(futures):
                r = fut.result()
                rows.append(r)
                flag = " [错误]" if r["error"] else ""
                print(f"  [{r['id']:>3}] {r['duration_s']:>5}s 命中{r['keyword_hits']}"
                      f"/{r['keywords_total']} 回炉{r['revision_count']}{flag} {r['question']}")
        print(f"\n[B 遍完成] 耗时 {(time.perf_counter() - t0) / 60:.1f} 分钟")
        if subset:
            path = OUT_DIR / "m6_router_off.jsonl"
            existing = {r["id"]: r for r in
                        (json.loads(l) for l in path.read_text(encoding="utf-8").splitlines())}
            existing.update({r["id"]: r for r in rows})
            rows = [existing[k] for k in sorted(existing)]
        rows.sort(key=lambda r: r["id"])
        _write(OUT_DIR / "m6_router_off.jsonl", rows)
        ok = [r for r in rows if not r["error"]]
        lat = sorted(r["duration_s"] for r in ok)
        print(f"  全链延迟: 中位 {lat[len(lat)//2]:.1f}s, max {lat[-1]:.1f}s（{len(ok)}/{len(rows)} 成功）")
    elif mode == "j":
        rows = [json.loads(l) for l in
                (OUT_DIR / "m6_router_on.jsonl").read_text(encoding="utf-8").splitlines()]
        judge_direct(rows)
    else:
        print("用法: m6_eval.py a|b|j")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
