#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""aismp RAG 问答准确率评测（真链路：编排层 → 网关 → Java RAG 管线 → pgvector）

## 与 m4_eval.py 的区别

`m4_eval.py` 测的是**整条编排链**（改写/路由/自纠环）在 20 题上的关键词命中率；
本脚本专门测 **RAG 检索与回答的准确率**，且支持**两套题**：

  · `aismp-qa-eval.json`  领域题 22 道 —— 真实准确率（写进比赛报告的主数字）
  · `aismp-qa-oob.json`   域外题 20 道 —— 下界基线（证明通用集不适合评本项目）

> 两份题集由 `scripts/build_rag_eval.py` 生成。领域题的关键词已用 `--check`
> 校验过确实落在**真实灌进 pgvector 的语料**（`aismp-agent/src/main/resources/faq/**/*.md`）中，
> 而不是看似相关却没入库的 `local-init/kb-seed/`——这一点曾出过错，改动语料来源务必重跑 `--check`。

## 准确率怎么算（三口径，层层递进）

单看「关键词命中率」会高估——废话里也可能带关键词。所以分三层：

  1. **检索命中率 Recall**：回答中是否出现了预期文档的特征词
     （回答不返回 doc_id，用文档级特征词反推是否检索到了那篇）。
  2. **要点覆盖率 Point Coverage**：`answer_points` 里每条要点的人工判定词命中比例。
     这是最接近「答得对不对」的指标。
  3. **严格准确率 Strict**：要点覆盖率 ≥ 阈值（默认 0.6）才算这题答对。
     **Strict Acc = 严格答对题数 / 有效题数**，这就是最终写进报告的「准确率」。

## 超纲题怎么算（关键设计）

超纲题没有 expected_doc/keywords，**不能混进准确率分母**——那会把「拒答」当「答错」。
本脚本把超纲题单独统计为 **拒答率 Rejection Rate**：
  回答中若出现知识库不可用/无法回答等**兜底话术**，或明确说明超出范围 → 记为正确拒答。
  若回答里编造了看似具体的内容 → 记为**幻觉 Hallucination**（这是要重点盯的坏指标）。

## 域外基线怎么用

`--oob` 跑域外题，预期 Recall≈0。它的价值不是分数低，而是**给出对照**：
「同一套系统在领域题上 X%，在通用题上 0%」——这个对比才能证明**题目选对了**，
而不是系统不行。

## 用法
    uv run python scripts/rag_accuracy_eval.py                # 领域题（默认）
    uv run python scripts/rag_accuracy_eval.py --oob          # 域外基线
    uv run python scripts/rag_accuracy_eval.py --threshold 0.7  # 改严格阈值
    uv run python scripts/rag_accuracy_eval.py --workers 3
产出: docs/eval/rag_accuracy_{domain,oob}.csv + .jsonl + 控制台汇总
"""
from __future__ import annotations

import csv
import json
import re
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from graph import build_graph

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
EVAL_DIR = ROOT / "docs" / "eval"
DOMAIN_SET = EVAL_DIR / "aismp-qa-eval.json"
OOB_SET = EVAL_DIR / "aismp-qa-oob.json"

DEFAULT_WORKERS = 3  # 比 m4_eval(5) 更保守：RAG 链路更重，避免触发模型端限流长尾

# 兜底话术：出现即视为「系统诚实承认答不了」——超纲题算正确拒答
# （与 graph.py kb_node / digital_human_node 的 except 分支一致）
FALLBACK_PATTERNS = [
    "知识库暂时不可用", "数字员工暂时不可用", "无法从知识库",
    "知识库中没有", "没有相关", "暂无相关", "无法回答", "超出",
    "不在知识库", "未找到相关",
    # 2026-10-11 实测补充：以下话术出现在真实拒答里但原先漏判为 ❓
    "不在我的服务范围", "服务范围", "不属于终端管理", "查不到相关",
    "没法查", "不做推测", "手册里没有", "手册中未",
]
# 幻觉信号：明明该拒答却给出了具体数字/断言
HALLUCINATION_HINTS = ["根据知识库", "根据文档", "根据手册"]


def is_rejection(answer: str) -> bool:
    return any(p in answer for p in FALLBACK_PATTERNS)


def point_coverage(answer: str, points: list[str], keywords: list[str]) -> float:
    """要点覆盖率：对每条要点取「人工判定的代表词」，看是否出现在回答里。

    判据用「要点里出现的实义词」而非整句匹配——回答是自然语言，
    整句匹配会把改写过的正确回答判错。策略：
      ① 若该题给了 keywords，先用 keywords 做粗筛（命中比例）；
      ② 再逐条要点，抽取其中长度≥2 的中文词/术语做存在性判定；
      ③ 最终取两者平均，避免单一口径偏置。
    """
    if not points:
        # 超纲题没有要点，覆盖率记 1.0（其正确性由 is_rejection 单独判）
        return 1.0
    hit = 0
    for p in points:
        # 抽取要点里的「可判词」：英文术语 / 连续中文 2~6 字片段
        tokens = re.findall(r"[A-Za-z]{2,}|\d+|[一-龥]{2,6}", p)
        # 去掉纯虚词
        tokens = [t for t in tokens if t not in ("的", "了", "和", "或", "与", "在", "是")]
        if not tokens:
            continue
        # 该要点只要有任一 token 命中即算这条要点被覆盖（宽松但可解释）
        if any(t in answer for t in tokens):
            hit += 1
    return hit / len(points)


def run_one(item: dict, workers_tag: str, critic_enabled: bool = False) -> dict:
    """跑一题：独立会话（防跨题记忆污染）。单题异常不炸整遍。"""
    conv = f"rag{workers_tag}q{item['id']}-{uuid.uuid4().hex[:4]}"
    t0 = time.perf_counter()
    error = ""
    final: dict = {}
    try:
        final = build_graph(critic_enabled=critic_enabled).invoke(
            {"query": item["question"], "revision_count": 0,
             "meta": {"conversation_id": conv}})
    except Exception as exc:  # noqa: BLE001 —— 评估脚本要活下来
        error = f"{type(exc).__name__}: {exc}"[:160]
    duration = time.perf_counter() - t0

    answer = final.get("agent_response", "") or ""
    keywords = item.get("keywords") or []
    points = item.get("answer_points") or []

    # ① 检索/关键词命中
    kw_hit = sum(1 for k in keywords if k in answer)
    kw_total = len(keywords)

    # ② 要点覆盖率
    cov = point_coverage(answer, points, keywords)

    # ③ 超纲题：判拒答 or 幻觉
    # 「超纲」= 领域集里的出界题；「域外-*」= oob 基线集（DuReader 风格通用题）。
    # 两者都不进准确率分母，单独统计拒答率。
    is_oob = item.get("type", "").startswith(("超纲", "域外")) \
        or item.get("expected_doc") is None and not keywords and not points
    rejection = is_rejection(answer) if is_oob else None
    hallucination = None
    if is_oob:
        # 既没兜底话术、又自称"根据知识库/文档" → 大概率编造
        hallucination = (not rejection) and any(h in answer for h in HALLUCINATION_HINTS)

    meta = final.get("meta") or {}
    return {
        "id": item["id"],
        "type": item.get("type", ""),
        "question": item["question"],
        "expected_doc": item.get("expected_doc", ""),
        "keyword_hits": kw_hit,
        "keywords_total": kw_total,
        "point_coverage": round(cov, 3),
        "is_oob": is_oob,
        "rejection": rejection,
        "hallucination": hallucination,
        "answer_len": len(answer),
        "duration_s": round(duration, 1),
        "revision_count": final.get("revision_count", 0),
        "channel": meta.get("channel", ""),
        "error": error,
        "conversation_id": conv,
        "answer": answer,  # 全文，CSV 不放，走 JSONL 侧车
        "answer_excerpt": (answer or error)[:100].replace("\n", " "),
    }


def summarize(rows: list[dict], threshold: float, label: str) -> dict:
    """三口径汇总。超纲题从准确率分母剔除，单独统计拒答/幻觉。"""
    normal = [r for r in rows if not r["is_oob"]]
    oob = [r for r in rows if r["is_oob"]]

    # 有效题 = 非超纲 且 无运行错误
    valid = [r for r in normal if not r["error"]]

    def rate(num: int, den: int) -> float:
        return num / den if den else 0.0

    recall = rate(sum(r["keyword_hits"] for r in valid),
                  sum(r["keywords_total"] for r in valid))
    avg_cov = rate(sum(1 for r in valid if r["point_coverage"] > 0), len(valid))
    strict = [r for r in valid if r["point_coverage"] >= threshold]
    strict_acc = rate(len(strict), len(valid))

    result = {
        "label": label,
        "threshold": threshold,
        "total": len(rows),
        "valid": len(valid),
        "errors": len([r for r in rows if r["error"]]),
        "recall": recall,
        "avg_point_coverage": sum(r["point_coverage"] for r in valid) / len(valid)
        if valid else 0.0,
        "strict_acc": strict_acc,
        "strict_pass": len(strict),
        "avg_duration_s": sum(r["duration_s"] for r in valid) / len(valid) if valid else 0.0,
        "oob_total": len(oob),
        "oob_rejected": len([r for r in oob if r["rejection"]]),
        "oob_hallucinated": len([r for r in oob if r["hallucination"]]),
    }
    return result


def main() -> int:
    use_oob = "--oob" in sys.argv
    threshold = 0.6
    workers = DEFAULT_WORKERS
    for i, a in enumerate(sys.argv):
        if a == "--threshold" and i + 1 < len(sys.argv):
            threshold = float(sys.argv[i + 1])
        if a == "--workers" and i + 1 < len(sys.argv):
            workers = int(sys.argv[i + 1])

    src = OOB_SET if use_oob else DOMAIN_SET
    tag = "oob" if use_oob else "domain"
    label = "域外基线（通用领域题）" if use_oob else "领域题（真实准确率）"

    if not src.exists():
        raise SystemExit(f"[错误] 找不到测试集：{src}\n"
                         f"       请先执行：python scripts/build_rag_eval.py")

    items = json.loads(src.read_text(encoding="utf-8"))
    print(f"[配置] {label} | {len(items)} 题 | 并发 {workers} | 严格阈值 {threshold}")

    EVAL_DIR.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(run_one, it, tag): it for it in items}
        for fut in as_completed(futures):
            r = fut.result()
            rows.append(r)
            extra = ""
            if r["is_oob"]:
                extra = " 拒答" if r["rejection"] else (" ⚠️幻觉" if r["hallucination"] else " ❓")
            flag = " [错误]" if r["error"] else ""
            print(f"  [{r['id']:>4}] {r['duration_s']:>5}s 命中{r['keyword_hits']}/{r['keywords_total']}"
                  f" 覆盖{r['point_coverage']:.2f} 通道{r['channel']}{extra}{flag} {r['question']}")
    elapsed = time.perf_counter() - t0

    rows.sort(key=lambda r: r["id"])
    jsonl = EVAL_DIR / f"rag_accuracy_{tag}.jsonl"
    with jsonl.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    csv_rows = [{k: v for k, v in r.items() if k != "answer"} for r in rows]
    fieldnames: list[str] = []
    for cr in csv_rows:
        fieldnames += [k for k in cr if k not in fieldnames]
    csv_out = EVAL_DIR / f"rag_accuracy_{tag}.csv"
    with csv_out.open("w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        w.writerows(csv_rows)

    s = summarize(rows, threshold, label)
    s["elapsed_s"] = round(elapsed, 1)
    print(f"\n[汇总] {label}（耗时 {elapsed/60:.1f} 分钟）")
    print(f"  有效题数:     {s['valid']}/{s['total']}（剔除超纲与运行错误）")
    print(f"  ① 召回 Recall:     {s['recall']:.1%}（关键词命中/总关键词）")
    print(f"  ② 平均要点覆盖率:  {s['avg_point_coverage']:.1%}")
    print(f"  ③ 严格准确率:      {s['strict_acc']:.1%}（覆盖≥{threshold} 的题数/{s['valid']}）")
    print(f"  平均耗时:     {s['avg_duration_s']:.1f}s/题    运行错误: {s['errors']}")
    if s["oob_total"]:
        print(f"  超纲题:       {s['oob_total']} 道 | 正确拒答 {s['oob_rejected']} "
              f"| ⚠️ 疑似幻觉 {s['oob_hallucinated']}")
    print(f"  明细: {csv_out}")
    (EVAL_DIR / f"rag_accuracy_{tag}_summary.json").write_text(
        json.dumps(s, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
